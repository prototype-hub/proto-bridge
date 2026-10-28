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

/** 真实HTTP/PostgreSQL检验固定修订引用、成员权限、幂等CAS与回收生命周期，不访问外部地址。 */
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
class PrototypeHubWorkItemIntegrationTest {
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
    void persistedCrudAndReplayRemainOnTheSelectedRevisionAfterVersionReplacement()
            throws Exception {
        Fixture f = fixture();
        Map<String, Object> create =
                create(f, "https://example.com/tasks/42", "<b>plain note</b>", "create-one");
        JsonNode row = result(HttpMethod.POST, endpoint(f), f.owner().token(), create);
        String id = row.path("id").asText();
        assertEquals(0, row.path("configVersion").asLong());
        assertEquals(
                id,
                result(HttpMethod.POST, endpoint(f), f.owner().token(), create)
                        .path("id")
                        .asText());
        assertEquals(1L, count("select count(*) as count from p_work_item"));
        assertEquals(
                "<b>plain note</b>",
                database.sql("select note from p_work_item where id=:id")
                        .bind("id", id)
                        .map((r, m) -> r.get("note", String.class))
                        .one()
                        .block(TIMEOUT));
        Map<String, Object> changedSameKey = new LinkedHashMap<>(create);
        changedSameKey.put("note", "different input");
        call(HttpMethod.POST, endpoint(f), f.owner().token(), changedSameKey, 409);

        JsonNode second = upload(f.project(), f.version(), f.revision(), "R2");
        String r2 = second.path("currentRevisionId").asText();
        assertNotEquals(f.revision(), r2);
        assertEquals(
                1, query(f, f.owner().token(), f.revision(), PAGE, 0, 20).path("total").asInt());
        assertEquals(0, query(f, f.owner().token(), r2, PAGE, 0, 20).path("total").asInt());

        Map<String, Object> update = new LinkedHashMap<>(create);
        update.put("clientRequestId", "update-one");
        update.put("expectedVersion", 0);
        update.put("note", "Updated");
        JsonNode updated =
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update);
        assertEquals(1, updated.path("configVersion").asLong());
        assertEquals(
                1,
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update)
                        .path("configVersion")
                        .asLong());
        Map<String, Object> stale = new LinkedHashMap<>(update);
        stale.put("clientRequestId", "stale-update");
        call(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), stale, 409);
        Map<String, Object> delete = mutation(f, 1, "delete-one");
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + id + "/_delete",
                                f.owner().token(),
                                delete)
                        .asBoolean());
        assertTrue(
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + id + "/_delete",
                                f.owner().token(),
                                delete)
                        .asBoolean());
        call(HttpMethod.POST, endpoint(f), f.owner().token(), create, 409);
        assertEquals(
                0, query(f, f.owner().token(), f.revision(), PAGE, 0, 20).path("total").asInt());
    }

    @Test
    void actionPermissionsAndCurrentProjectMembershipIntersectForEveryOperation() throws Exception {
        Fixture f = fixture();
        Actor editor = actor(List.of("query", "save"));
        Actor viewer = actor(List.of("query", "save"));
        Actor outsider = actor(List.of("query", "save"));
        Actor noQuery = actor(List.of("save"));
        Actor noSave = actor(List.of("query"));
        member(f.project(), editor.id(), "editor");
        member(f.project(), viewer.id(), "viewer");
        member(f.project(), noQuery.id(), "editor");
        member(f.project(), noSave.id(), "editor");
        JsonNode row =
                result(
                        HttpMethod.POST,
                        endpoint(f),
                        editor.token(),
                        create(f, "https://example.com/1", "", "editor-create"));
        assertEquals(1, query(f, viewer.token(), f.revision(), PAGE, 0, 20).path("total").asInt());
        for (String token : List.of(viewer.token(), noSave.token()))
            call(
                    HttpMethod.POST,
                    endpoint(f),
                    token,
                    create(f, "https://example.com/2", "", id()),
                    403);
        call(
                HttpMethod.POST,
                endpoint(f),
                outsider.token(),
                create(f, "https://example.com/2", "", id()),
                404);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_query",
                outsider.token(),
                page(f.revision(), PAGE),
                404);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_query",
                noQuery.token(),
                page(f.revision(), PAGE),
                403);
        call(HttpMethod.POST, endpoint(f) + "/_query", null, page(f.revision(), PAGE), 401);
        call(
                HttpMethod.POST,
                endpoint(f) + "/" + row.path("id").asText() + "/_delete",
                viewer.token(),
                mutation(f, 0, id()),
                403);
        database.sql("delete from p_project_member where project_id=:project and user_id=:user")
                .bind("project", f.project())
                .bind("user", editor.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.POST,
                endpoint(f),
                editor.token(),
                create(f, "https://example.com/1", "", "editor-create"),
                404);
        assertEquals(1L, count("select count(*) as count from p_work_item"));
    }

    @Test
    void rejectsInvalidUrlsScopeAndPayloadWithoutPersistingAnyReference() throws Exception {
        Fixture f = fixture();
        for (String url :
                List.of(
                        "javascript:alert(1)",
                        "data:text/html,x",
                        "file:///tmp/example",
                        "//example.com/task",
                        "https://name:secret@example.com/task",
                        "https://example.com:99999/task",
                        "https://example.com/has space",
                        "https://example.com/task\nother"))
            call(HttpMethod.POST, endpoint(f), f.owner().token(), create(f, url, "", id()), 400);
        var invalid = create(f, "https://example.com/1", "x".repeat(2001), id());
        call(HttpMethod.POST, endpoint(f), f.owner().token(), invalid, 400);
        invalid = create(f, "https://example.com/1", "", "");
        call(HttpMethod.POST, endpoint(f), f.owner().token(), invalid, 400);
        var scope = new LinkedHashMap<>(create(f, "https://example.com/1", "", id()));
        scope.put("pageId", "0".repeat(64));
        call(HttpMethod.POST, endpoint(f), f.owner().token(), scope, 404);
        scope.put("pageId", PAGE);
        scope.put("revisionId", id());
        call(HttpMethod.POST, endpoint(f), f.owner().token(), scope, 404);
        var other = fixture();
        scope.put("revisionId", other.revision());
        call(HttpMethod.POST, endpoint(f), f.owner().token(), scope, 404);
        var tooLarge = new LinkedHashMap<>(page(f.revision(), PAGE));
        tooLarge.put("pageSize", 101);
        call(HttpMethod.POST, endpoint(f) + "/_query", f.owner().token(), tooLarge, 400);
        assertEquals(0L, count("select count(*) as count from p_work_item"));
    }

    @Test
    void concurrentExactCreateIsOneRowAndCompetingUpdatesHaveOneCasWinner() throws Exception {
        Fixture f = fixture();
        var create = create(f, "https://example.com/tasks/1", "", "parallel-create");
        var first =
                CompletableFuture.supplyAsync(
                        () -> result(HttpMethod.POST, endpoint(f), f.owner().token(), create));
        var second =
                CompletableFuture.supplyAsync(
                        () -> result(HttpMethod.POST, endpoint(f), f.owner().token(), create));
        String item = first.get(30, TimeUnit.SECONDS).path("id").asText();
        assertEquals(item, second.get(30, TimeUnit.SECONDS).path("id").asText());
        assertEquals(1L, count("select count(*) as count from p_work_item"));
        var update1 = new LinkedHashMap<>(create);
        update1.put("clientRequestId", "race-a");
        update1.put("expectedVersion", 0);
        update1.put("note", "A");
        var update2 = new LinkedHashMap<>(update1);
        update2.put("clientRequestId", "race-b");
        update2.put("note", "B");
        var a =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + item,
                                        f.owner().token(),
                                        update1));
        var b =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + item,
                                        f.owner().token(),
                                        update2));
        assertEquals(
                List.of(200, 409),
                List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        assertEquals(
                1,
                query(f, f.owner().token(), f.revision(), PAGE, 0, 20)
                        .path("data")
                        .get(0)
                        .path("configVersion")
                        .asLong());
    }

    @Test
    void pagingAndPageScopeUseRealTotalsAndMetadataReceiptsFollowThirtyDayPurge() throws Exception {
        Fixture f = fixture();
        for (int i = 0; i < 3; i++)
            result(
                    HttpMethod.POST,
                    endpoint(f),
                    f.owner().token(),
                    create(f, "https://example.com/tasks/" + i, "", id()));
        var child = new LinkedHashMap<>(create(f, "https://example.com/child", "", id()));
        child.put("pageId", CHILD);
        result(HttpMethod.POST, endpoint(f), f.owner().token(), child);
        JsonNode first = query(f, f.owner().token(), f.revision(), PAGE, 0, 2);
        JsonNode second = query(f, f.owner().token(), f.revision(), PAGE, 1, 2);
        assertEquals(3, first.path("total").asInt());
        assertEquals(2, first.path("data").size());
        assertEquals(3, second.path("total").asInt());
        assertEquals(1, second.path("data").size());
        assertNotEquals(
                first.path("data").get(0).path("id").asText(),
                second.path("data").get(0).path("id").asText());
        assertEquals(
                1, query(f, f.owner().token(), f.revision(), CHILD, 0, 20).path("total").asInt());
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        call(
                HttpMethod.POST,
                endpoint(f) + "/_query",
                f.owner().token(),
                page(f.revision(), PAGE),
                404);
        assertEquals(4L, count("select count(*) as count from p_work_item"));
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
        assertEquals(0L, count("select count(*) as count from p_work_item"));
        assertEquals(
                0L,
                database.sql(
                                "select count(*) as count from p_creation_receipt where resource_id=:id")
                        .bind("id", f.version())
                        .map((r, m) -> r.get("count", Long.class))
                        .one()
                        .block(TIMEOUT));
    }

    @Test
    void easyOrmCreatesTheAdditiveTableAndChineseColumnDescriptions() {
        assertEquals(
                "固定修订页面的人工工作项引用",
                database.sql("select obj_description('p_work_item'::regclass) as description")
                        .map((r, m) -> r.get("description", String.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(
                "手工填写的HTTP或HTTPS工作项地址，无账号信息",
                database.sql(
                                "select col_description('p_work_item'::regclass, attnum) as description from pg_attribute where attrelid='p_work_item'::regclass and attname='url'")
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
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Work item test"));
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
        String username = "work-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Work item test",
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
        return "/prototype-hub/versions/" + f.version() + "/work-items";
    }

    private Map<String, Object> page(String revision, String page) {
        return Map.of("revisionId", revision, "pageId", page);
    }

    private Map<String, Object> create(Fixture f, String url, String note, String key) {
        return Map.of(
                "revisionId",
                f.revision(),
                "pageId",
                PAGE,
                "url",
                url,
                "note",
                note,
                "clientRequestId",
                key);
    }

    private Map<String, Object> mutation(Fixture f, long expected, String key) {
        return Map.of(
                "revisionId",
                f.revision(),
                "pageId",
                PAGE,
                "expectedVersion",
                expected,
                "clientRequestId",
                key);
    }

    private JsonNode query(
            Fixture f, String token, String revision, String page, int index, int size) {
        var query = new LinkedHashMap<>(page(revision, page));
        query.put("pageIndex", index);
        query.put("pageSize", size);
        return result(HttpMethod.POST, endpoint(f) + "/_query", token, query);
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
            return Files.createTempDirectory("prototype-work-items-");
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
