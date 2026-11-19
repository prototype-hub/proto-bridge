package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
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

/** 真实HTTP/PostgreSQL检验固定页交付状态、负责人资格、持久幂等CAS与原子回收生命周期，不访问外部地址。 */
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
class PrototypeHubPageDeliveryIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();
    private static final String PAGE = PrototypePageInfo.page("Home", "index.html").pageId();
    private static final String LONG_PATH =
            (("中".repeat(20) + "a".repeat(120)) + "/").repeat(4) + "详情.html";
    private static final String LONG_NAME = "长中文页面".repeat(100);
    private static final String LONG_PAGE = PrototypePageInfo.page(LONG_NAME, LONG_PATH).pageId();
    private static final String THIRD = PrototypePageInfo.page("Other", "other.html").pageId();
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
    @Autowired ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    @Autowired PrototypeRetentionService retention;
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
    void unsetDoesNotWriteAndFourStatesStayOnTheFixedRevisionWithFullManifestSnapshots()
            throws Exception {
        Fixture f = fixture();
        JsonNode unset = detail(f, f.owner().token(), PAGE);
        assertTrue(unset.path("state").isNull());
        assertTrue(unset.path("updatedAt").isNull());
        assertTrue(unset.path("assignee").isNull());
        assertEquals("", unset.path("reason").asText());
        assertEquals(0, unset.path("configVersion").asLong());
        assertEquals(0, count("select count(*) as count from p_page_delivery"));
        long expected = 0;
        for (String state : List.of("DRAFT", "READY", "IN_PROGRESS", "DONE")) {
            JsonNode saved = save(f, PAGE, save(f, state, null, expected++, id()));
            assertEquals(state, saved.path("state").asText());
            assertEquals(expected, saved.path("configVersion").asLong());
            assertEquals("<b>人工理由 / manual reason</b>", saved.path("reason").asText());
        }
        JsonNode longPage = save(f, LONG_PAGE, save(f, "READY", null, 0, id()));
        assertEquals(LONG_NAME, longPage.path("pageName").asText());
        assertEquals(LONG_PATH, longPage.path("pagePath").asText());
        assertEquals(LONG_PATH, detail(f, f.owner().token(), LONG_PAGE).path("pagePath").asText());
        JsonNode second = upload(f.project(), f.version(), f.revision(), "R2");
        String r2 = second.path("currentRevisionId").asText();
        assertNotEquals(f.revision(), r2);
        JsonNode newPage =
                result(
                        HttpMethod.GET,
                        endpoint(f) + "/" + PAGE + "?revisionId=" + r2,
                        f.owner().token(),
                        null);
        assertTrue(newPage.path("state").isNull());
        assertEquals("DONE", detail(f, f.owner().token(), PAGE).path("state").asText());
        assertEquals(2, count("select count(*) as count from p_page_delivery"));
    }

    @Test
    void concurrentFirstCreationAndUpdatesHaveOneCasWinnerAndReplayReturnsCurrent()
            throws Exception {
        Fixture f = fixture();
        var a = save(f, "READY", null, 0, "first-a");
        var b = save(f, "DONE", null, 0, "first-b");
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + PAGE,
                                        f.owner().token(),
                                        a));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + PAGE,
                                        f.owner().token(),
                                        b));
        assertEquals(
                List.of(200, 409),
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        assertEquals(1, count("select count(*) as count from p_page_delivery"));
        JsonNode row = detail(f, f.owner().token(), PAGE);
        var winning = row.path("state").asText().equals("READY") ? a : b;
        assertEquals(1, save(f, PAGE, winning).path("configVersion").asLong());
        var changed = new LinkedHashMap<>(winning);
        changed.put("reason", "changed same key");
        assertEquals(
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT",
                call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), changed, 409)
                        .path("code")
                        .asText());
        var update = save(f, "IN_PROGRESS", null, 1, "update-a");
        var competing = save(f, "DRAFT", null, 1, "update-b");
        first =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + PAGE,
                                        f.owner().token(),
                                        update));
        second =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + PAGE,
                                        f.owner().token(),
                                        competing));
        assertEquals(
                List.of(200, 409),
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        JsonNode current = detail(f, f.owner().token(), PAGE);
        assertEquals(2, current.path("configVersion").asLong());
        assertEquals(current, save(f, PAGE, winning));
        var exact = save(f, "READY", null, 0, "parallel-exact");
        var exactA = CompletableFuture.supplyAsync(() -> save(f, CHILD, exact));
        var exactB = CompletableFuture.supplyAsync(() -> save(f, CHILD, exact));
        assertEquals(exactA.get(30, TimeUnit.SECONDS), exactB.get(30, TimeUnit.SECONDS));
        assertEquals(1, detail(f, f.owner().token(), CHILD).path("configVersion").asLong());
        // Java Long cache ends at127: compare actual CAS values rather than boxed references.
        sql("update p_page_delivery set config_version=200");
        assertEquals(
                201,
                save(f, PAGE, save(f, "DONE", null, 200, id())).path("configVersion").asLong());
        sql("update p_page_delivery set config_version=9223372036854775807");
        assertEquals(
                "PROTOTYPE_HUB_PAGE_DELIVERY_CONFLICT",
                call(
                                HttpMethod.PUT,
                                endpoint(f) + "/" + PAGE,
                                f.owner().token(),
                                save(f, "DONE", null, Long.MAX_VALUE, id()),
                                409)
                        .path("code")
                        .asText());
    }

    @Test
    void
            projectViewerIsEligibleButAssignmentDoesNotGrantEditAndOldRequestSurvivesUnavailableTarget()
                    throws Exception {
        Fixture f = fixture();
        Actor viewer = actor(List.of("query", "save"));
        member(f.project(), viewer.id(), "viewer");
        var original = save(f, "READY", viewer.id(), 0, "assign-viewer");
        JsonNode assigned = save(f, PAGE, original);
        assertEquals(viewer.id(), assigned.path("assignee").path("userId").asText());
        assertTrue(assigned.path("assignee").path("available").asBoolean());
        assertEquals(assigned, detail(f, viewer.token(), PAGE));
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + PAGE,
                viewer.token(),
                save(f, "DONE", viewer.id(), 1, id()),
                403);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_candidates",
                viewer.token(),
                Map.of("revisionId", f.revision()),
                403);
        removeMember(f, viewer.id());
        JsonNode unavailable = detail(f, f.owner().token(), PAGE);
        assertFalse(unavailable.path("assignee").path("available").asBoolean());
        assertEquals(
                assigned.path("assignee").path("name"), unavailable.path("assignee").path("name"));
        assertEquals(unavailable, save(f, PAGE, original));
        assertEquals(
                "PROTOTYPE_HUB_PAGE_DELIVERY_ASSIGNEE_INVALID",
                call(
                                HttpMethod.PUT,
                                endpoint(f) + "/" + PAGE,
                                f.owner().token(),
                                save(f, "DONE", viewer.id(), 1, id()),
                                400)
                        .path("code")
                        .asText());
        member(f.project(), viewer.id(), "viewer");
        database.sql("update s_user set status=0 where id=:id")
                .bind("id", viewer.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertFalse(
                detail(f, f.owner().token(), PAGE).path("assignee").path("available").asBoolean());
        assertEquals(1, save(f, PAGE, original).path("configVersion").asLong());
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + PAGE,
                f.owner().token(),
                save(f, "DONE", viewer.id(), 1, id()),
                400);
        JsonNode cleared = save(f, PAGE, save(f, "DONE", null, 1, id()));
        assertTrue(cleared.path("assignee").isNull());
        assertEquals(cleared, save(f, PAGE, original));
    }

    @Test
    void candidatesUseActiveCurrentMembershipLiteralSearchAndRealDatabasePaging() throws Exception {
        Fixture f = fixture();
        Actor editor = actor(List.of("query", "save"));
        Actor viewer = actor(List.of("query"));
        Actor inactive = actor(List.of("query"));
        Actor outsider = actor(List.of("query", "save"));
        member(f.project(), editor.id(), "editor");
        member(f.project(), viewer.id(), "viewer");
        member(f.project(), inactive.id(), "viewer");
        database.sql("update s_user set status=0 where id=:id")
                .bind("id", inactive.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        for (String user : List.of(editor.id(), viewer.id()))
            database.sql("update s_user set name=:name where id=:id")
                    .bind("name", "Same%_literal")
                    .bind("id", user)
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        JsonNode candidates = candidates(f, editor.token(), "", 0, 50);
        assertEquals(3, candidates.path("total").asInt());
        var ids = new java.util.HashSet<String>();
        candidates.path("data").forEach(r -> ids.add(r.path("userId").asText()));
        assertEquals(java.util.Set.of(f.owner().id(), editor.id(), viewer.id()), ids);
        assertFalse(ids.contains(outsider.id()));
        String adminUser =
                database.sql("select id from s_user where username='admin'")
                        .map((r, m) -> r.get("id", String.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(adminUser);
        assertFalse(ids.contains(adminUser));
        JsonNode p0 = candidates(f, editor.token(), "%_", 0, 1);
        JsonNode p1 = candidates(f, editor.token(), "%_", 1, 1);
        assertEquals(2, p0.path("total").asInt());
        assertEquals(2, p1.path("total").asInt());
        assertNotEquals(
                p0.path("data").get(0).path("userId"), p1.path("data").get(0).path("userId"));
        assertTrue(
                p0.path("data")
                                .get(0)
                                .path("userId")
                                .asText()
                                .compareTo(p1.path("data").get(0).path("userId").asText())
                        < 0);
        assertEquals(
                0, candidates(f, editor.token(), "does-not-exist", 0, 20).path("total").asInt());
        assertEquals(0, candidates(f, editor.token(), "%_", 2, 1).path("data").size());
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + PAGE,
                f.owner().token(),
                save(f, "READY", outsider.id(), 0, id()),
                400);
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + PAGE,
                f.owner().token(),
                save(f, "READY", adminUser, 0, id()),
                400);
    }

    @Test
    void everyReadAndReplayRechecksCurrentActorPermissionsAndImmutableScope() throws Exception {
        Fixture f = fixture();
        Actor editor = actor(List.of("query", "save"));
        Actor noQuery = actor(List.of("save"));
        Actor noSave = actor(List.of("query"));
        Actor outsider = actor(List.of("query", "save"));
        for (Actor actor : List.of(editor, noQuery, noSave))
            member(f.project(), actor.id(), "editor");
        var input = save(f, "READY", null, 0, "editor-save");
        result(HttpMethod.PUT, endpoint(f) + "/" + PAGE, editor.token(), input);
        call(
                HttpMethod.GET,
                endpoint(f) + "/" + PAGE + "?revisionId=" + f.revision(),
                noQuery.token(),
                null,
                403);
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + CHILD,
                noSave.token(),
                save(f, "READY", null, 0, id()),
                403);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_query",
                outsider.token(),
                Map.of("revisionId", f.revision()),
                404);
        call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, outsider.token(), input, 404);
        call(
                HttpMethod.GET,
                endpoint(f) + "/" + PAGE + "?revisionId=" + f.revision(),
                null,
                null,
                401);
        removeMember(f, editor.id());
        call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, editor.token(), input, 404);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_query",
                editor.token(),
                Map.of("revisionId", f.revision()),
                404);
        Fixture other = fixture();
        var cross = new LinkedHashMap<>(input);
        cross.put("revisionId", other.revision());
        call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), cross, 404);
        call(
                HttpMethod.GET,
                endpoint(f) + "/" + "0".repeat(64) + "?revisionId=" + f.revision(),
                f.owner().token(),
                null,
                404);
        call(HttpMethod.GET, endpoint(f) + "/" + PAGE, f.owner().token(), null, 400);
        assertEquals(1, count("select count(*) as count from p_page_delivery"));
    }

    @Test
    void validationRejectsInvalidStatesAssigneesLengthsAndFractionalNumbersWithoutWriting()
            throws Exception {
        Fixture f = fixture();
        for (Object state : List.of("ready", "", "APPROVED", 2)) {
            var input = save(f, "READY", null, 0, id());
            input.put("state", state);
            call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), input, 400);
        }
        for (Object assignee : List.of("", " ", "\u2003", 3, true, "x".repeat(65))) {
            var input = save(f, "READY", null, 0, id());
            input.put("assigneeId", assignee);
            call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), input, 400);
        }
        for (Object expected : List.of(-1, 0.5, "0")) {
            var input = save(f, "READY", null, 0, id());
            input.put("expectedVersion", expected);
            call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), input, 400);
        }
        var input = save(f, "READY", null, 0, id());
        input.put("reason", "x".repeat(2001));
        call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), input, 400);
        input = save(f, "READY", null, 0, "contains space");
        call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), input, 400);
        for (Object pageSize : List.of(0, 51, 1.5, "2"))
            call(
                    HttpMethod.POST,
                    endpoint(f) + "/_query",
                    f.owner().token(),
                    Map.of("revisionId", f.revision(), "pageSize", pageSize),
                    400);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_candidates",
                f.owner().token(),
                Map.of("revisionId", f.revision(), "keyword", "x".repeat(101)),
                400);
        assertEquals(0, count("select count(*) as count from p_page_delivery"));
    }

    @Test
    void listFiltersBeforeCountAndUsesStableTieBreaksWithoutSynthesizingUnsetPages()
            throws Exception {
        Fixture f = fixture();
        save(f, PAGE, save(f, "READY", null, 0, id()));
        save(f, CHILD, save(f, "DONE", null, 0, id()));
        save(f, THIRD, save(f, "READY", null, 0, id()));
        sql("update p_page_delivery set updated_at=1000");
        JsonNode p0 = query(f, f.owner().token(), 0, 2, null);
        JsonNode p1 = query(f, f.owner().token(), 1, 2, null);
        assertEquals(3, p0.path("total").asInt());
        assertEquals(3, p1.path("total").asInt());
        var pages = new java.util.ArrayList<String>();
        p0.path("data").forEach(r -> pages.add(r.path("pageId").asText()));
        p1.path("data").forEach(r -> pages.add(r.path("pageId").asText()));
        assertEquals(List.of(PAGE, CHILD, THIRD).stream().sorted().toList(), pages);
        JsonNode ready = query(f, f.owner().token(), 0, 1, "READY");
        assertEquals(2, ready.path("total").asInt());
        assertEquals("READY", ready.path("data").get(0).path("state").asText());
        assertEquals(0, query(f, f.owner().token(), 10000, 50, null).path("data").size());
        assertEquals(0, query(f, f.owner().token(), 0, 20, "DRAFT").path("total").asInt());
    }

    @Test
    void recycleRestoreAndFailedPermanentCleanupPreserveAtomicRowsReceiptsThenRetry()
            throws Exception {
        Fixture f = fixture();
        var input = save(f, "DONE", null, 0, "lifecycle");
        save(f, PAGE, input);
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        call(HttpMethod.PUT, endpoint(f) + "/" + PAGE, f.owner().token(), input, 404);
        assertEquals(1, count("select count(*) as count from p_page_delivery"));
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                f.owner().token(),
                null);
        assertEquals(1, save(f, PAGE, input).path("configVersion").asLong());
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        database.sql("update p_version set deleted_time=:deleted where id=:id")
                .bind(
                        "deleted",
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 1000)
                .bind("id", f.version())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        sql(
                "create table f28_purge_blocker(delivery_id varchar(64) references p_page_delivery(id))");
        try {
            sql("insert into f28_purge_blocker select id from p_page_delivery");
            long receipts = count("select count(*) as count from p_creation_receipt");
            long cleanup = count("select count(*) as count from p_file_cleanup");
            assertThrows(
                    RuntimeException.class,
                    () -> retention.purgeVersion(f.version()).block(TIMEOUT));
            assertEquals(1, count("select count(*) as count from p_page_delivery"));
            assertEquals(1, count("select count(*) as count from p_version"));
            assertEquals(receipts, count("select count(*) as count from p_creation_receipt"));
            assertEquals(cleanup, count("select count(*) as count from p_file_cleanup"));
        } finally {
            sql("drop table f28_purge_blocker");
        }
        assertTrue(retention.purgeVersion(f.version()).block(TIMEOUT));
        assertFalse(retention.purgeVersion(f.version()).block(TIMEOUT));
        assertEquals(0, count("select count(*) as count from p_page_delivery"));
        assertEquals(0, count("select count(*) as count from p_creation_receipt"));
    }

    @Test
    void easyOrmCreatesTextSnapshotsDescriptionsAndBoundedQueryIndexes() {
        assertEquals(
                "固定页面人工交付状态",
                database.sql("select obj_description('p_page_delivery'::regclass) as description")
                        .map((r, m) -> r.get("description", String.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(
                2,
                count(
                        "select count(*) as count from information_schema.columns where table_name='p_page_delivery' and column_name in ('page_name','page_path') and data_type='text'"));
        assertEquals(
                3,
                count(
                        "select count(*) as count from pg_indexes where tablename='p_page_delivery' and indexname in ('idx_page_delivery_state','idx_page_delivery_revision','idx_page_delivery_version')"));
        assertEquals(
                "首次保存为一，每个新成功请求递增的CAS版本",
                database.sql(
                                "select col_description('p_page_delivery'::regclass,attnum) as description from pg_attribute where attrelid='p_page_delivery'::regclass and attname='config_version'")
                        .map((r, m) -> r.get("description", String.class))
                        .one()
                        .block(TIMEOUT));
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
            for (String path : List.of("index.html", "child.html", "other.html", LONG_PATH)) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(
                        ("<html><body>" + label + "<a href='child.html'>Child</a></body></html>")
                                .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("data/document.js"));
            zip.write(
                    ("var rootNodes=[{pageName:'Home',url:'index.html',children:[{pageName:'Child',url:'child.html'}]},"
                                    + "{pageName:'Other',url:'other.html'},{pageName:'"
                                    + LONG_NAME
                                    + "',url:'"
                                    + LONG_PATH
                                    + "'}];")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
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
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Delivery test"));
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
        String username = "delivery-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Delivery test",
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
        return "/prototype-hub/versions/" + f.version() + "/page-deliveries";
    }

    private Map<String, Object> save(
            Fixture f, String state, String assignee, long expected, String key) {
        var input = new LinkedHashMap<String, Object>();
        input.put("revisionId", f.revision());
        input.put("state", state);
        input.put("assigneeId", assignee);
        input.put("reason", "<b>人工理由 / manual reason</b>");
        input.put("expectedVersion", expected);
        input.put("clientRequestId", key);
        return input;
    }

    private JsonNode detail(Fixture f, String token, String page) {
        return result(
                HttpMethod.GET,
                endpoint(f) + "/" + page + "?revisionId=" + f.revision(),
                token,
                null);
    }

    private JsonNode save(Fixture f, String page, Map<String, Object> input) {
        return result(HttpMethod.PUT, endpoint(f) + "/" + page, f.owner().token(), input);
    }

    private JsonNode query(Fixture f, String token, int index, int size, String state) {
        var input = new LinkedHashMap<String, Object>();
        input.put("revisionId", f.revision());
        input.put("pageIndex", index);
        input.put("pageSize", size);
        if (state != null) input.put("state", state);
        return result(HttpMethod.POST, endpoint(f) + "/_query", token, input);
    }

    private JsonNode candidates(Fixture f, String token, String keyword, int index, int size) {
        var input = new LinkedHashMap<String, Object>();
        input.put("revisionId", f.revision());
        input.put("keyword", keyword);
        input.put("pageIndex", index);
        input.put("pageSize", size);
        return result(HttpMethod.POST, endpoint(f) + "/_candidates", token, input);
    }

    private void removeMember(Fixture f, String user) {
        database.sql("delete from p_project_member where project_id=:project and user_id=:user")
                .bind("project", f.project())
                .bind("user", user)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(TIMEOUT);
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
            return Files.createTempDirectory("prototype-page-delivery-");
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
