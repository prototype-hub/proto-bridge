package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hswebframework.web.authorization.token.ParsedToken;
import org.hswebframework.web.authorization.token.SimpleParsedToken;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** 项目范围和内部预览凭证的真实 HTTP/PostgreSQL 边界，公开分享保持独立外部授权。 */
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
class PrototypeHubProjectAccessIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();
    private static final Map<String, List<String>> ALL_ACTIONS =
            Map.of(
                    "prototype-hub-project", List.of("query", "save", "delete"),
                    "prototype-hub-version", List.of("query", "save", "delete"),
                    "prototype-hub-share", List.of("query", "save", "delete"));

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
        properties.add("spring.r2dbc.pool.max-size", () -> 4);
        properties.add("spring.r2dbc.pool.initial-size", () -> 1);
        properties.add("spring.r2dbc.username", DATABASE::getUsername);
        properties.add("spring.r2dbc.password", DATABASE::getPassword);
        properties.add("proto.admin.password", () -> PASSWORD);
        properties.add("prototype-hub.storage.root", () -> FILES.toString());
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ReactiveUserService users;
    @Autowired PrototypeUploadService uploads;
    private String admin;

    private record Actor(String id, String token, String role) {}

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-project-access-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @BeforeEach
    void authenticateAndIsolate() throws IOException {
        PrototypeHubTestData.clear(database);
        SecureZipExtractor.deleteRecursively(FILES);
        Files.createDirectories(FILES);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
    }

    @AfterAll
    static void removeFiles() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    private JsonNode response(WebTestClient.ResponseSpec response, int status) {
        JsonNode result =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(result);
        return result;
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.bodyValue(body);
        return response(request.exchange(), status);
    }

    private JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    private String login(String username) {
        String token =
                result(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", username, "password", PASSWORD))
                        .path("token")
                        .asText();
        assertFalse(token.isBlank());
        return token;
    }

    private Actor actor(Map<String, List<String>> actions) {
        String key = UUID.randomUUID().toString().replace("-", "");
        String role = key;
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Access test"));
        for (var entry : actions.entrySet()) {
            grant(role, entry.getKey(), entry.getValue());
        }
        String username = "access-" + key;
        String id =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Access test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(id));
        return new Actor(id, login(username), role);
    }

    private void grant(String role, String permission, List<String> actions) {
        result(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        UUID.nameUUIDFromBytes(
                                        (role + ":" + permission).getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", ""),
                        "permission",
                        permission,
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        actions,
                        "state",
                        1));
    }

    private JsonNode project(Actor owner, String name) {
        return result(
                HttpMethod.POST, "/prototype-hub/projects", owner.token(), Map.of("name", name));
    }

    private void member(String project, Actor actor, String role) {
        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + actor.id(),
                admin,
                Map.of("role", role));
    }

    private JsonNode upload(
            String token, String project, String version, String text, String key, int status) {
        var body = new MultipartBodyBuilder();
        body.part("versionName", "Access fixture");
        body.part("file", new ByteArrayResource(archive(text))).filename("access-fixture.zip");
        var request =
                client.method(version == null ? HttpMethod.POST : HttpMethod.PUT)
                        .uri(
                                version == null
                                        ? "/prototype-hub/projects/" + project + "/versions"
                                        : "/prototype-hub/versions/" + version)
                        .header("X-Access-Token", token);
        if (key != null) request.header("Idempotency-Key", key);
        return response(
                        request.contentType(MediaType.MULTIPART_FORM_DATA)
                                .body(BodyInserters.fromMultipartData(body.build()))
                                .exchange(),
                        status)
                .path("result");
    }

    private static byte[] archive(String text) {
        try {
            var content = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(content)) {
                for (var entry :
                        Map.of(
                                        "index.html", "<html><body>" + text + "</body></html>",
                                        "nested/child.html",
                                                "<html><body>Nested " + text + "</body></html>",
                                        "assets/style.css", "body{color:navy}")
                                .entrySet()) {
                    var item = new ZipEntry(entry.getKey());
                    item.setTime(946684800000L);
                    zip.putNextEntry(item);
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return content.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private JsonNode preview(String token, String version) {
        return result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version + "/preview-session",
                token,
                null);
    }

    private String path(JsonNode preview, String suffix) {
        return preview.path("runtimeBaseUrl").asText().substring(4) + "/" + suffix;
    }

    private void runtime(JsonNode preview, String suffix, int status) {
        client.get().uri(path(preview, suffix)).exchange().expectStatus().isEqualTo(status);
    }

    private void sql(String sql) {
        database.sql(sql).fetch().rowsUpdated().block(TIMEOUT);
    }

    private String snapshot() {
        return database.sql(
                        "select"
                                + " md5(coalesce(jsonb_agg(jsonb_build_array(id,name,creator_id,create_time,modifier_id,modify_time)"
                                + " order by id)::text,'[]')) as hash from p_project")
                .map((row, metadata) -> row.get("hash", String.class))
                .one()
                .block(TIMEOUT);
    }

    @Test
    void projectRolesIntersectActionsAndAllAssociatedIdsResolveTheirPersistedProject() {
        Actor owner = actor(ALL_ACTIONS),
                outsider = actor(ALL_ACTIONS),
                viewer = actor(ALL_ACTIONS),
                editor = actor(ALL_ACTIONS);
        String first = project(owner, "Private first").path("id").asText();
        String second = project(outsider, "Private second").path("id").asText();
        String version =
                upload(owner.token(), first, null, "private first", null, 200).path("id").asText();
        String otherVersion =
                upload(outsider.token(), second, null, "private second", null, 200)
                        .path("id")
                        .asText();
        member(first, viewer, "viewer");
        member(first, editor, "editor");

        JsonNode visible = result(HttpMethod.GET, "/prototype-hub/projects", viewer.token(), null);
        assertEquals(1, visible.size());
        assertEquals(first, visible.get(0).path("id").asText());
        assertEquals("viewer", visible.get(0).path("role").asText());
        assertTrue(visible.get(0).path("capabilities").path("read").asBoolean());
        assertFalse(visible.get(0).path("capabilities").path("edit").asBoolean());
        assertFalse(visible.get(0).path("capabilities").path("manageMembers").asBoolean());
        JsonNode versions = result(HttpMethod.GET, "/prototype-hub/versions", viewer.token(), null);
        assertEquals(1, versions.size());
        assertEquals(version, versions.get(0).path("id").asText());
        for (String denied :
                List.of(
                        "/prototype-hub/projects/" + second,
                        "/prototype-hub/versions/" + otherVersion,
                        "/prototype-hub/versions/" + otherVersion + "/pages",
                        "/prototype-hub/versions/_usage?projectId=" + second,
                        "/prototype-hub/versions?projectId=" + second)) {
            call(HttpMethod.GET, denied, viewer.token(), null, 404);
        }
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/" + otherVersion + "/preview-session",
                viewer.token(),
                null,
                404);
        call(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + first,
                viewer.token(),
                Map.of("name", "Forbidden"),
                403);
        upload(viewer.token(), first, null, "viewer denied", null, 403);
        upload(viewer.token(), first, version, "viewer denied", null, 403);
        call(HttpMethod.DELETE, "/prototype-hub/versions/" + version, viewer.token(), null, 403);
        call(
                HttpMethod.GET,
                "/prototype-hub/versions/" + version + "/shares",
                viewer.token(),
                null,
                403);
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version + "/shares",
                viewer.token(),
                Map.of(),
                403);
        call(
                HttpMethod.GET,
                "/prototype-hub/projects/" + first + "/members",
                viewer.token(),
                null,
                403);
        call(HttpMethod.DELETE, "/prototype-hub/projects/" + first, editor.token(), null, 403);
        call(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + first + "/members/" + outsider.id(),
                editor.token(),
                Map.of("role", "viewer"),
                403);

        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + first,
                editor.token(),
                Map.of("name", "Edited"));
        upload(editor.token(), first, version, "editor allowed", null, 200);
        JsonNode share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version + "/shares",
                        editor.token(),
                        Map.of());
        call(
                HttpMethod.POST,
                "/prototype-hub/shares/" + share.path("id").asText() + "/verify-password",
                outsider.token(),
                Map.of("password", ""),
                404);
        call(
                HttpMethod.DELETE,
                "/prototype-hub/shares/" + share.path("id").asText(),
                outsider.token(),
                null,
                404);
        result(
                HttpMethod.DELETE,
                "/prototype-hub/shares/" + share.path("id").asText(),
                editor.token(),
                null);
        result(HttpMethod.DELETE, "/prototype-hub/versions/" + version, editor.token(), null);
        result(HttpMethod.DELETE, "/prototype-hub/projects/" + first, owner.token(), null);
    }

    @Test
    void membershipDoesNotGrantMissingGenericActionsAndUserTypeIsNotAdministratorAuthority() {
        Actor owner = actor(ALL_ACTIONS);
        Actor readOnly =
                actor(
                        Map.of(
                                "prototype-hub-project",
                                List.of("query"),
                                "prototype-hub-version",
                                List.of("query")));
        Actor noVersionRead = actor(Map.of("prototype-hub-project", List.of("query")));
        Actor fakeAdmin = actor(ALL_ACTIONS);
        String project = project(owner, "Action intersection").path("id").asText();
        String version =
                upload(owner.token(), project, null, "intersection", null, 200).path("id").asText();
        member(project, readOnly, "editor");
        member(project, noVersionRead, "viewer");
        result(HttpMethod.GET, "/prototype-hub/versions/" + version, readOnly.token(), null);
        upload(readOnly.token(), project, version, "generic save denied", null, 403);
        call(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project,
                readOnly.token(),
                Map.of("name", "denied"),
                403);
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version + "/preview-session",
                noVersionRead.token(),
                null,
                403);
        database.sql("update s_user set type='admin' where id=:id")
                .bind("id", fakeAdmin.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, "/prototype-hub/projects/" + project, fakeAdmin.token(), null, 404);
        call(
                HttpMethod.POST,
                "/prototype-hub/projects/_ownership-migration",
                fakeAdmin.token(),
                Map.of("apply", false),
                403);
    }

    @Test
    void disabledAdministratorDimensionCannotBypassProjectScopeEvenWithOtherGenericActions() {
        Actor owner = actor(ALL_ACTIONS), administrator = actor(ALL_ACTIONS);
        String project = project(owner, "Administrator exception").path("id").asText();
        result(HttpMethod.POST, "/role/ADMIN/users/_bind", admin, List.of(administrator.id()));
        // 先缓存带 ADMIN 的真实 Authentication；禁用角色后仍保留另一个角色的通用动作。
        result(HttpMethod.GET, "/prototype-hub/projects/" + project, administrator.token(), null);
        String original =
                database.sql("select properties from s_dimension where id='ADMIN'")
                        .map((row, metadata) -> row.get("properties", String.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(original);
        try {
            sql(
                    "update s_dimension set"
                            + " properties=jsonb_set(properties::jsonb,'{state}','\"disabled\"'::jsonb)::text"
                            + " where id='ADMIN'");
            call(
                    HttpMethod.GET,
                    "/prototype-hub/projects/" + project,
                    administrator.token(),
                    null,
                    404);
            call(
                    HttpMethod.POST,
                    "/prototype-hub/projects/_ownership-migration",
                    administrator.token(),
                    Map.of("apply", false),
                    403);
        } finally {
            database.sql("update s_dimension set properties=:properties where id='ADMIN'")
                    .bind("properties", original)
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        }
    }

    @Test
    void memberManagementIsOwnerBoundedAndCannotRewriteOwnerOrExposeUnboundedUserData() {
        Actor owner = actor(ALL_ACTIONS), viewer = actor(ALL_ACTIONS);
        String project = project(owner, "Members").path("id").asText();
        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + viewer.id(),
                owner.token(),
                Map.of("role", "viewer"));
        JsonNode members =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/projects/" + project + "/members",
                        owner.token(),
                        null);
        assertEquals(2, members.size());
        assertTrue(members.findValuesAsText("role").containsAll(List.of("owner", "viewer")));
        assertTrue(
                members.findValuesAsText("userId").containsAll(List.of(owner.id(), viewer.id())));
        JsonNode immutableUpdate =
                call(
                        HttpMethod.PUT,
                        "/prototype-hub/projects/" + project + "/members/" + owner.id(),
                        owner.token(),
                        Map.of("role", "viewer"),
                        400);
        JsonNode immutableDelete =
                call(
                        HttpMethod.DELETE,
                        "/prototype-hub/projects/" + project + "/members/" + owner.id(),
                        owner.token(),
                        null,
                        400);
        assertEquals("PROTOTYPE_HUB_OWNER_MEMBER_IMMUTABLE", immutableUpdate.path("code").asText());
        assertEquals("PROTOTYPE_HUB_OWNER_MEMBER_IMMUTABLE", immutableDelete.path("code").asText());
        assertEquals(
                owner.id(),
                result(HttpMethod.GET, "/prototype-hub/projects/" + project, owner.token(), null)
                        .path("ownerId")
                        .asText());
        call(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + viewer.id(),
                owner.token(),
                Map.of("role", "owner"),
                400);
        JsonNode candidates =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/projects/" + project + "/member-candidates?keyword=access",
                        owner.token(),
                        null);
        assertTrue(candidates.size() <= 20);
        for (JsonNode candidate : candidates) {
            assertNotEquals(owner.id(), candidate.path("userId").asText());
            assertTrue(candidate.hasNonNull("userId"));
            assertFalse(candidate.has("password"));
            assertFalse(candidate.has("salt"));
            assertFalse(candidate.has("email"));
            assertFalse(candidate.has("telephone"));
        }
        JsonNode literal =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/projects/" + project + "/member-candidates?keyword=%25_%27",
                        owner.token(),
                        null);
        assertTrue(literal.isEmpty());
        call(
                HttpMethod.GET,
                "/prototype-hub/projects/" + project + "/member-candidates?keyword=access",
                viewer.token(),
                null,
                403);
        result(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + project + "/members/" + viewer.id(),
                owner.token(),
                null);
        call(HttpMethod.GET, "/prototype-hub/projects/" + project, viewer.token(), null, 404);
    }

    @Test
    void shortPreviewCredentialsAreRevokedByMembershipChangesWithoutRevokingPublicShares() {
        Actor owner = actor(ALL_ACTIONS),
                viewer = actor(ALL_ACTIONS),
                outsider = actor(ALL_ACTIONS);
        String project = project(owner, "Preview revoke").path("id").asText();
        JsonNode version = upload(owner.token(), project, null, "preview revoke", null, 200);
        String id = version.path("id").asText();
        assertTrue(version.path("runtimeBaseUrl").isNull());
        assertTrue(version.path("previewUrl").isNull());
        member(project, viewer, "viewer");
        long before = System.currentTimeMillis();
        JsonNode credential = preview(viewer.token(), id);
        assertTrue(credential.path("expiresAt").asLong() > before);
        assertTrue(
                credential.path("expiresAt").asLong()
                        <= System.currentTimeMillis() + Duration.ofMinutes(5).toMillis());
        String capability =
                credential
                        .path("runtimeBaseUrl")
                        .asText()
                        .substring(credential.path("runtimeBaseUrl").asText().lastIndexOf('/') + 1);
        String digest;
        try {
            digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(capability.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        String persistedDigest =
                database.sql(
                                "select id from p_preview_session where user_id=:user and"
                                        + " version_id=:version")
                        .bind("user", viewer.id())
                        .bind("version", id)
                        .map((row, metadata) -> row.get("id", String.class))
                        .one()
                        .block(TIMEOUT);
        assertEquals(digest, persistedDigest);
        assertNotEquals(capability, persistedDigest);
        runtime(credential, "index.html", 200);
        runtime(credential, "nested/child.html", 200);
        runtime(credential, "assets/style.css", 200);
        String legacy =
                database.sql("select preview_key from p_version where id=:id")
                        .bind("id", id)
                        .map((row, metadata) -> row.get("preview_key", String.class))
                        .one()
                        .block(TIMEOUT);
        call(
                HttpMethod.GET,
                "/prototype-hub/runtime/" + id + "/" + legacy + "/index.html",
                null,
                null,
                403);
        JsonNode share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + id + "/shares",
                        owner.token(),
                        Map.of());
        String publicInfo = "/prototype-hub/public/shares/" + share.path("token").asText();
        JsonNode external = result(HttpMethod.GET, publicInfo, null, null);
        client.get()
                .uri(external.path("previewUrl").asText().substring(4))
                .exchange()
                .expectStatus()
                .isOk();
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/" + id + "/preview-session",
                outsider.token(),
                null,
                404);
        result(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + project + "/members/" + viewer.id(),
                owner.token(),
                null);
        runtime(credential, "index.html", 403);
        member(project, viewer, "viewer");
        runtime(credential, "index.html", 403);
        runtime(preview(viewer.token(), id), "index.html", 200);
        client.get()
                .uri(external.path("previewUrl").asText().substring(4))
                .exchange()
                .expectStatus()
                .isOk();
        result(
                HttpMethod.DELETE,
                "/prototype-hub/shares/" + share.path("id").asText(),
                owner.token(),
                null);
        client.get()
                .uri(external.path("previewUrl").asText().substring(4))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void previewPinsRevisionAndStopsAfterDisableOrGenericActionRevocationAndSessionsAreBounded() {
        Actor owner = actor(ALL_ACTIONS), viewer = actor(ALL_ACTIONS);
        String project = project(owner, "Preview lifecycle").path("id").asText();
        String version =
                upload(owner.token(), project, null, "before replacement", null, 200)
                        .path("id")
                        .asText();
        member(project, viewer, "viewer");
        JsonNode old = preview(viewer.token(), version);
        upload(owner.token(), project, version, "after replacement", null, 200);
        runtime(old, "index.html", 200);
        JsonNode updated = preview(viewer.token(), version);
        assertNotEquals(old.path("runtimeBaseUrl"), updated.path("runtimeBaseUrl"));
        runtime(updated, "index.html", 200);
        grant(viewer.role(), "prototype-hub-version", List.of("save"));
        runtime(updated, "index.html", 403);
        grant(viewer.role(), "prototype-hub-version", List.of("query", "save", "delete"));
        JsonNode reauthorized = preview(viewer.token(), version);
        users.changeState(Mono.just(viewer.id()), (byte) 0).block(TIMEOUT);
        runtime(reauthorized, "index.html", 403);

        JsonNode first = preview(owner.token(), version);
        for (int i = 0; i < 8; i++) runtime(preview(owner.token(), version), "index.html", 200);
        runtime(first, "index.html", 403);
        Long count =
                database.sql(
                                "select count(*) as count from p_preview_session where"
                                        + " user_id=:user and version_id=:version")
                        .bind("user", owner.id())
                        .bind("version", version)
                        .map((row, metadata) -> row.get("count", Long.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(count);
        assertTrue(count <= 8);
    }

    @Test
    void previewExpiryAndVersionBindingCannotBeBypassedByReusingTheTokenInAnotherPath() {
        Actor owner = actor(ALL_ACTIONS);
        String project = project(owner, "Preview expiry").path("id").asText();
        String first = upload(owner.token(), project, null, "first", null, 200).path("id").asText();
        String second =
                upload(owner.token(), project, null, "second", null, 200).path("id").asText();
        JsonNode granted = preview(owner.token(), first);
        runtime(granted, "index.html", 200);
        String wrongVersion =
                path(granted, "index.html").replace("/" + first + "/", "/" + second + "/");
        client.get().uri(wrongVersion).exchange().expectStatus().isForbidden();
        database.sql(
                        "update p_preview_session set expires_at=0 where user_id=:user and"
                                + " version_id=:version")
                .bind("user", owner.id())
                .bind("version", first)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        runtime(granted, "index.html", 403);
        runtime(preview(owner.token(), first), "index.html", 200);
    }

    @Test
    void ownershipMigrationIsExplicitIdempotentAndMissingCreatorsRequireAdministratorClaim() {
        Actor creator = actor(ALL_ACTIONS), replacement = actor(ALL_ACTIONS);
        String valid = project(creator, "Valid legacy creator").path("id").asText();
        String orphan = project(creator, "Missing legacy creator").path("id").asText();
        database.sql(
                        "update p_project set owner_id=null,access_epoch=null where id in"
                                + " (:valid,:orphan)")
                .bind("valid", valid)
                .bind("orphan", orphan)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_project set creator_id='missing-historical-user' where id=:id")
                .bind("id", orphan)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        String identityBefore = snapshot();
        JsonNode dry =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/projects/_ownership-migration",
                        admin,
                        Map.of("apply", false));
        assertEquals(2, dry.path("scanned").asInt());
        assertEquals(1, dry.path("assigned").asInt());
        assertEquals(1, dry.path("pending").asInt());
        assertEquals(
                0L,
                database.sql("select count(*) as count from p_project where owner_id is not null")
                        .map((row, metadata) -> row.get("count", Long.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(identityBefore, snapshot());
        call(HttpMethod.GET, "/prototype-hub/projects/" + valid, creator.token(), null, 404);
        call(
                HttpMethod.POST,
                "/prototype-hub/projects/_ownership-migration",
                creator.token(),
                Map.of("apply", true),
                403);
        JsonNode applied =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/projects/_ownership-migration",
                        admin,
                        Map.of("apply", true));
        assertEquals(1, applied.path("assigned").asInt());
        JsonNode assigned =
                result(HttpMethod.GET, "/prototype-hub/projects/" + valid, creator.token(), null);
        assertEquals(creator.id(), assigned.path("ownerId").asText());
        assertEquals("owner", assigned.path("role").asText());
        assertEquals(identityBefore, snapshot());
        JsonNode pending = result(HttpMethod.GET, "/prototype-hub/projects/" + orphan, admin, null);
        assertTrue(pending.path("claimRequired").asBoolean());
        String authorizationAfterApply = authorizationSnapshot();
        assertEquals(
                0L, count("select count(*) as count from p_project where access_epoch is null"));
        JsonNode rerun =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/projects/_ownership-migration",
                        admin,
                        Map.of("apply", true));
        assertEquals(0, rerun.path("assigned").asInt());
        assertEquals(1, rerun.path("pending").asInt());
        assertEquals(1, rerun.path("unchanged").asInt());
        assertEquals(authorizationAfterApply, authorizationSnapshot());
        call(
                HttpMethod.POST,
                "/prototype-hub/projects/" + orphan + "/claim",
                creator.token(),
                Map.of("userId", replacement.id()),
                403);
        result(
                HttpMethod.POST,
                "/prototype-hub/projects/" + orphan + "/claim",
                admin,
                Map.of("userId", replacement.id()));
        JsonNode claimed =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/projects/" + orphan,
                        replacement.token(),
                        null);
        assertEquals(replacement.id(), claimed.path("ownerId").asText());
        assertFalse(claimed.path("claimRequired").asBoolean());
        call(
                HttpMethod.POST,
                "/prototype-hub/projects/" + valid + "/claim",
                admin,
                Map.of("userId", replacement.id()),
                400);
        assertEquals(identityBefore, snapshot());
    }

    @Test
    void oldCreationReceiptsCannotReplayResourcesAfterOwnershipOrMembershipIsRevoked() {
        Actor original = actor(ALL_ACTIONS), replacement = actor(ALL_ACTIONS);
        String key = UUID.randomUUID().toString();
        JsonNode created =
                response(
                                client.post()
                                        .uri("/prototype-hub/projects")
                                        .header("X-Access-Token", original.token())
                                        .header("Idempotency-Key", key)
                                        .bodyValue(Map.of("name", "Receipt project"))
                                        .exchange(),
                                200)
                        .path("result");
        String project = created.path("id").asText();
        String versionKey = UUID.randomUUID().toString();
        String version =
                upload(original.token(), project, null, "receipt", versionKey, 200)
                        .path("id")
                        .asText();
        users.changeState(Mono.just(original.id()), (byte) 0).block(TIMEOUT);
        result(
                HttpMethod.POST,
                "/prototype-hub/projects/" + project + "/claim",
                admin,
                Map.of("userId", replacement.id()));
        users.changeState(Mono.just(original.id()), (byte) 1).block(TIMEOUT);
        String username = users.findById(original.id()).block(TIMEOUT).getUsername();
        String active = login(username);
        response(
                client.post()
                        .uri("/prototype-hub/projects")
                        .header("X-Access-Token", active)
                        .header("Idempotency-Key", key)
                        .bodyValue(Map.of("name", "Receipt project"))
                        .exchange(),
                404);
        upload(active, project, null, "receipt", versionKey, 404);
        result(HttpMethod.GET, "/prototype-hub/versions/" + version, replacement.token(), null);
        member(project, original, "editor");
        String memberKey = UUID.randomUUID().toString();
        upload(active, project, null, "member receipt", memberKey, 200);
        result(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + project + "/members/" + original.id(),
                replacement.token(),
                null);
        upload(active, project, null, "member receipt", memberKey, 404);
    }

    @Test
    void persistedShareCannotCrossItsVersionProjectRelationship() {
        Actor first = actor(ALL_ACTIONS), second = actor(ALL_ACTIONS);
        String project = project(first, "Share original").path("id").asText();
        String other = project(second, "Share other").path("id").asText();
        String version =
                upload(first.token(), project, null, "first-only", null, 200).path("id").asText();
        JsonNode share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version + "/shares",
                        first.token(),
                        Map.of());
        JsonNode external =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/public/shares/" + share.path("token").asText(),
                        null,
                        null);
        database.sql("update p_share set project_id=:project where id=:share")
                .bind("project", other)
                .bind("share", share.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.DELETE,
                "/prototype-hub/shares/" + share.path("id").asText(),
                second.token(),
                null,
                404);
        call(
                HttpMethod.POST,
                "/prototype-hub/shares/" + share.path("id").asText() + "/verify-password",
                second.token(),
                Map.of("password", ""),
                404);
        call(
                HttpMethod.GET,
                "/prototype-hub/public/shares/" + share.path("token").asText(),
                null,
                null,
                404);
        client.get()
                .uri(external.path("previewUrl").asText().substring(4))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private long count(String sql) {
        Long value =
                database.sql(sql)
                        .map((row, metadata) -> row.get("count", Long.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(value);
        return value;
    }

    private String authorizationSnapshot() {
        return database.sql(
                        "select md5(coalesce(jsonb_agg(jsonb_build_array(id,owner_id,access_epoch)"
                                + " order by id)::text,'[]')) as hash from p_project")
                .map((row, metadata) -> row.get("hash", String.class))
                .one()
                .block(TIMEOUT);
    }

    @Test
    void deletingAndRecreatingTheSameUsernameCannotInheritAuthorizationOrLegacyBackfill() {
        Actor deleted = actor(ALL_ACTIONS), other = actor(ALL_ACTIONS);
        String username = users.findById(deleted.id()).block(TIMEOUT).getUsername();
        String owned = project(deleted, "Deleted owner").path("id").asText();
        String shared = project(other, "Deleted member").path("id").asText();
        String legacy = project(deleted, "Unclassified historical creator").path("id").asText();
        database.sql("update p_project set owner_id=null,access_epoch=null where id=:id")
                .bind("id", legacy)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        String ownedVersion =
                upload(deleted.token(), owned, null, "owned", null, 200).path("id").asText();
        String memberVersion =
                upload(other.token(), shared, null, "member", null, 200).path("id").asText();
        member(shared, deleted, "viewer");
        JsonNode ownerCredential = preview(deleted.token(), ownedVersion);
        JsonNode memberCredential = preview(deleted.token(), memberVersion);
        String before = authorizationSnapshot();
        result(HttpMethod.DELETE, "/user/" + deleted.id(), admin, null);
        assertEquals(
                0L,
                count(
                        "select count(*) as count from p_project_member where user_id='"
                                + deleted.id()
                                + "'"));
        assertEquals(
                0L,
                count(
                        "select count(*) as count from p_preview_session where user_id='"
                                + deleted.id()
                                + "'"));
        assertNotEquals(before, authorizationSnapshot());
        assertEquals(
                1L,
                count(
                        "select count(*) as count from p_project where id='"
                                + legacy
                                + "' and owner_id is null and access_epoch is not null"));
        JsonNode recreated =
                result(
                        HttpMethod.POST,
                        "/user/detail/_create",
                        admin,
                        Map.of(
                                "name",
                                "Recreated identity",
                                "username",
                                username,
                                "password",
                                PASSWORD));
        assertEquals(deleted.id(), recreated.path("id").asText());
        result(
                HttpMethod.POST,
                "/role/" + deleted.role() + "/users/_bind",
                admin,
                List.of(deleted.id()));
        String token = login(username);
        call(HttpMethod.GET, "/prototype-hub/projects/" + owned, token, null, 404);
        call(HttpMethod.GET, "/prototype-hub/projects/" + shared, token, null, 404);
        runtime(ownerCredential, "index.html", 403);
        runtime(memberCredential, "index.html", 403);
        String beforeMigration = authorizationSnapshot();
        JsonNode migration =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/projects/_ownership-migration",
                        admin,
                        Map.of("apply", true));
        assertEquals(0, migration.path("assigned").asInt());
        assertEquals(2, migration.path("pending").asInt());
        assertEquals(beforeMigration, authorizationSnapshot());
        call(HttpMethod.GET, "/prototype-hub/projects/" + owned, token, null, 404);
        call(HttpMethod.GET, "/prototype-hub/projects/" + legacy, token, null, 404);
    }

    @Test
    void replacementRechecksRevokedMembershipBeforeCommittingAndCleansTheCandidate()
            throws IOException {
        Actor owner = actor(ALL_ACTIONS), editor = actor(ALL_ACTIONS);
        String project = project(owner, "Upload revocation").path("id").asText();
        String version =
                upload(owner.token(), project, null, "original content", null, 200)
                        .path("id")
                        .asText();
        member(project, editor, "editor");
        JsonNode oldPreview = preview(owner.token(), version);
        Set<Path> before;
        try (var files = Files.walk(FILES)) {
            before = files.filter(Files::isRegularFile).collect(Collectors.toSet());
        }
        long receipts = count("select count(*) as count from p_creation_receipt");
        Sinks.One<Void> entered = Sinks.one(), release = Sinks.one();
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("prototype.zip");
        // 文件接收订阅证明首次权限检查已通过；此时没有持有最终提交的项目锁。
        when(file.content())
                .thenAnswer(
                        ignored ->
                                Flux.defer(
                                        () -> {
                                            entered.tryEmitEmpty();
                                            return release.asMono()
                                                    .thenMany(
                                                            Flux.just(
                                                                    new DefaultDataBufferFactory()
                                                                            .wrap(
                                                                                    archive(
                                                                                            "revoked"
                                                                                                    + " replacement"))));
                                        }));
        var operation =
                uploads.update(version, "Unauthorized replacement", null, file)
                        .contextWrite(
                                context ->
                                        context.put(
                                                ParsedToken.class,
                                                SimpleParsedToken.of(
                                                        "default",
                                                        editor.token(),
                                                        (headers, token) ->
                                                                headers.set(
                                                                        "X-Access-Token", token))))
                        .toFuture();
        try {
            entered.asMono().block(TIMEOUT);
            result(
                    HttpMethod.DELETE,
                    "/prototype-hub/projects/" + project + "/members/" + editor.id(),
                    owner.token(),
                    null);
        } finally {
            release.tryEmitEmpty();
        }
        CompletionException failure = assertThrows(CompletionException.class, operation::join);
        assertTrue(failure.getCause() instanceof PrototypeHubException);
        assertEquals(404, ((PrototypeHubException) failure.getCause()).getStatus());
        assertEquals(1L, count("select count(*) as count from p_version"));
        assertEquals(receipts, count("select count(*) as count from p_creation_receipt"));
        try (var files = Files.walk(FILES)) {
            assertEquals(before, files.filter(Files::isRegularFile).collect(Collectors.toSet()));
        }
        runtime(oldPreview, "index.html", 403); // 成员变更轮换 epoch，旧 owner session 同样退休。
        JsonNode fresh = preview(owner.token(), version);
        client.get()
                .uri(path(fresh, "index.html"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        text -> {
                            assertTrue(text.contains("original content"));
                            assertFalse(text.contains("revoked replacement"));
                        });
    }

    @Test
    void operationalSqlIncludesAuthorizationButStillRunsBeforeTheMemberSchemaExists()
            throws Exception {
        Actor owner = actor(ALL_ACTIONS), viewer = actor(ALL_ACTIONS);
        String project = project(owner, "SQL snapshots").path("id").asText();
        String version =
                upload(owner.token(), project, null, "snapshot", null, 200).path("id").asText();
        member(project, viewer, "viewer");
        preview(viewer.token(), version);
        for (String name :
                List.of("prototype-hub-table-names.sql", "preflight-db.sql", "snapshot-db.sql")) {
            DATABASE.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "tools", name).toAbsolutePath()),
                    "/tmp/" + name);
        }
        var preflight =
                DATABASE.execInContainer(
                        "psql",
                        "-X",
                        "-qAt",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-U",
                        DATABASE.getUsername(),
                        "-d",
                        DATABASE.getDatabaseName(),
                        "-f",
                        "/tmp/preflight-db.sql");
        assertEquals(0, preflight.getExitCode(), preflight.getStderr());
        var current =
                DATABASE.execInContainer(
                        "psql",
                        "-X",
                        "-qAt",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-U",
                        DATABASE.getUsername(),
                        "-d",
                        DATABASE.getDatabaseName(),
                        "-f",
                        "/tmp/snapshot-db.sql");
        assertEquals(0, current.getExitCode(), current.getStderr());
        assertTrue(current.getStdout().contains("project_authorization|1|"));
        assertTrue(current.getStdout().contains("project_members|1|"));
        assertTrue(current.getStdout().contains("short_preview_sessions|1|"));
        sql("create schema legacy_pre_member");
        sql("create table legacy_pre_member.protohub_project(id varchar(64))");
        sql(
                "create table legacy_pre_member.protohub_version(id varchar(64),project_id"
                        + " varchar(64),status varchar(64))");
        sql(
                "create table legacy_pre_member.protohub_share(id varchar(64),project_id"
                        + " varchar(64),version_id varchar(64),token varchar(64))");
        sql("create table legacy_pre_member.protohub_file_cleanup(id varchar(64))");
        for (String name : List.of("preflight-db.sql", "snapshot-db.sql")) {
            var old =
                    DATABASE.execInContainer(
                            "env",
                            "PGOPTIONS=-c search_path=legacy_pre_member",
                            "psql",
                            "-X",
                            "-qAt",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-v",
                            "prototype_hub_table_prefix=protohub_",
                            "-U",
                            DATABASE.getUsername(),
                            "-d",
                            DATABASE.getDatabaseName(),
                            "-f",
                            "/tmp/" + name);
            assertEquals(0, old.getExitCode(), old.getStderr());
        }
    }

    @Test
    void deletionAndConcurrentMemberAssignmentCannotLeaveBindingsForARecreatedIdentity() {
        Actor target = actor(ALL_ACTIONS), owner = actor(ALL_ACTIONS);
        String username = users.findById(target.id()).block(TIMEOUT).getUsername();
        String owned = project(target, "Deletion lock anchor").path("id").asText();
        String other = project(owner, "Concurrent member target").path("id").asText();
        Connection anchor =
                Mono.from(
                                ConnectionFactories.get(
                                                "r2dbc:postgresql://"
                                                        + DATABASE.getUsername()
                                                        + ":"
                                                        + DATABASE.getPassword()
                                                        + "@"
                                                        + DATABASE.getHost()
                                                        + ":"
                                                        + DATABASE.getMappedPort(5432)
                                                        + "/"
                                                        + DATABASE.getDatabaseName())
                                        .create())
                        .block(TIMEOUT);
        assertNotNull(anchor);
        java.util.concurrent.CompletableFuture<JsonNode> deleting;
        java.util.concurrent.CompletableFuture<JsonNode> assigning;
        try {
            Mono.from(anchor.beginTransaction()).block(TIMEOUT);
            Flux.from(
                            anchor.createStatement(
                                            "select id from p_project where id=$1 for update")
                                    .bind(0, owned)
                                    .execute())
                    .flatMap(row -> row.map((data, metadata) -> data.get("id", String.class)))
                    .then()
                    .block(TIMEOUT);
            deleting =
                    Mono.fromCallable(
                                    () ->
                                            result(
                                                    HttpMethod.DELETE,
                                                    "/user/" + target.id(),
                                                    admin,
                                                    null))
                            .subscribeOn(Schedulers.boundedElastic())
                            .toFuture();
            // 删除已持有用户行并进入官方事务内清理，等待我们占有的项目 A。
            Flux.defer(
                            () ->
                                    Flux.from(
                                                    anchor.createStatement(
                                                                    "select exists(select 1 from"
                                                                            + " pg_stat_activity a"
                                                                            + " where"
                                                                            + " a.pid<>pg_backend_pid()"
                                                                            + " and a.wait_event_type='Lock'"
                                                                            + " and pg_backend_pid()=any(pg_blocking_pids(a.pid)))"
                                                                            + " as blocked")
                                                            .execute())
                                            .flatMap(
                                                    row ->
                                                            row.map(
                                                                    (data, metadata) ->
                                                                            data.get(
                                                                                    "blocked",
                                                                                    Boolean
                                                                                            .class))))
                    .repeat()
                    .filter(Boolean.TRUE::equals)
                    .next()
                    .timeout(TIMEOUT)
                    .block(TIMEOUT);
            assigning =
                    Mono.fromCallable(
                                    () ->
                                            call(
                                                    HttpMethod.PUT,
                                                    "/prototype-hub/projects/"
                                                            + other
                                                            + "/members/"
                                                            + target.id(),
                                                    owner.token(),
                                                    Map.of("role", "viewer"),
                                                    403))
                            .subscribeOn(Schedulers.boundedElastic())
                            .toFuture();
            // 等待第二个请求实际排队到用户行，不能仅靠调度启动就放行。
            Flux.defer(
                            () ->
                                    Flux.from(
                                                    anchor.createStatement(
                                                                    "select exists(select 1 from"
                                                                            + " pg_stat_activity a join"
                                                                            + " pg_stat_activity b on"
                                                                            + " b.pid=any(pg_blocking_pids(a.pid))"
                                                                            + " where"
                                                                            + " a.pid<>pg_backend_pid()"
                                                                            + " and a.wait_event_type='Lock'"
                                                                            + " and pg_backend_pid()=any(pg_blocking_pids(b.pid)))"
                                                                            + " as blocked")
                                                            .execute())
                                            .flatMap(
                                                    row ->
                                                            row.map(
                                                                    (data, metadata) ->
                                                                            data.get(
                                                                                    "blocked",
                                                                                    Boolean
                                                                                            .class))))
                    .repeat()
                    .filter(Boolean.TRUE::equals)
                    .next()
                    .timeout(TIMEOUT)
                    .block(TIMEOUT);
            Mono.from(anchor.commitTransaction()).block(TIMEOUT);
            assertNotNull(deleting.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join());
            assertNotNull(assigning.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join());
        } finally {
            Mono.from(anchor.close()).block(TIMEOUT);
        }
        assertEquals(
                0L,
                count(
                        "select count(*) as count from p_project_member where user_id='"
                                + target.id()
                                + "'"));
        JsonNode recreated =
                result(
                        HttpMethod.POST,
                        "/user/detail/_create",
                        admin,
                        Map.of(
                                "name",
                                "Recreated after race",
                                "username",
                                username,
                                "password",
                                PASSWORD));
        assertEquals(target.id(), recreated.path("id").asText());
        result(
                HttpMethod.POST,
                "/role/" + target.role() + "/users/_bind",
                admin,
                List.of(target.id()));
        String token = login(username);
        call(HttpMethod.GET, "/prototype-hub/projects/" + owned, token, null, 404);
        call(HttpMethod.GET, "/prototype-hub/projects/" + other, token, null, 404);
    }

    @Test
    void previewGarbageCollectionCannotTouchAnotherProjectsExpiredCapabilities() {
        Actor owner = actor(ALL_ACTIONS);
        String first = project(owner, "GC first").path("id").asText();
        String second = project(owner, "GC second").path("id").asText();
        String firstVersion =
                upload(owner.token(), first, null, "first", null, 200).path("id").asText();
        String secondVersion =
                upload(owner.token(), second, null, "second", null, 200).path("id").asText();
        preview(owner.token(), firstVersion);
        preview(owner.token(), secondVersion);
        sql("update p_preview_session set expires_at=0");
        runtime(preview(owner.token(), secondVersion), "index.html", 200);
        assertEquals(
                1L,
                count(
                        "select count(*) as count from p_preview_session where project_id='"
                                + first
                                + "' and expires_at=0"));
        assertEquals(
                0L,
                count(
                        "select count(*) as count from p_preview_session where project_id='"
                                + second
                                + "' and expires_at=0"));
    }
}
