package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.PrototypeDemoScriptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实HTTP/PostgreSQL检验有序JSON脚本、固定授权、分享投影、幂等CAS与原子清理。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000"
        })
@Testcontainers
class PrototypeHubDemoScriptIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();
    private static final String PAGE = PrototypePageInfo.page("Home", "index.html").pageId();
    private static final String CHILD = PrototypePageInfo.page("Child", "child.html").pageId();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        properties.add("spring.r2dbc.username", DATABASE::getUsername);
        properties.add("spring.r2dbc.password", DATABASE::getPassword);
        properties.add("spring.r2dbc.pool.max-size", () -> 2);
        properties.add("spring.r2dbc.pool.initial-size", () -> 1);
        properties.add("proto.admin.password", () -> PASSWORD);
        properties.add("prototype-hub.storage.root", FILES::toString);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ReactiveRepository<PrototypeProjectEntity, String> projects;
    @Autowired ReactiveRepository<PrototypeDemoScriptEntity, String> scripts;
    @Autowired ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    @Autowired PrototypeRetentionService retention;
    @Autowired FileCleanupService cleanup;
    private String admin;

    @BeforeEach
    void isolate() {
        PrototypeHubTestData.clear(database);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
    }

    @AfterAll
    static void removeFiles() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    @Test
    void jsonCodecPreservesOrderedStableStepsAndOriginalKeysReadCurrentRows() throws Exception {
        Fixture f = fixture();
        var input = create("original-create");
        input.put("prerequisites", null);
        JsonNode row = result(HttpMethod.POST, endpoint(f), f.owner().token(), input);
        String id = row.path("id").asText();
        assertNotEquals("original-create", id);
        assertEquals(13, row.size());
        assertEquals(0, row.path("configVersion").asInt());
        assertEquals("", row.path("prerequisites").asText());
        assertFalse(row.path("shareEnabled").asBoolean());
        assertEquals(row, result(HttpMethod.GET, endpoint(f) + "/" + id, f.owner().token(), null));
        assertInstanceOf(
                DemoScriptStep.class, scripts.findById(id).block(TIMEOUT).getSteps().getFirst());
        assertEquals(row, result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        assertEquals(
                List.of("start", "child", "return"),
                List.of(
                        row.path("steps").get(0).path("id").asText(),
                        row.path("steps").get(1).path("id").asText(),
                        row.path("steps").get(2).path("id").asText()));
        assertEquals(
                row.path("steps").get(0).path("pageId"), row.path("steps").get(2).path("pageId"));
        var reordered = new LinkedHashMap<>(input);
        reordered.put("expectedVersion", 0);
        reordered.put("clientRequestId", "original-update");
        reordered.put("shareEnabled", true);
        reordered.put(
                "steps",
                List.of(
                        Map.of("id", "return", "pageId", PAGE, "narration", "Revisit first"),
                        Map.of("id", "start", "pageId", PAGE),
                        Map.of("id", "child", "pageId", CHILD, "narration", "Last")));
        JsonNode enabled =
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), reordered);
        assertEquals("return", enabled.path("steps").get(0).path("id").asText());
        assertEquals("", enabled.path("steps").get(1).path("narration").asText());
        var disabled = new LinkedHashMap<>(reordered);
        disabled.put("expectedVersion", 1);
        disabled.put("clientRequestId", "disable");
        disabled.remove("shareEnabled");
        JsonNode current =
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), disabled);
        assertFalse(current.path("shareEnabled").asBoolean());
        assertEquals(current, result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        assertEquals(
                current,
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), reordered));
        var changed = new LinkedHashMap<>(input);
        changed.put("title", "Different");
        call(HttpMethod.POST, endpoint(f), f.owner().token(), changed, 409);
        var stale = new LinkedHashMap<>(reordered);
        stale.put("clientRequestId", "fresh-stale");
        call(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), stale, 409);
        JsonNode page =
                result(HttpMethod.POST, endpoint(f) + "/_query", f.owner().token(), Map.of());
        assertEquals(1, page.path("total").asInt());
        assertEquals(9, page.path("data").get(0).size());
        for (String excluded : List.of("steps", "objective", "prerequisites", "resetNote"))
            assertFalse(page.path("data").get(0).has(excluded));
        String next =
                upload(f.project(), f.version(), f.revision(), "R2")
                        .path("currentRevisionId")
                        .asText();
        String nextEndpoint = endpoint(f).replace(f.revision(), next);
        assertEquals(
                0,
                result(HttpMethod.POST, nextEndpoint + "/_query", f.owner().token(), Map.of())
                        .path("total")
                        .asInt());
        call(HttpMethod.GET, nextEndpoint + "/" + id, f.owner().token(), null, 404);
        assertEquals(
                current, result(HttpMethod.GET, endpoint(f) + "/" + id, f.owner().token(), null));
        var deletion = mutation(2, "original-delete");
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + id + "/_delete",
                                f.owner().token(),
                                deletion)
                        .asBoolean());
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + id + "/_delete",
                                f.owner().token(),
                                deletion)
                        .asBoolean());
        call(HttpMethod.POST, endpoint(f), f.owner().token(), input, 409);
        call(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), reordered, 409);
        assertEquals(0, count("select count(*) as count from p_demo_script"));
        assertEquals(
                13,
                count(
                        "select count(*) as count from information_schema.columns where table_schema='public' and table_name='p_demo_script'"));
        assertEquals(
                4,
                count(
                        "select count(*) as count from pg_indexes where schemaname='public' and tablename='p_demo_script' and indexname like 'idx_demo_script_%'"));
        assertEquals(
                "固定修订人工任务演示脚本",
                database.sql("select obj_description('p_demo_script'::regclass) as description")
                        .map((r, m) -> r.get("description", String.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(
                13,
                count(
                        "select count(*) as count from pg_attribute where attrelid='p_demo_script'::regclass and attnum>0 and not attisdropped and coalesce(col_description(attrelid,attnum),'')<>''"));
    }

    @Test
    void maxSafeCasCannotIncrementAndCanonicalEmptyTextReplaysWithoutASecondRow() throws Exception {
        Fixture f = fixture();
        var original = create("canonical-create");
        original.put("prerequisites", null);
        original.put("resetNote", null);
        JsonNode row = result(HttpMethod.POST, endpoint(f), f.owner().token(), original);
        var same = create("canonical-create");
        same.put("prerequisites", "");
        same.put("resetNote", "");
        same.put("shareEnabled", false);
        assertEquals(row, result(HttpMethod.POST, endpoint(f), f.owner().token(), same));
        String id = row.path("id").asText();
        database.sql("update p_demo_script set config_version=9007199254740991 where id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        var update = create("max-update");
        update.put("expectedVersion", 9007199254740991L);
        call(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update, 409);
        assertEquals(
                9007199254740991L,
                result(HttpMethod.GET, endpoint(f) + "/" + id, f.owner().token(), null)
                        .path("configVersion")
                        .asLong());
        var reorder = create("canonical-create");
        reorder.put(
                "steps",
                List.of(
                        Map.of("id", "child", "pageId", CHILD),
                        Map.of("id", "start", "pageId", PAGE)));
        call(HttpMethod.POST, endpoint(f), f.owner().token(), reorder, 409);
        assertEquals(1, count("select count(*) as count from p_demo_script"));
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + id + "/_delete",
                                f.owner().token(),
                                mutation(9007199254740991L, "max-delete"))
                        .asBoolean());
    }

    @Test
    void currentMenuProjectAndActiveUserIntersectWithoutRequiringQueryToSave() throws Exception {
        Fixture f = fixture();
        Actor editor = actor(List.of("query", "save")),
                viewer = actor(List.of("query", "save")),
                outsider = actor(List.of("query", "save")),
                saveOnly = actor(List.of("save")),
                readOnly = actor(List.of("query"));
        for (Actor user : List.of(editor, saveOnly, readOnly))
            member(f.project(), user.id(), "editor");
        member(f.project(), viewer.id(), "viewer");
        var input = create("editor-create");
        JsonNode row = result(HttpMethod.POST, endpoint(f), editor.token(), input);
        String id = row.path("id").asText();
        JsonNode save = result(HttpMethod.POST, endpoint(f), saveOnly.token(), create("save-only"));
        call(
                HttpMethod.GET,
                endpoint(f) + "/" + save.path("id").asText(),
                saveOnly.token(),
                null,
                403);
        call(HttpMethod.POST, endpoint(f) + "/_query", saveOnly.token(), Map.of(), 403);
        for (String token : List.of(viewer.token(), readOnly.token())) {
            result(HttpMethod.GET, endpoint(f) + "/" + id, token, null);
            call(HttpMethod.POST, endpoint(f), token, create(id()), 403);
            var update = create(id());
            update.put("expectedVersion", 0);
            call(HttpMethod.PUT, endpoint(f) + "/" + id, token, update, 403);
            call(
                    HttpMethod.POST,
                    endpoint(f) + "/" + id + "/_delete",
                    token,
                    mutation(0, id()),
                    403);
        }
        call(HttpMethod.POST, endpoint(f), outsider.token(), create(id()), 404);
        call(HttpMethod.GET, endpoint(f) + "/" + id, outsider.token(), null, 404);
        call(HttpMethod.POST, endpoint(f) + "/_query", null, Map.of(), 401);
        database.sql("delete from p_project_member where project_id=:project and user_id=:user")
                .bind("project", f.project())
                .bind("user", editor.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.POST, endpoint(f), editor.token(), input, 404);
        member(f.project(), editor.id(), "editor");
        database.sql("update s_user set status=0 where id=:id")
                .bind("id", editor.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.POST, endpoint(f), editor.token(), input, 403);
        var invalid = create(id());
        invalid.put(
                "steps",
                List.of(
                        Map.of("id", "same", "pageId", PAGE),
                        Map.of("id", "same", "pageId", CHILD)));
        call(HttpMethod.POST, endpoint(f), f.owner().token(), invalid, 400);
        invalid.put("steps", List.of(Map.of("id", "valid", "pageId", "other-revision")));
        call(HttpMethod.POST, endpoint(f), f.owner().token(), invalid, 404);
        invalid.put("steps", List.of(Map.of("id", "valid", "pageId", PAGE, "command", "click")));
        call(HttpMethod.POST, endpoint(f), f.owner().token(), invalid, 400);
        assertEquals(2, count("select count(*) as count from p_demo_script"));
    }

    @Test
    void fixedLatestAndPasswordGrantsOnlyReadExplicitlyEnabledScriptsWithoutSessionWrites()
            throws Exception {
        Fixture f = fixture();
        JsonNode fixed = publicGrant(f, "fixed", "demo-password"),
                latest = publicGrant(f, "latest", null);
        JsonNode privateRow =
                result(HttpMethod.POST, endpoint(f), f.owner().token(), create("private"));
        for (int index = 0; index < 3; index++) {
            var input = create("shared-" + index);
            input.put("shareEnabled", true);
            result(HttpMethod.POST, endpoint(f), f.owner().token(), input);
        }
        long sessions = count("select count(*) as count from p_review_session"),
                guests = count("select count(*) as count from p_review_guest"),
                visits = count("select count(*) as count from p_share_visit");
        for (JsonNode grant : List.of(fixed, latest)) {
            String path =
                    "/prototype-hub/public/shares/"
                            + grant.path("token").asText()
                            + "/demo-scripts";
            var body = new LinkedHashMap<>(grantBody(grant));
            body.put("pageSize", 2);
            JsonNode first = result(HttpMethod.POST, path + "/_query", null, body);
            body.put("pageIndex", 1);
            JsonNode second = result(HttpMethod.POST, path + "/_query", null, body);
            assertEquals(3, first.path("total").asInt());
            assertEquals(2, first.path("data").size());
            assertEquals(3, second.path("total").asInt());
            assertEquals(1, second.path("data").size());
            assertNotEquals(
                    first.path("data").get(0).path("id"), second.path("data").get(0).path("id"));
            String id = first.path("data").get(0).path("id").asText();
            assertEquals(3, first.path("data").get(0).size());
            JsonNode detail =
                    result(HttpMethod.POST, path + "/" + id + "/context", null, grantBody(grant));
            assertEquals(7, detail.size());
            assertEquals(3, detail.path("steps").size());
            assertEquals("Explain <b>ordinary text</b>", detail.path("objective").asText());
            call(
                    HttpMethod.POST,
                    path + "/" + privateRow.path("id").asText() + "/context",
                    null,
                    grantBody(grant),
                    404);
            var bad = new LinkedHashMap<>(grantBody(grant));
            bad.put("accessKey", "wrong");
            call(HttpMethod.POST, path + "/_query", null, bad, 403);
            bad.put("revisionId", "wrong");
            call(HttpMethod.POST, path + "/" + id + "/context", null, bad, 403);
        }
        upload(f.project(), f.version(), f.revision(), "R2");
        String publicPath =
                "/prototype-hub/public/shares/" + latest.path("token").asText() + "/demo-scripts";
        JsonNode old = result(HttpMethod.POST, publicPath + "/_query", null, grantBody(latest));
        assertEquals(3, old.path("total").asInt());
        String shared = old.path("data").get(0).path("id").asText();
        var disabled = create("disable-shared");
        disabled.put("expectedVersion", 0);
        result(HttpMethod.PUT, endpoint(f) + "/" + shared, f.owner().token(), disabled);
        call(HttpMethod.POST, publicPath + "/" + shared + "/context", null, grantBody(latest), 404);
        assertEquals(
                2,
                result(HttpMethod.POST, publicPath + "/_query", null, grantBody(latest))
                        .path("total")
                        .asInt());
        database.sql("update p_share set revoked=true where token=:token")
                .bind("token", latest.path("token").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.POST, publicPath + "/_query", null, grantBody(latest), 404);
        String fixedPath =
                "/prototype-hub/public/shares/" + fixed.path("token").asText() + "/demo-scripts";
        database.sql("update p_share set expires_at=1 where token=:token")
                .bind("token", fixed.path("token").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.POST, fixedPath + "/_query", null, grantBody(fixed), 404);
        assertEquals(sessions, count("select count(*) as count from p_review_session"));
        assertEquals(guests, count("select count(*) as count from p_review_guest"));
        assertEquals(visits, count("select count(*) as count from p_share_visit"));
        assertEquals(0, count("select count(*) as count from p_revision_approval"));
    }

    @Test
    void simultaneousWritesHaveOneCasWinnerAndPurgeIsAtomicAcrossTheNewGraph() throws Exception {
        Fixture f = fixture();
        var input = create("parallel-create");
        var a =
                CompletableFuture.supplyAsync(
                        () -> result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        var b =
                CompletableFuture.supplyAsync(
                        () -> result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        String id = a.get(30, TimeUnit.SECONDS).path("id").asText();
        assertEquals(id, b.get(30, TimeUnit.SECONDS).path("id").asText());
        var one = create("race-a");
        one.put("expectedVersion", 0);
        var two = create("race-b");
        two.put("expectedVersion", 0);
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + id,
                                        f.owner().token(),
                                        one));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + id,
                                        f.owner().token(),
                                        two));
        assertEquals(
                List.of(200, 409),
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        call(HttpMethod.GET, endpoint(f) + "/" + id, f.owner().token(), null, 404);
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                f.owner().token(),
                null);
        assertEquals(
                1,
                result(HttpMethod.GET, endpoint(f) + "/" + id, f.owner().token(), null)
                        .path("configVersion")
                        .asInt());
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        database.sql("update p_version set deleted_time=:cutoff where id=:id")
                .bind(
                        "cutoff",
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 1000)
                .bind("id", f.version())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        sql("create table f29_purge_blocker(script_id varchar(64) references p_demo_script(id))");
        try {
            sql("insert into f29_purge_blocker select id from p_demo_script");
            long receipts = count("select count(*) as count from p_creation_receipt"),
                    pending = count("select count(*) as count from p_file_cleanup");
            assertThrows(
                    RuntimeException.class,
                    () -> retention.purgeVersion(f.version()).block(TIMEOUT));
            assertEquals(1, count("select count(*) as count from p_demo_script"));
            assertEquals(1, count("select count(*) as count from p_version"));
            assertEquals(receipts, count("select count(*) as count from p_creation_receipt"));
            assertEquals(pending, count("select count(*) as count from p_file_cleanup"));
        } finally {
            sql("drop table f29_purge_blocker");
        }
        assertTrue(retention.purgeVersion(f.version()).block(TIMEOUT));
        assertEquals(0, count("select count(*) as count from p_demo_script"));
        assertEquals(0, count("select count(*) as count from p_creation_receipt"));
        cleanup.retryPending().block(TIMEOUT);
    }

    @Test
    void orphanProjectSweepIncludesScriptRows() throws Exception {
        Fixture f = fixture();
        result(HttpMethod.POST, endpoint(f), f.owner().token(), create("orphan"));
        database.sql("delete from p_version where id=:id")
                .bind("id", f.version())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_project set deleted_time=:cutoff where id=:id")
                .bind(
                        "cutoff",
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 1000)
                .bind("id", f.project())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeProject(f.project()).block(TIMEOUT));
        assertEquals(0, count("select count(*) as count from p_demo_script"));
    }

    private Fixture fixture() throws Exception {
        Actor owner = actor(List.of("query", "save", "delete"));
        String project = id();
        var row = new PrototypeProjectEntity();
        row.setId(project);
        row.setName("Delivery project");
        row.setOwnerId(owner.id());
        row.setAccessEpoch(id());
        row.setCreatorId(owner.id());
        row.setModifierId(owner.id());
        row.setCreateTime(System.currentTimeMillis());
        row.setModifyTime(System.currentTimeMillis());
        projects.insert(row).block(TIMEOUT);
        JsonNode version = upload(project, null, null, "R1");
        return new Fixture(
                project,
                version.path("id").asText(),
                version.path("currentRevisionId").asText(),
                owner);
    }

    private JsonNode upload(String project, String version, String expected, String label)
            throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String path : List.of("index.html", "child.html")) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(
                        ("<html><body>" + label + "<a href='child.html'>Child</a></body></html>")
                                .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var parts = new MultipartBodyBuilder();
        parts.part("versionName", label);
        if (expected != null) parts.part("expectedRevisionId", expected);
        parts.part(
                        "file",
                        new ByteArrayResource(bytes.toByteArray()) {
                            @Override
                            public String getFilename() {
                                return "site.zip";
                            }
                        })
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        return client.method(version == null ? HttpMethod.POST : HttpMethod.PUT)
                .uri(
                        version == null
                                ? "/prototype-hub/projects/" + project + "/versions"
                                : "/prototype-hub/versions/" + version)
                .header("X-Access-Token", admin)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result");
    }

    private Actor actor(List<String> actions) {
        String role = id();
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Demo script test"));
        result(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        id(),
                        "permission",
                        "prototype-hub-version",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        actions,
                        "state",
                        1));
        String username = "demo-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Demo script test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(user));
        return new Actor(user, login(username));
    }

    private void member(String project, String user, String role) {
        var row = new PrototypeProjectMemberEntity();
        row.setId(id());
        row.setProjectId(project);
        row.setUserId(user);
        row.setRole(role);
        row.setCreateTime(1L);
        members.insert(row).block(TIMEOUT);
    }

    private String login(String username) {
        return result(
                        HttpMethod.POST,
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD))
                .path("token")
                .asText();
    }

    private String endpoint(Fixture f) {
        return "/prototype-hub/versions/"
                + f.version()
                + "/revisions/"
                + f.revision()
                + "/demo-scripts";
    }

    private Map<String, Object> create(String key) {
        var input = new LinkedHashMap<String, Object>();
        input.put("clientRequestId", key);
        input.put("title", "Manual demo");
        input.put("objective", "Explain <b>ordinary text</b>");
        input.put(
                "steps",
                List.of(
                        Map.of("id", "start", "pageId", PAGE, "narration", "Start"),
                        Map.of("id", "child", "pageId", CHILD, "narration", "Child"),
                        Map.of("id", "return", "pageId", PAGE, "narration", "Revisit")));
        return input;
    }

    private Map<String, Object> mutation(long expected, String key) {
        return Map.of("expectedVersion", expected, "clientRequestId", key);
    }

    private void sql(String source) {
        database.sql(source).fetch().rowsUpdated().block(TIMEOUT);
    }

    private JsonNode publicGrant(Fixture f, String mode, String password) {
        var input = new LinkedHashMap<String, Object>();
        input.put("revisionMode", mode);
        if (password != null) input.put("password", password);
        JsonNode share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + f.version() + "/shares",
                        admin,
                        input);
        String path = "/prototype-hub/public/shares/" + share.path("token").asText();
        if (password != null) {
            JsonNode locked = result(HttpMethod.GET, path, null, null);
            assertTrue(locked.path("passwordRequired").asBoolean());
            assertTrue(locked.path("accessKey").isNull());
            return result(HttpMethod.POST, path + "/verify", null, Map.of("password", password));
        }
        return result(HttpMethod.GET, path, null, null);
    }

    private Map<String, Object> grantBody(JsonNode grant) {
        return Map.of(
                "revisionId",
                grant.path("revisionId").asText(),
                "accessKey",
                grant.path("accessKey").asText());
    }

    private long count(String sql) {
        return database.sql(sql).map((r, m) -> r.get("count", Long.class)).one().block(TIMEOUT);
    }

    private JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.bodyValue(body);
        JsonNode response =
                request.exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(response);
        return response;
    }

    private int status(HttpMethod method, String path, String token, Object body) {
        return client.method(method)
                .uri(path)
                .header("X-Access-Token", token)
                .bodyValue(body)
                .exchange()
                .expectBody()
                .returnResult()
                .getStatus()
                .value();
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-demo-items-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Actor(String id, String token) {}

    private record Fixture(String project, String version, String revision, Actor owner) {}
}
