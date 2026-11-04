package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
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

/** 真实HTTP/PostgreSQL检验人工视口记录、成员权限、幂等CAS与回收生命周期，不访问外部地址。 */
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
class PrototypeHubViewportCheckIntegrationTest {
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
    void persistedPlainTextDefaultsAndCasReplayStayOnTheFixedRevision() throws Exception {
        Fixture f = fixture();
        var input = create("create-one");
        input.put("steps", "<b>Open page</b>");
        JsonNode row = result(HttpMethod.POST, endpoint(f), f.owner().token(), input);
        String check = row.path("id").asText();
        assertEquals(0, row.path("configVersion").asLong());
        assertEquals("untested", row.path("result").path("value").asText());
        assertEquals(100, row.path("viewportScale").asInt());
        assertEquals("", row.path("observed").asText());
        assertEquals("", row.path("evidence").asText());
        assertEquals(row, result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        assertEquals(1L, count("select count(*) as count from p_viewport_check"));
        assertEquals(
                "<b>Open page</b>",
                database.sql("select steps from p_viewport_check where id=:id")
                        .bind("id", check)
                        .map((r, m) -> r.get("steps", String.class))
                        .one()
                        .block(TIMEOUT));
        var changedKey = new LinkedHashMap<>(input);
        changedKey.put("title", "Changed input");
        call(HttpMethod.POST, endpoint(f), f.owner().token(), changedKey, 409);
        String next =
                upload(f.project(), f.version(), f.revision(), "R2")
                        .path("currentRevisionId")
                        .asText();
        assertNotEquals(next, f.revision());
        assertEquals(
                1, query(f, f.owner().token(), f.revision(), PAGE, 0, 20).path("total").asInt());
        assertEquals(0, query(f, f.owner().token(), next, PAGE, 0, 20).path("total").asInt());
        var update = new LinkedHashMap<>(input);
        update.put("clientRequestId", "update-one");
        update.put("expectedVersion", 0);
        update.put("result", "passed");
        update.put("observed", "The page remains usable");
        update.put("evidence", "Manual browser observation");
        for (Object expectedVersion : List.of(0.5, "0")) {
            var invalid = new LinkedHashMap<>(update);
            invalid.put("expectedVersion", expectedVersion);
            call(HttpMethod.PUT, endpoint(f) + "/" + check, f.owner().token(), invalid, 400);
            call(
                    HttpMethod.POST,
                    endpoint(f) + "/" + check + "/_delete",
                    f.owner().token(),
                    Map.of("expectedVersion", expectedVersion, "clientRequestId", id()),
                    400);
        }
        JsonNode updated =
                result(HttpMethod.PUT, endpoint(f) + "/" + check, f.owner().token(), update);
        assertEquals(1, updated.path("configVersion").asLong());
        assertEquals("passed", updated.path("result").path("value").asText());
        assertEquals(
                updated,
                result(HttpMethod.PUT, endpoint(f) + "/" + check, f.owner().token(), update));
        update.put("clientRequestId", "stale-update");
        assertEquals(
                "PROTOTYPE_HUB_VIEWPORT_CHECK_CONFLICT",
                call(HttpMethod.PUT, endpoint(f) + "/" + check, f.owner().token(), update, 409)
                        .path("code")
                        .asText());
        call(
                HttpMethod.POST,
                endpoint(f) + "/" + check + "/_delete",
                f.owner().token(),
                mutation(0, "stale-delete"),
                409);
        var deletion = mutation(1, "delete-one");
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + check + "/_delete",
                                f.owner().token(),
                                deletion)
                        .asBoolean());
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + check + "/_delete",
                                f.owner().token(),
                                deletion)
                        .asBoolean());
        call(HttpMethod.POST, endpoint(f), f.owner().token(), input, 409);
        assertEquals(0L, count("select count(*) as count from p_viewport_check"));
    }

    @Test
    void currentActionProjectRoleAndAccountAreRequiredBeforeReplay() throws Exception {
        Fixture f = fixture();
        Actor editor = actor(List.of("query", "save"));
        Actor viewer = actor(List.of("query", "save"));
        Actor outsider = actor(List.of("query", "save"));
        Actor noQuery = actor(List.of("save"));
        Actor noSave = actor(List.of("query"));
        for (Actor actor : List.of(editor, noQuery, noSave))
            member(f.project(), actor.id(), "editor");
        member(f.project(), viewer.id(), "viewer");
        var input = create("editor-create");
        JsonNode row = result(HttpMethod.POST, endpoint(f), editor.token(), input);
        assertEquals(1, query(f, viewer.token(), f.revision(), PAGE, 0, 20).path("total").asInt());
        for (String token : List.of(viewer.token(), noSave.token())) {
            call(HttpMethod.POST, endpoint(f), token, create(id()), 403);
            var update = new LinkedHashMap<>(input);
            update.put("expectedVersion", 0);
            call(HttpMethod.PUT, endpoint(f) + "/" + row.path("id").asText(), token, update, 403);
            call(
                    HttpMethod.POST,
                    endpoint(f) + "/" + row.path("id").asText() + "/_delete",
                    token,
                    mutation(0, id()),
                    403);
        }
        call(HttpMethod.POST, endpoint(f), outsider.token(), create(id()), 404);
        call(HttpMethod.POST, endpoint(f) + "/_query", outsider.token(), Map.of(), 404);
        call(HttpMethod.POST, endpoint(f) + "/_query", noQuery.token(), Map.of(), 403);
        call(HttpMethod.POST, endpoint(f) + "/_query", null, Map.of(), 401);
        result(HttpMethod.POST, endpoint(f), admin, create("admin-create"));
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
        assertEquals(2L, count("select count(*) as count from p_viewport_check"));
    }

    @Test
    void ordinaryFieldAndObservationValidationAndForeignScopeDoNotPersist() throws Exception {
        Fixture f = fixture();
        for (var invalid :
                List.of(
                        Map.entry("viewportWidth", 239),
                        Map.entry("viewportHeight", 3841),
                        Map.entry("viewportScale", 24),
                        Map.entry("viewportScale", 201),
                        Map.entry("category", "other"),
                        Map.entry("title", " "),
                        Map.entry("expected", ""),
                        Map.entry("steps", "x".repeat(2001)),
                        Map.entry("result", "other"),
                        Map.entry("clientRequestId", ""))) {
            var input = create(id());
            input.put(invalid.getKey(), invalid.getValue());
            call(HttpMethod.POST, endpoint(f), f.owner().token(), input, 400);
        }
        for (String result : List.of("passed", "failed")) {
            var input = create(id());
            input.put("result", result);
            input.put("observed", " ");
            call(HttpMethod.POST, endpoint(f), f.owner().token(), input, 400);
        }
        for (String field : List.of("viewportWidth", "viewportHeight", "viewportScale")) {
            for (Object number : List.of(390.5, "390")) {
                var input = create(id());
                input.put(field, number);
                assertEquals(
                        "HTTP_400",
                        call(HttpMethod.POST, endpoint(f), f.owner().token(), input, 400)
                                .path("code")
                                .asText());
            }
        }
        call(
                HttpMethod.POST,
                endpoint(f) + "/_query",
                f.owner().token(),
                Map.of("pageSize", 101),
                400);
        call(HttpMethod.POST, endpoint(f, id(), PAGE), f.owner().token(), create(id()), 404);
        call(
                HttpMethod.POST,
                endpoint(f, f.revision(), "0".repeat(64)),
                f.owner().token(),
                create(id()),
                404);
        var other = fixture();
        call(
                HttpMethod.POST,
                endpoint(f, other.revision(), PAGE),
                f.owner().token(),
                create(id()),
                404);
        assertEquals(0L, count("select count(*) as count from p_viewport_check"));
    }

    @Test
    void enumObjectsFollowTheHttpRequestLanguage() throws Exception {
        Fixture f = fixture();
        result(HttpMethod.POST, endpoint(f), f.owner().token(), create(id()));
        for (var language :
                Map.of("zh-CN", List.of("正常", "未测"), "en-US", List.of("Normal", "Untested"))
                        .entrySet()) {
            JsonNode row =
                    client.post()
                            .uri(endpoint(f) + "/_query")
                            .header("X-Access-Token", f.owner().token())
                            .header("Accept-Language", language.getKey())
                            .bodyValue(Map.of())
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(JsonNode.class)
                            .returnResult()
                            .getResponseBody()
                            .path("result")
                            .path("data")
                            .get(0);
            assertEquals("normal", row.path("category").path("value").asText());
            assertEquals(language.getValue().get(0), row.path("category").path("text").asText());
            assertEquals(language.getValue().get(1), row.path("result").path("text").asText());
            assertEquals(2, row.path("category").size());
        }
    }

    @Test
    void concurrentSameKeyCreatesOneRowAndCompetingUpdatesHaveOneCasWinner() throws Exception {
        Fixture f = fixture();
        var input = create("parallel-create");
        var a =
                CompletableFuture.supplyAsync(
                        () -> result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        var b =
                CompletableFuture.supplyAsync(
                        () -> result(HttpMethod.POST, endpoint(f), f.owner().token(), input));
        String check = a.get(30, TimeUnit.SECONDS).path("id").asText();
        assertEquals(check, b.get(30, TimeUnit.SECONDS).path("id").asText());
        assertEquals(1L, count("select count(*) as count from p_viewport_check"));
        var update1 = new LinkedHashMap<>(input);
        update1.put("expectedVersion", 0);
        update1.put("clientRequestId", "race-a");
        update1.put("title", "A");
        var update2 = new LinkedHashMap<>(update1);
        update2.put("clientRequestId", "race-b");
        update2.put("title", "B");
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + check,
                                        f.owner().token(),
                                        update1));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + check,
                                        f.owner().token(),
                                        update2));
        assertEquals(
                List.of(200, 409),
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
    }

    @Test
    void realPagingFiltersAndThirtyDayPurgePreserveRestoreThenRemoveMetadataReceiptsAndFiles()
            throws Exception {
        Fixture f = fixture();
        for (int i = 0; i < 3; i++) {
            var input = create(id());
            input.put("category", i == 0 ? "empty" : "normal");
            result(HttpMethod.POST, endpoint(f), f.owner().token(), input);
        }
        result(HttpMethod.POST, endpoint(f, f.revision(), CHILD), f.owner().token(), create(id()));
        var first = query(f, f.owner().token(), f.revision(), PAGE, 0, 2);
        var second = query(f, f.owner().token(), f.revision(), PAGE, 1, 2);
        assertEquals(3, first.path("total").asInt());
        assertEquals(2, first.path("data").size());
        assertEquals(3, second.path("total").asInt());
        assertEquals(1, second.path("data").size());
        assertNotEquals(
                first.path("data").get(0).path("id"), second.path("data").get(0).path("id"));
        assertEquals(0, query(f, f.owner().token(), f.revision(), PAGE, 99, 2).path("data").size());
        assertEquals(
                1,
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/_query",
                                f.owner().token(),
                                Map.of("category", "empty", "result", "untested"))
                        .path("total")
                        .asInt());
        assertEquals(
                1, query(f, f.owner().token(), f.revision(), CHILD, 0, 20).path("total").asInt());
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        call(HttpMethod.POST, endpoint(f) + "/_query", f.owner().token(), Map.of(), 404);
        assertEquals(4L, count("select count(*) as count from p_viewport_check"));
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                f.owner().token(),
                null);
        assertEquals(
                3, query(f, f.owner().token(), f.revision(), PAGE, 0, 20).path("total").asInt());
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
        assertTrue(retention.purgeVersion(f.version()).block(TIMEOUT));
        assertEquals(0L, count("select count(*) as count from p_viewport_check"));
        assertEquals(
                0L,
                database.sql(
                                "select count(*) as count from p_creation_receipt where resource_id=:id")
                        .bind("id", f.version())
                        .map((r, m) -> r.get("count", Long.class))
                        .one()
                        .block(TIMEOUT));
        cleanup.retryPending().block(TIMEOUT);
        try (var files = Files.walk(FILES.resolve("projects").resolve(f.project()))) {
            assertFalse(files.anyMatch(Files::isRegularFile));
        }
    }

    @Test
    void projectPermanentCleanupRemovesOrphanViewportMetadataAndEasyOrmAddsDescriptions()
            throws Exception {
        Fixture f = fixture();
        result(HttpMethod.POST, endpoint(f), f.owner().token(), create(id()));
        database.sql("delete from p_version where id=:id")
                .bind("id", f.version())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_project set deleted_time=:deleted where id=:id")
                .bind(
                        "deleted",
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 1000)
                .bind("id", f.project())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeProject(f.project()).block(TIMEOUT));
        assertEquals(0L, count("select count(*) as count from p_viewport_check"));
        assertEquals(
                "固定修订页面的人工视口自测记录",
                database.sql("select obj_description('p_viewport_check'::regclass) as description")
                        .map((r, m) -> r.get("description", String.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(
                "人工结论untested、passed或failed，不是自动测试结果",
                database.sql(
                                "select col_description('p_viewport_check'::regclass,attnum) as description from pg_attribute where attrelid='p_viewport_check'::regclass and attname='result'")
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
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Viewport check test"));
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
        String username = "viewport-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Viewport check test",
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
        return endpoint(f, f.revision(), PAGE);
    }

    private String endpoint(Fixture f, String revision, String page) {
        return "/prototype-hub/versions/"
                + f.version()
                + "/revisions/"
                + revision
                + "/pages/"
                + page
                + "/viewport-checks";
    }

    private Map<String, Object> create(String key) {
        var input = new LinkedHashMap<String, Object>();
        input.put("clientRequestId", key);
        input.put("viewportWidth", 390);
        input.put("viewportHeight", 844);
        input.put("category", "normal");
        input.put("title", "Manual narrow viewport");
        input.put("expected", "The page remains usable");
        return input;
    }

    private Map<String, Object> mutation(long expected, String key) {
        return Map.of("expectedVersion", expected, "clientRequestId", key);
    }

    private JsonNode query(
            Fixture f, String token, String revision, String page, int index, int size) {
        return result(
                HttpMethod.POST,
                endpoint(f, revision, page) + "/_query",
                token,
                Map.of("pageIndex", index, "pageSize", size));
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
            return Files.createTempDirectory("prototype-viewport-items-");
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
