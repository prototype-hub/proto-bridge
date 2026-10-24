package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.hswebframework.web.authorization.token.ParsedToken;
import org.hswebframework.web.authorization.token.SimpleParsedToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class SystemIntegrationTest {
    static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        registry.add("spring.r2dbc.username", DATABASE::getUsername);
        registry.add("spring.r2dbc.password", DATABASE::getPassword);
        registry.add("proto.admin.password", () -> ADMIN_PASSWORD);
        registry.add("FRONT_TITLE", () -> "Configured Proto Bridge");
        registry.add("FRONT_LOGO", () -> "/branding/logo.svg");
        registry.add("FRONT_ICO", () -> "/branding/icon.svg");
        registry.add("FRONT_BACKGROUND", () -> "/branding/login.svg");
        registry.add("FRONT_HEADER_THEME", () -> "dark");
        registry.add("FRONT_SHOW_RECORD_NUMBER", () -> "true");
        registry.add("FRONT_RECORD_NUMBER", () -> "ICP-test");
        registry.add("FRONT_POLICE_RECORD_NUMBER", () -> "police-test");
        registry.add("FRONT_COPYRIGHT", () -> "Proto Bridge Test");
        // Unknown private fields must never appear in the public presentation response.
        registry.add("proto.front.password", () -> ADMIN_PASSWORD);
        registry.add("prototype-hub.storage.root", () -> FILES.toString());
        registry.add("logging.file.name", () -> "target/test-logs/application.log");
    }

    @Autowired WebTestClient client;
    @Autowired com.gaiys.proto.prototypehub.service.PrototypeVersionService versions;
    @Autowired com.gaiys.proto.prototypehub.service.PrototypeShareService shares;

    @BeforeEach
    void isolatePrototypeData() {
        PrototypeHubTestData.clear(database);
    }

    static final java.nio.file.Path FILES = temporaryStorage();

    static java.nio.file.Path temporaryStorage() {
        try {
            return java.nio.file.Files.createTempDirectory("proto-integration-");
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    static byte[] zip(String html) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.util.zip.ZipOutputStream(bytes)) {
            for (var entry :
                    java.util.Map.of(
                                    "index.html",
                                    "<html><head><title>页面</title></head><body>"
                                            + html
                                            + "</body></html>",
                                    "data/document.js",
                                    "var a='sitemap',b='rootNodes',c='pageName',d='url';function"
                                            + " _creator(){return"
                                            + " _(a,_(b,[_(c,'页面',d,'index.html')]))};return"
                                            + " _creator();",
                                    "images/icon.svg",
                                    "<svg xmlns='http://www.w3.org/2000/svg'/>",
                                    "styles/style.css",
                                    "body{color:red}",
                                    "script.js",
                                    "window.example=1")
                            .entrySet()) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    JsonNode createProject(String token) {
        return client.post()
                .uri("/prototype-hub/projects")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("name", "项目", "description", "描述", "cover", ""))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result");
    }

    JsonNode upload(
            org.springframework.http.HttpMethod method, String path, String token, byte[] bytes) {
        var body = new org.springframework.http.client.MultipartBodyBuilder();
        body.part("versionName", "版本一");
        body.part("description", "描述");
        body.part("file", new org.springframework.core.io.ByteArrayResource(bytes))
                .filename("prototype.zip");
        return client.method(method)
                .uri(path)
                .header("X-Access-Token", token)
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                                body.build()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result");
    }

    @Test
    void allBusinessContractsAndStableReplacementLinks() throws Exception {
        String token = login();
        String project = createProject(token).path("id").asText();
        client.get()
                .uri("/prototype-hub/projects")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/prototype-hub/projects/" + project)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.put()
                .uri("/prototype-hub/projects/" + project)
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("name", "项目更新"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        JsonNode version =
                upload(
                        org.springframework.http.HttpMethod.POST,
                        "/prototype-hub/projects/" + project + "/versions",
                        token,
                        zip("initial"));
        String id = version.path("id").asText();
        JsonNode credential =
                client.post()
                        .uri("/prototype-hub/versions/" + id + "/preview-session")
                        .header("X-Access-Token", token)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        String runtime = credential.path("previewUrl").asText().substring(4);
        var original = versions.requireById(id).block(Duration.ofSeconds(10));
        String originalPath = original.getStoragePath();
        client.get()
                .uri("/prototype-hub/versions?projectId=" + project)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/prototype-hub/versions/" + id)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.storagePath")
                .doesNotExist()
                .jsonPath("$.result.previewKey")
                .doesNotExist();
        client.get()
                .uri("/prototype-hub/versions/" + id + "/pages")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result[0].name")
                .isEqualTo("页面");
        String password = UUID.randomUUID().toString();
        JsonNode share =
                client.post()
                        .uri("/prototype-hub/versions/" + id + "/shares")
                        .header("X-Access-Token", token)
                        .bodyValue(java.util.Map.of("password", password))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        org.junit.jupiter.api.Assertions.assertFalse(share.has("passwordHash"));
        org.junit.jupiter.api.Assertions.assertFalse(share.has("accessKey"));
        String publicPath = "/prototype-hub/public/shares/" + share.path("token").asText();
        client.get()
                .uri("/prototype-hub/versions/" + id + "/shares")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri(publicPath)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.accessKey")
                .isEmpty();
        client.post()
                .uri(publicPath + "/verify")
                .bodyValue(java.util.Map.of("password", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(result -> {});
        JsonNode access =
                client.post()
                        .uri(publicPath + "/verify")
                        .bodyValue(java.util.Map.of("password", password))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        String key = access.path("accessKey").asText();
        client.get()
                .uri(publicPath + "/" + key + "/pages")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri(publicPath + "/bad-key/pages")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri(publicPath + "/" + key + "/runtime/index.html")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri(runtime)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(String.class)
                .value(
                        value ->
                                org.junit.jupiter.api.Assertions.assertTrue(
                                        value.contains("data-prototype-hub-inspector")));
        client.get()
                .uri("/prototype-hub/runtime/" + id + "/bad-key/index.html")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/prototype-hub/public/inspector.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        value ->
                                org.junit.jupiter.api.Assertions.assertTrue(
                                        value.contains("AXURE_INSPECT_ELEMENT")));
        JsonNode updated =
                upload(
                        org.springframework.http.HttpMethod.PUT,
                        "/prototype-hub/versions/" + id,
                        token,
                        zip("replacement"));
        String replacementPath =
                versions.requireById(id).block(Duration.ofSeconds(10)).getStoragePath();
        assertTrue(version.path("previewUrl").isNull());
        assertTrue(updated.path("previewUrl").isNull());
        // 内部凭证与公开分享固定 R1；R2 只改变逻辑当前指针，不重写已发布快照。
        client.get().uri(runtime).exchange().expectStatus().isOk();
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Files.exists(FILES.resolve(originalPath)));
        client.get()
                .uri(publicPath + "/" + key + "/runtime/index.html")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        value ->
                                org.junit.jupiter.api.Assertions.assertTrue(
                                        value.contains("initial")));
        client.delete()
                .uri("/prototype-hub/shares/" + share.path("id").asText())
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri(publicPath)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .consumeWith(result -> {});
        assertNull(shares.findById(share.path("id").asText()).block(Duration.ofSeconds(10)));
        // 独立保留一个未显式删除的分享，验证软删版本时撤销且保留该引用。
        JsonNode retainedShareBeforeDeletion =
                client.post()
                        .uri("/prototype-hub/versions/" + id + "/shares")
                        .header("X-Access-Token", token)
                        .bodyValue(java.util.Map.of())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        String retainedPublicPath =
                "/prototype-hub/public/shares/"
                        + retainedShareBeforeDeletion.path("token").asText();
        JsonNode retainedGrantBeforeDeletion =
                client.get()
                        .uri(retainedPublicPath)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        String retainedRuntimeBeforeDeletion =
                retainedGrantBeforeDeletion.path("previewUrl").asText().substring(4);
        client.get().uri(retainedRuntimeBeforeDeletion).exchange().expectStatus().isOk();
        client.delete()
                .uri("/prototype-hub/versions/" + id)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        // 不存在与已失去项目范围一致返回 404，重复删除不泄露历史归属。
        client.delete()
                .uri("/prototype-hub/versions/" + id)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .consumeWith(result -> {});
        client.delete()
                .uri("/prototype-hub/projects/" + project)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        // 软删除仅阻断访问；旧/新快照、逻辑版本和撤销分享均保留，不产生历史回收任务。
        assertTrue(
                Files.readString(FILES.resolve(originalPath).resolve("index.html"))
                        .contains("initial"));
        assertTrue(
                Files.readString(FILES.resolve(replacementPath).resolve("index.html"))
                        .contains("replacement"));
        assertNotNull(versions.findById(id).block(Duration.ofSeconds(10)).getDeletedTime());
        var retainedShare =
                shares.findById(retainedShareBeforeDeletion.path("id").asText())
                        .block(Duration.ofSeconds(10));
        assertNotNull(retainedShare);
        assertTrue(retainedShare.getRevoked());
        assertFalse(retainedShare.getEnabled());
        assertNotNull(
                database.sql("select deleted_time from p_project where id=:id")
                        .bind("id", project)
                        .map((row, metadata) -> row.get("deleted_time", Long.class))
                        .one()
                        .block(Duration.ofSeconds(10)));
        assertEquals(
                2L,
                database.sql("select count(*) from p_revision where version_id=:id")
                        .bind("id", id)
                        .map((row, metadata) -> row.get(0, Long.class))
                        .one()
                        .block(Duration.ofSeconds(10)));
        assertEquals(0L, cleanup.createQuery().count().block(Duration.ofSeconds(10)).longValue());
        assertEquals(
                0L,
                database.sql("select count(*) from p_preview_session where version_id=:id")
                        .bind("id", id)
                        .map((row, metadata) -> row.get(0, Long.class))
                        .one()
                        .block(Duration.ofSeconds(10)));
        for (String deleted :
                java.util.List.of(
                        "/prototype-hub/projects/" + project, "/prototype-hub/versions/" + id))
            client.get()
                    .uri(deleted)
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        client.get().uri(runtime).exchange().expectStatus().isForbidden();
        client.get()
                .uri(publicPath + "/" + key + "/runtime/index.html")
                .exchange()
                .expectStatus()
                .isNotFound();
        client.get().uri(retainedPublicPath).exchange().expectStatus().isNotFound();
        client.get().uri(retainedRuntimeBeforeDeletion).exchange().expectStatus().isNotFound();
    }

    @Test
    void businessUnauthenticatedAndMemberWritesAreRejected() throws Exception {
        String token = login();
        String project = createProject(token).path("id").asText();
        String id =
                upload(
                                org.springframework.http.HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                token,
                                zip("permissions"))
                        .path("id")
                        .asText();
        for (String path :
                java.util.List.of(
                        "/prototype-hub/projects",
                        "/prototype-hub/projects/" + project,
                        "/prototype-hub/versions",
                        "/prototype-hub/versions/" + id,
                        "/prototype-hub/versions/" + id + "/pages",
                        "/prototype-hub/versions/" + id + "/shares"))
            client.get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody()
                    .consumeWith(result -> {});
        String username = "reader-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        member(username, password);
        String reader = loginAs(username, password);
        client.get()
                .uri("/prototype-hub/projects")
                .header("X-Access-Token", reader)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.post()
                .uri("/prototype-hub/projects")
                .header("X-Access-Token", reader)
                .bodyValue(java.util.Map.of("name", "denied"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(result -> {});
        for (String path :
                java.util.List.of(
                        "/prototype-hub/projects/" + project, "/prototype-hub/versions/" + id))
            client.delete()
                    .uri(path)
                    .header("X-Access-Token", reader)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .consumeWith(result -> {});
        client.post()
                .uri("/prototype-hub/versions/" + id + "/shares")
                .header("X-Access-Token", reader)
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(result -> {});
        client.post()
                .uri("/prototype-hub/projects")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("name", ""))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/prototype-hub/projects/missing-project")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/prototype-hub/public/shares/missing-share")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Autowired
    org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService users;

    @Autowired
    org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<
                    org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity,
                    String>
            bindings;

    @Autowired org.hswebframework.web.authorization.token.UserTokenManager tokens;

    String member(String username, String password) {
        var user = new org.hswebframework.web.system.authorization.api.entity.UserEntity();
        user.setUsername(username);
        user.setName(username);
        user.setPassword(password);
        user.setStatus((byte) 1);
        user = users.addUser(user).block(Duration.ofSeconds(30));
        var binding =
                new org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity();
        binding.setDimensionTypeId("role");
        binding.setDimensionId("MEMBER");
        binding.setDimensionName("Member");
        binding.setUserId(user.getId());
        binding.setUserName(username);
        binding.generateId();
        bindings.insert(reactor.core.publisher.Mono.just(binding)).block(Duration.ofSeconds(10));
        return user.getId();
    }

    String loginAs(String username, String password) {
        JsonNode response =
                client.post()
                        .uri("/authorize/login")
                        .bodyValue(java.util.Map.of("username", username, "password", password))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        return response.path("result").path("token").asText();
    }

    @Test
    void memberCannotAdministerUsersAndDisabledTokensAreRevoked() {
        String username = "member-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        String id = member(username, password);
        String token = loginAs(username, password);
        client.get()
                .uri("/menu/_own/tree")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .value(
                        response -> {
                            var codes = new java.util.HashSet<String>();
                            collectMenuCodes(response.path("result"), codes);
                            assertTrue(codes.contains("prototype-hub/Project"));
                            for (String code :
                                    java.util.Set.of(
                                            "system/Basis",
                                            "system/User",
                                            "system/Department",
                                            "system/Role",
                                            "system/Menu",
                                            "system/Permission",
                                            "system/Dictionary",
                                            "system/Positions",
                                            "system/Log/Access",
                                            "system/Log/System"))
                                assertFalse(codes.contains(code), code);
                        });
        for (String path :
                java.util.List.of(
                        "/user/_query", "/dimension-type/_query", "/dimension-user/_query"))
            client.get()
                    .uri(path)
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .consumeWith(result -> {});
        users.changeState(reactor.core.publisher.Mono.just(id), (byte) 0)
                .block(Duration.ofSeconds(10));
        client.get()
                .uri("/authorize/me")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
        client.post()
                .uri("/authorize/login")
                .bodyValue(java.util.Map.of("username", username, "password", password))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Test
    void expiredTokensAreRejected() throws InterruptedException {
        String token = UUID.randomUUID().toString();
        String id = users.findByUsername("admin").block(Duration.ofSeconds(10)).getId();
        tokens.signIn(token, "default", id, 1).block(Duration.ofSeconds(10));
        Thread.sleep(10);
        client.get()
                .uri("/authorize/me")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Test
    void unknownPathsUseUnifiedLocalizedErrors() {
        client.get()
                .uri("/missing-path")
                .header("Accept-Language", "zh-CN")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false)
                .jsonPath("$.message")
                .isEqualTo("资源不存在");
    }

    /** 直接服务测试沿用真实登录与框架 ParsedToken，不能绕过当前账号和项目校验。 */
    private <T> Mono<T> asUser(String token, Mono<T> operation) {
        return operation.contextWrite(
                context ->
                        context.put(
                                ParsedToken.class,
                                SimpleParsedToken.of(
                                        "default",
                                        token,
                                        (headers, value) -> headers.set("X-Access-Token", value))));
    }

    String login() {
        JsonNode response =
                client.mutate()
                        .responseTimeout(Duration.ofSeconds(30))
                        .build()
                        .post()
                        .uri("/authorize/login")
                        .bodyValue(
                                java.util.Map.of("username", "admin", "password", ADMIN_PASSWORD))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assert response != null;
        org.junit.jupiter.api.Assertions.assertTrue(response.path("success").asBoolean());
        return response.path("result").path("token").asText();
    }

    @Test
    void loginCurrentUserAndLogout() {
        String token = login();
        client.get()
                .uri("/authorize/me")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.user.username")
                .isEqualTo("admin");
        client.get()
                .uri("/user-token/reset")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/authorize/me")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Test
    void unauthenticatedAndWrongPasswordAreRejected() {
        client.get()
                .uri("/menu/_query")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false);
        client.post()
                .uri("/authorize/login")
                .bodyValue(
                        java.util.Map.of(
                                "username", "admin", "password", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Test
    void easyormColdStartHealthAndAdminMenusWork() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
        client.get()
                .uri("/menu/_own/tree")
                .header("X-Access-Token", login())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .value(
                        response -> {
                            var codes = new java.util.HashSet<String>();
                            collectMenuCodes(response.path("result"), codes);
                            assertTrue(
                                    codes.containsAll(
                                            java.util.Set.of(
                                                    "prototype-hub/Project",
                                                    "system/Basis",
                                                    "system/User",
                                                    "system/Department",
                                                    "system/Role",
                                                    "system/Menu",
                                                    "system/Permission",
                                                    "system/Dictionary",
                                                    "system/Positions",
                                                    "system/Log/Access",
                                                    "system/Log/System")));
                        });
    }

    @Autowired org.springframework.r2dbc.core.DatabaseClient database;
    @Autowired FileCleanupService cleanup;
    @Autowired PrototypeUploadService uploads;

    @Test
    void databaseFailurePreservesPreviousFilesAndRemovesReplacement() throws Exception {
        String token = login(), project = createProject(token).path("id").asText();
        JsonNode version =
                upload(
                        HttpMethod.POST,
                        "/prototype-hub/projects/" + project + "/versions",
                        token,
                        zip("original"));
        String id = version.path("id").asText();
        String old = versions.requireById(id).block(Duration.ofSeconds(10)).getStoragePath();
        database.sql(
                        "create function reject_test_version_update() returns trigger language"
                                + " plpgsql as $$ begin if old.id='"
                                + id
                                + "' then raise exception 'controlled failure'; end if; return new;"
                                + " end $$")
                .fetch()
                .rowsUpdated()
                .block();
        database.sql(
                        "create trigger reject_test_update before update on p_version for each row"
                                + " execute function reject_test_version_update()")
                .fetch()
                .rowsUpdated()
                .block();
        try {
            var body = new org.springframework.http.client.MultipartBodyBuilder();
            body.part("versionName", "new");
            body.part("file", new org.springframework.core.io.ByteArrayResource(zip("new")))
                    .filename("a.zip");
            client.put()
                    .uri("/prototype-hub/versions/" + id)
                    .header("X-Access-Token", token)
                    .body(
                            org.springframework.web.reactive.function.BodyInserters
                                    .fromMultipartData(body.build()))
                    .exchange()
                    .expectStatus()
                    .is5xxServerError()
                    .expectBody()
                    .jsonPath("$.message")
                    .isEqualTo("服务器内部错误");
            assertEquals(
                    old, versions.requireById(id).block(Duration.ofSeconds(10)).getStoragePath());
            assertTrue(Files.exists(FILES.resolve(old).resolve("index.html")));
            try (var dirs = Files.list(FILES.resolve("projects/" + project + "/versions"))) {
                assertEquals(1, dirs.count());
            }
        } finally {
            database.sql("drop trigger reject_test_update on p_version")
                    .fetch()
                    .rowsUpdated()
                    .block();
            database.sql("drop function reject_test_version_update()")
                    .fetch()
                    .rowsUpdated()
                    .block();
        }
    }

    @Test
    void unclassifiedHistoricalCleanupRemainsDurableWithoutReclaimingRetainedFiles()
            throws Exception {
        String token = login(), project = createProject(token).path("id").asText();
        String id =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                token,
                                zip("old"))
                        .path("id")
                        .asText();
        String oldPath = versions.requireById(id).block(Duration.ofSeconds(10)).getStoragePath();
        Path old = FILES.resolve(oldPath).getParent();
        Path saved = old.resolveSibling(old.getFileName() + "-saved"),
                neighbor = Files.createTempDirectory("proto-neighbor-");
        Files.writeString(neighbor.resolve("keep"), "safe");
        Files.move(old, saved);
        Files.createSymbolicLink(old, neighbor);
        try {
            upload(HttpMethod.PUT, "/prototype-hub/versions/" + id, token, zip("new"));
            client.delete()
                    .uri("/prototype-hub/versions/" + id)
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertEquals(
                    0L, cleanup.createQuery().count().block(Duration.ofSeconds(10)).longValue());
            // 模拟旧版本遗留任务：来源无法区分历史与失败候选，不得在升级后自动推断回收。
            var legacy = new FileCleanupEntity();
            legacy.setId(UUID.randomUUID().toString());
            legacy.setProjectId(project);
            legacy.setVersionId(id);
            legacy.setResourceType("version");
            legacy.setStoragePath(oldPath);
            legacy.setCreateTime(System.currentTimeMillis());
            legacy.setAttempts(0);
            legacy.setNextAttempt(0L);
            cleanup.insert(legacy).block(Duration.ofSeconds(10));
            long firstAttempt = System.currentTimeMillis();
            cleanup.retryPending().block(Duration.ofSeconds(10));
            var task = cleanup.findById(legacy.getId()).block(Duration.ofSeconds(10));
            assertNotNull(task);
            assertTrue(task.getAttempts() > 0);
            assertTrue(task.getNextAttempt() > firstAttempt);
            assertEquals(legacy.getCreateTime(), task.getCreateTime());
            assertEquals("version", task.getResourceType());
            assertTrue(Files.isSymbolicLink(old));
            assertTrue(Files.exists(neighbor.resolve("keep")));
            Files.delete(old);
            Files.move(saved, old);
            long secondAttempt = System.currentTimeMillis();
            cleanup.attempt(task).block(Duration.ofSeconds(10));
            var retried = cleanup.findById(task.getId()).block(Duration.ofSeconds(10));
            assertNotNull(retried);
            assertTrue(retried.getAttempts() > task.getAttempts());
            assertTrue(retried.getNextAttempt() > secondAttempt);
            assertTrue(Files.readString(old.resolve("site/index.html")).contains("old"));
            assertNotNull(versions.findById(id).block(Duration.ofSeconds(10)).getDeletedTime());
            cleanup.retryPending().block(Duration.ofSeconds(10));
            assertNotNull(cleanup.findById(task.getId()).block(Duration.ofSeconds(10)));
            assertTrue(Files.exists(old));
        } finally {
            if (Files.isSymbolicLink(old)) Files.delete(old);
            if (Files.exists(saved)) Files.move(saved, old);
            com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor.deleteRecursively(
                    neighbor);
        }
    }

    @Test
    void interruptedAndRejectedUploadsLeaveNoTemporaryVersion() throws Exception {
        String token = login(), project = createProject(token).path("id").asText();
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("a.zip");
        var buffer =
                new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                        .wrap(new byte[] {1, 2, 3});
        when(file.content())
                .thenReturn(
                        Flux.concat(
                                Flux.just(buffer),
                                Flux.error(new java.io.IOException("controlled disconnect"))));
        String actor = users.findByUsername("admin").block(Duration.ofSeconds(10)).getId();
        reactor.test.StepVerifier.create(
                        asUser(token, uploads.upload(project, "interrupted", null, file, actor)))
                .expectError()
                .verify(Duration.ofSeconds(10));
        verify(file).content();
        try (var dirs = Files.list(FILES.resolve("projects/" + project + "/versions"))) {
            assertEquals(0, dirs.count());
        }
        var body = new org.springframework.http.client.MultipartBodyBuilder();
        body.part("versionName", "invalid");
        body.part("file", new org.springframework.core.io.ByteArrayResource(new byte[] {1, 2, 3}))
                .filename("a.zip");
        client.post()
                .uri("/prototype-hub/projects/" + project + "/versions")
                .header("X-Access-Token", token)
                .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                                body.build()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(result -> {});
        assertEquals(
                0L,
                versions.createQuery()
                        .where(
                                com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity
                                        ::getProjectId,
                                project)
                        .count()
                        .block()
                        .longValue());
        try (var dirs = Files.list(FILES.resolve("projects/" + project + "/versions"))) {
            assertEquals(0, dirs.count());
        }
    }

    @Autowired
    com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties
            storageProperties;

    @Test
    void cancelledAndOversizedUploadsAreReclaimed() throws Exception {
        String token = login(), project = createProject(token).path("id").asText();
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("a.zip");
        var started = new java.util.concurrent.CountDownLatch(1);
        when(file.content())
                .thenReturn(
                        Flux.defer(
                                () -> {
                                    started.countDown();
                                    return Flux.concat(
                                            Flux.just(
                                                    new org.springframework.core.io.buffer
                                                                    .DefaultDataBufferFactory()
                                                            .wrap(new byte[] {1, 2, 3})),
                                            Flux.never());
                                }));
        String actor = users.findByUsername("admin").block(Duration.ofSeconds(10)).getId();
        var subscription =
                asUser(token, uploads.upload(project, "cancelled", null, file, actor)).subscribe();
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        subscription.dispose();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> {
                            try (var dirs =
                                    Files.list(
                                            FILES.resolve("projects/" + project + "/versions"))) {
                                return dirs.count() == 0;
                            }
                        });
        long previous = storageProperties.getMaxUploadSizeMb();
        storageProperties.setMaxUploadSizeMb(0);
        try {
            var body = new org.springframework.http.client.MultipartBodyBuilder();
            body.part("versionName", "too big");
            body.part("file", new org.springframework.core.io.ByteArrayResource(zip("big")))
                    .filename("a.zip");
            client.post()
                    .uri("/prototype-hub/projects/" + project + "/versions")
                    .header("X-Access-Token", token)
                    .body(
                            org.springframework.web.reactive.function.BodyInserters
                                    .fromMultipartData(body.build()))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("PROTOTYPE_HUB_ZIP_TOO_LARGE");
            try (var dirs = Files.list(FILES.resolve("projects/" + project + "/versions"))) {
                assertEquals(0, dirs.count());
            }
        } finally {
            storageProperties.setMaxUploadSizeMb(previous);
        }
    }

    @Test
    void shareExpiryInvalidDatesAndCapabilityPathsAreDenied() throws Exception {
        String token = login(), project = createProject(token).path("id").asText();
        String id =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                token,
                                zip("share"))
                        .path("id")
                        .asText();
        client.post()
                .uri("/prototype-hub/versions/" + id + "/shares")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("expiresAt", 1))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(result -> {});
        JsonNode share =
                client.post()
                        .uri("/prototype-hub/versions/" + id + "/shares")
                        .header("X-Access-Token", token)
                        .bodyValue(java.util.Map.of())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        String path = "/prototype-hub/public/shares/" + share.path("token").asText();
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.accessKey")
                .isNotEmpty();
        shares.createUpdate()
                .set(com.gaiys.proto.prototypehub.entity.PrototypeShareEntity::getExpiresAt, 1L)
                .where(
                        com.gaiys.proto.prototypehub.entity.PrototypeShareEntity::getId,
                        share.path("id").asText())
                .execute()
                .block(Duration.ofSeconds(10));
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/prototype-hub/versions/missing/pages")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Test
    void administratorUserResetStateRolesAndSelfUpdateRemainSafe() {
        String token = login(),
                username = "managed-" + UUID.randomUUID(),
                password = UUID.randomUUID().toString();
        client.patch()
                .uri("/user")
                .header("X-Access-Token", token)
                .bodyValue(
                        java.util.Map.of(
                                "username",
                                username,
                                "name",
                                "Managed",
                                "password",
                                password,
                                "status",
                                1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        var user = users.findByUsername(username).block(Duration.ofSeconds(10));
        assertTrue(user.getPassword().startsWith("pbkdf2-sha256$"));
        String previous = loginAs(username, password), nextPassword = UUID.randomUUID().toString();
        client.patch()
                .uri("/user")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("id", user.getId(), "password", nextPassword))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/authorize/me")
                .header("X-Access-Token", previous)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
        client.patch()
                .uri("/dimension-user")
                .header("X-Access-Token", token)
                .bodyValue(
                        java.util.Map.of(
                                "dimensionTypeId",
                                "role",
                                "dimensionId",
                                "MEMBER",
                                "dimensionName",
                                "Member",
                                "userId",
                                user.getId(),
                                "userName",
                                username))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        String reset = loginAs(username, nextPassword);
        client.put()
                .uri("/user/" + user.getId() + "/0")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        client.get()
                .uri("/authorize/me")
                .header("X-Access-Token", reset)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(result -> {});
        client.put()
                .uri("/user/" + user.getId() + "/1")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        String self = loginAs(username, nextPassword), selfPassword = UUID.randomUUID().toString();
        client.put()
                .uri("/user/passwd")
                .header("X-Access-Token", self)
                .bodyValue(
                        java.util.Map.of("oldPassword", nextPassword, "newPassword", selfPassword))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        loginAs(username, selfPassword);
        client.put()
                .uri("/user/me")
                .header("X-Access-Token", token)
                .bodyValue(
                        java.util.Map.of(
                                "name", "Administrator", "password", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(result -> {});
        client.put()
                .uri("/user/me")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("name", "Administrator"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> {});
        for (String path :
                java.util.List.of(
                        "/user/_query",
                        "/dimension/_query",
                        "/dimension-type/_query",
                        "/dimension-user/_query",
                        "/permission/_query",
                        "/autz-setting/_query"))
            client.get()
                    .uri(path)
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .consumeWith(result -> {});
    }

    @Test
    void allManagedWriteEndpointsEnforceAuthenticationAndActions() throws Exception {
        String admin = login(), project = createProject(admin).path("id").asText();
        byte[] archive = zip("permission matrix");
        String version =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                admin,
                                archive)
                        .path("id")
                        .asText();
        String username = "matrix-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        member(username, password);
        String reader = loginAs(username, password);
        for (String token : new String[] {null, reader}) {
            int status = token == null ? 401 : 403;
            for (var request :
                    java.util.List.of(
                            new String[] {"POST", "/prototype-hub/projects"},
                            new String[] {"PUT", "/prototype-hub/projects/" + project},
                            new String[] {"POST", "/prototype-hub/versions/" + version + "/shares"},
                            new String[] {"DELETE", "/prototype-hub/projects/" + project},
                            new String[] {"DELETE", "/prototype-hub/versions/" + version},
                            new String[] {"DELETE", "/prototype-hub/shares/missing"})) {
                var spec = client.method(HttpMethod.valueOf(request[0])).uri(request[1]);
                if (token != null) spec.header("X-Access-Token", token);
                if (request[0].equals("POST") || request[0].equals("PUT"))
                    spec.bodyValue(java.util.Map.of("name", "allowed shape"));
                spec.exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody()
                        .consumeWith(result -> {});
            }
            for (var request :
                    java.util.List.of(
                            new String[] {
                                "POST", "/prototype-hub/projects/" + project + "/versions"
                            },
                            new String[] {"PUT", "/prototype-hub/versions/" + version})) {
                var body = new org.springframework.http.client.MultipartBodyBuilder();
                body.part("versionName", "valid");
                body.part("file", new org.springframework.core.io.ByteArrayResource(archive))
                        .filename("a.zip");
                var spec = client.method(HttpMethod.valueOf(request[0])).uri(request[1]);
                if (token != null) spec.header("X-Access-Token", token);
                spec.body(
                                org.springframework.web.reactive.function.BodyInserters
                                        .fromMultipartData(body.build()))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody()
                        .consumeWith(result -> {});
            }
        }
        client.put()
                .uri("/prototype-hub/projects/" + project)
                .header("X-Access-Token", admin)
                .bodyValue(java.util.Map.of("name", ""))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(result -> {});
        client.post()
                .uri("/prototype-hub/versions/" + version + "/shares")
                .header("X-Access-Token", admin)
                .bodyValue(java.util.Map.of("password", "x".repeat(129)))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(result -> {});
    }

    @Test
    void loginBrandingIsAnonymousConfiguredAndContainsOnlyPublicFields() {
        for (String prefix : java.util.List.of("", "/api")) {
            var response =
                    client.get()
                            .uri(prefix + "/system/config/front")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(JsonNode.class)
                            .returnResult()
                            .getResponseBody();
            assertNotNull(response);
            assertTrue(response.path("success").asBoolean());
            assertEquals(200, response.path("status").asInt());
            var front = response.path("result");
            var names = new java.util.HashSet<String>();
            front.fieldNames().forEachRemaining(names::add);
            assertEquals(
                    java.util.Set.of(
                            "title",
                            "logo",
                            "ico",
                            "background",
                            "headerTheme",
                            "showRecordNumber",
                            "recordNumber",
                            "policeRecordNumber",
                            "copyright"),
                    names);
            assertEquals("Configured Proto Bridge", front.path("title").asText());
            assertEquals("/branding/logo.svg", front.path("logo").asText());
            assertEquals("/branding/icon.svg", front.path("ico").asText());
            assertEquals("/branding/login.svg", front.path("background").asText());
            assertEquals("dark", front.path("headerTheme").asText());
            assertTrue(front.path("showRecordNumber").asBoolean());
            assertEquals("ICP-test", front.path("recordNumber").asText());
            assertEquals("police-test", front.path("policeRecordNumber").asText());
            assertEquals("Proto Bridge Test", front.path("copyright").asText());
            assertFalse(response.toString().contains(ADMIN_PASSWORD));
        }
    }

    @Test
    void standaloneSsoApplicationListIsAnonymousAndEmpty() {
        for (String prefix : java.util.List.of("", "/api")) {
            client.get()
                    .uri(prefix + "/application/sso/_all")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.success")
                    .isEqualTo(true)
                    .jsonPath("$.status")
                    .isEqualTo(200)
                    .jsonPath("$.result")
                    .isArray()
                    .jsonPath("$.result.length()")
                    .isEqualTo(0);
        }
    }

    @Test
    void gatewayAliasOpenApiAndTraceHeaderWork() {
        client.get()
                .uri("/api/actuator/health")
                .header("X-Request-ID", "contract-trace")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Request-ID", "contract-trace")
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
        client.get()
                .uri("/api/prototype-hub/public/inspector.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(js -> assertTrue(js.contains("AXURE_INSPECT_ELEMENT")));
        client.mutate()
                .responseTimeout(java.time.Duration.ofSeconds(60))
                .build()
                .get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.paths['/prototype-hub/projects']")
                .exists()
                .jsonPath("$.paths['/protohub/projects']")
                .doesNotExist()
                .jsonPath("$.paths['/protohub/public/inspector.js']")
                .doesNotExist()
                .jsonPath("$.paths['/user/settings/{type}'].get.summary")
                .isEqualTo("查询当前用户的设置列表")
                .jsonPath("$.paths['/user/settings/{type}'].post.summary")
                .isEqualTo("创建当前用户的设置")
                .jsonPath("$.components.schemas.UserSettingRequest.properties.name.type")
                .isEqualTo("string")
                .jsonPath("$.components.schemas.UserSettingRequest.properties.content")
                .exists()
                .jsonPath("$.components.schemas.UserSettingView.properties.id.type")
                .isEqualTo("string")
                .jsonPath("$.components.schemas.UserSettingView.properties.key.type")
                .isEqualTo("string")
                .jsonPath("$.components.schemas.UserSettingView.properties.content")
                .exists()
                .jsonPath("$.components.schemas.UserSettingView.properties.createTime.format")
                .isEqualTo("int64")
                .jsonPath("$.components.schemas.LogLevelRequest.properties.level.type")
                .isEqualTo("string")
                .jsonPath("$.components.schemas.CaptchaConfig.properties.ttl.format")
                .isEqualTo("int64")
                .jsonPath("$.components.schemas.LoginConfig.properties.encrypt['$ref']")
                .isEqualTo("#/components/schemas/LoginEncryptionConfig")
                .jsonPath("$.components.schemas.LoginEncryptionConfig.properties.publicKey.type")
                .isEqualTo("string")
                .jsonPath("$.components.schemas.CaptchaImage.properties.base64.type")
                .isEqualTo("string");
    }

    @Test
    void legacyJsonAutoTypeIsRejectedBeforeClassLoading() {
        assertTrue(com.alibaba.fastjson.parser.ParserConfig.getGlobalInstance().isSafeMode());
        assertThrows(
                com.alibaba.fastjson.JSONException.class,
                () ->
                        com.alibaba.fastjson.JSON.parse(
                                "{\"@type\":\"java.lang.AutoCloseable\",\"value\":1}"));
    }

    @Test
    void infrastructureDisabledLocalLogsAndFrontendAliasesWork() {
        String token = login();
        String accessTrace = "access-" + UUID.randomUUID();
        client.put()
                .uri("/user/detail")
                .header("X-Request-ID", accessTrace)
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("name", "Administrator"))
                .exchange()
                .expectStatus()
                .isOk();
        client.put()
                .uri("/user/detail")
                .header("X-Request-ID", accessTrace)
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of("name", "Administrator", "status", 0))
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.get()
                .uri("/authorize/captcha/config")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.enabled")
                .isEqualTo(false);
        client.get()
                .uri("/authorize/login/configs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.encrypt.enabled")
                .isEqualTo(false);
        client.get()
                .uri("/user/detail")
                .header("X-Request-ID", accessTrace)
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.username")
                .isEqualTo("admin")
                .jsonPath("$.result.password")
                .doesNotExist();
        var asyncFileAppender =
                (ch.qos.logback.classic.AsyncAppender)
                        ((ch.qos.logback.classic.Logger)
                                        org.slf4j.LoggerFactory.getLogger(
                                                org.slf4j.Logger.ROOT_LOGGER_NAME))
                                .getAppender("ASYNC_FILE");
        assertNotNull(asyncFileAppender);
        var fileAppender =
                (ch.qos.logback.core.FileAppender<?>) asyncFileAppender.getAppender("FILE");
        assertNotNull(fileAppender);
        var logPath = Path.of(fileAppender.getFile());
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            assertTrue(Files.exists(logPath));
                            var lines = Files.readAllLines(logPath);
                            var successful =
                                    lines.stream()
                                            .filter(
                                                    l ->
                                                            l.contains(accessTrace)
                                                                    && l.contains("proto.access")
                                                                    && l.contains(
                                                                            "\"httpMethod\":\"GET\""))
                                            .findFirst()
                                            .orElse(null);
                            assertNotNull(successful);
                            assertFalse(successful.contains(token));
                            assertTrue(successful.contains("\"status\":200"));
                            var rejected =
                                    lines.stream()
                                            .filter(
                                                    l ->
                                                            l.contains(accessTrace)
                                                                    && l.contains("proto.access")
                                                                    && l.contains("\"status\":400"))
                                            .findFirst()
                                            .orElse(null);
                            assertNotNull(rejected);
                            assertTrue(rejected.contains("HTTP_400"));
                            assertFalse(rejected.contains(token));
                        });
        client.post()
                .uri("/menu/user-own/tree")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result")
                .isArray();
        client.get()
                .uri("/user/settings/init")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result[0]")
                .isEqualTo("administrator");
        client.post()
                .uri("/logger/system/_query")
                .header("X-Access-Token", token)
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.total")
                .exists();
        String username = "log-member-" + UUID.randomUUID();
        String password = "Member-" + UUID.randomUUID();
        member(username, password);
        String member = loginAs(username, password);
        client.post()
                .uri("/logger/access/_query")
                .header("X-Access-Token", member)
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(body -> {});
        client.put()
                .uri("/logger/level/com.gaiys.proto")
                .header("X-Access-Token", member)
                .bodyValue(java.util.Map.of("level", "DEBUG"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .consumeWith(body -> {});
    }

    private static void collectMenuCodes(JsonNode rows, java.util.Set<String> codes) {
        for (JsonNode row : rows) {
            codes.add(row.path("code").asText());
            collectMenuCodes(row.path("children"), codes);
        }
    }
}
