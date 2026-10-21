package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeShareService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
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

/** 原型输入边界与能力链接回归；使用隔离数据库和临时存储验证真实 HTTP、持久化及文件读取。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class PrototypeHubApiContractIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String PASSWORD = UUID.randomUUID().toString();
    static final Path FILES = temporaryStorage();

    static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-api-contract-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

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
        registry.add("proto.admin.password", () -> PASSWORD);
        registry.add("prototype-hub.storage.root", () -> FILES.toString());
    }

    @Autowired WebTestClient client;
    @Autowired PrototypeHubStorageProperties storageProperties;
    @Autowired MessageSource messages;
    @Autowired ObjectMapper mapper;
    @Autowired PrototypeVersionService versions;
    @Autowired PrototypeShareService shares;
    @Autowired FileCleanupService cleanup;
    @Autowired DatabaseClient database;
    @LocalServerPort int port;
    String admin;

    @BeforeEach
    void authenticate() {
        PrototypeHubTestData.clear(database);
        client = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        admin =
                call(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", "admin", "password", PASSWORD),
                                200)
                        .path("result")
                        .path("token")
                        .asText();
        assertFalse(admin.isBlank());
    }

    @AfterAll
    static void clearStorage() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.bodyValue(body);
        return response(request.exchange(), status);
    }

    JsonNode response(WebTestClient.ResponseSpec response, int status) {
        JsonNode body =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(body);
        return body;
    }

    @Test
    void newConfigurationMessagesAndInspectorRouteWorkWithoutLegacyManagementRoutes() {
        assertEquals(
                FILES.toAbsolutePath().normalize(),
                storageProperties.getRoot().toAbsolutePath().normalize());
        assertEquals(
                "/api/prototype-hub/public/inspector.js", storageProperties.getInspectorPath());
        assertEquals(
                "项目不存在",
                messages.getMessage("error.prototype-hub.project_not_found", null, Locale.CHINESE));
        assertEquals(
                "Project not found",
                messages.getMessage("error.prototype-hub.project_not_found", null, Locale.ENGLISH));
        byte[] script = null;
        for (String path :
                List.of(
                        "/prototype-hub/public/inspector.js",
                        "/api/prototype-hub/public/inspector.js",
                        "/protohub/public/inspector.js",
                        "/api/protohub/public/inspector.js")) {
            byte[] actual =
                    client.get()
                            .uri(path)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectHeader()
                            .contentType("application/javascript")
                            .expectHeader()
                            .valueEquals("Cache-Control", "no-store")
                            .expectBody()
                            .returnResult()
                            .getResponseBody();
            assertNotNull(actual);
            if (script != null) assertTrue(java.util.Arrays.equals(script, actual));
            script = actual;
        }
        call(HttpMethod.GET, "/protohub/projects", admin, null, 404);
    }

    String project() {
        return call(
                        HttpMethod.POST,
                        "/prototype-hub/projects",
                        admin,
                        Map.of("name", "Boundary"),
                        200)
                .path("result")
                .path("id")
                .asText();
    }

    JsonNode upload(HttpMethod method, String path, String description, String entry, int status)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(
                    ("<html><body>Original file: " + entry + "</body></html>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var body = new MultipartBodyBuilder();
        body.part("versionName", "Release");
        if (description != null) body.part("description", description);
        body.part("file", new ByteArrayResource(bytes.toByteArray())).filename("original.zip");
        return response(
                client.method(method)
                        .uri(path)
                        .header("X-Access-Token", admin)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(body.build()))
                        .exchange(),
                status);
    }

    @Test
    void browserFolderArchivePreservesResourcesOnCreateReplayAndReplacement() throws Exception {
        JsonNode fixture;
        try (var input =
                new ClassPathResource("prototype-hub/folder-upload.json").getInputStream()) {
            fixture = mapper.readTree(input);
        }
        byte[] archive = Base64.getDecoder().decode(fixture.path("zipBase64").asText());
        assertEquals(
                fixture.path("archiveSha256").asText(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
        // 固定生产 Worker 打包器输出；流式 data descriptor 和中文 UTF-8 标记是跨端兼容边界。
        try (var zip = new ZipArchiveInputStream(new ByteArrayInputStream(archive))) {
            ZipArchiveEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                assertTrue(entry.getGeneralPurposeBit().usesDataDescriptor());
                if (entry.getName().chars().anyMatch(value -> value > 127))
                    assertTrue(entry.getGeneralPurposeBit().usesUTF8ForNames());
                zip.transferTo(OutputStream.nullOutputStream());
                count++;
            }
            assertEquals(fixture.path("entries").size(), count);
        }

        String project = project();
        String create = "/prototype-hub/projects/" + project + "/versions";
        String key = UUID.randomUUID().toString();
        JsonNode created = folderArchiveUpload(HttpMethod.POST, create, archive, key);
        String version = created.path("id").asText();
        assertEquals("ready", created.path("status").asText());
        assertEquals("index.html", created.path("entryFile").asText());
        assertFolderResources(fixture, created);
        assertEquals(
                version,
                folderArchiveUpload(HttpMethod.POST, create, archive, key).path("id").asText());
        JsonNode replaced =
                folderArchiveUpload(
                        HttpMethod.PUT, "/prototype-hub/versions/" + version, archive, null);
        assertEquals(version, replaced.path("id").asText());
        assertFolderResources(fixture, replaced);
        assertEquals(
                1,
                call(
                                HttpMethod.GET,
                                "/prototype-hub/versions/_usage?projectId=" + project,
                                admin,
                                null,
                                200)
                        .path("result")
                        .path("versions")
                        .path("used")
                        .asInt());
    }

    /** 浏览器目录与普通 ZIP 共用现 multipart 字段，不引入专用上传端点。 */
    JsonNode folderArchiveUpload(HttpMethod method, String path, byte[] archive, String key) {
        var body = new MultipartBodyBuilder();
        body.part("versionName", "Folder export");
        body.part("description", "Synthetic HTML directory");
        body.part("file", new ByteArrayResource(archive)).filename("folder-export.zip");
        var request = client.method(method).uri(path).header("X-Access-Token", admin);
        if (key != null) request.header("Idempotency-Key", key);
        return response(
                        request.contentType(MediaType.MULTIPART_FORM_DATA)
                                .body(BodyInserters.fromMultipartData(body.build()))
                                .exchange(),
                        200)
                .path("result");
    }

    /** 从公开运行态入口读回所有文件；HTML 仅允许既有 Inspector 注入这一已知差异。 */
    void assertFolderResources(JsonNode fixture, JsonNode version) throws Exception {
        assertTrue(version.path("runtimeBaseUrl").isNull());
        assertTrue(version.path("previewUrl").isNull());
        String base = previewSession(version.path("id").asText()).path("runtimeBaseUrl").asText();
        for (JsonNode entry : fixture.path("entries")) {
            String path = entry.path("path").asText();
            byte[] original = Base64.getDecoder().decode(entry.path("base64").asText());
            assertEquals(
                    entry.path("sha256").asText(),
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            URI encoded = URI.create(new URI(null, null, base + "/" + path, null).toASCIIString());
            URI resource = URI.create("http://localhost:" + port).resolve(encoded);
            byte[] actual =
                    client.get()
                            .uri(resource)
                            .header("Origin", "null")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectHeader()
                            .valueEquals(
                                    "Content-Security-Policy", "sandbox allow-scripts allow-forms")
                            .expectHeader()
                            .doesNotExist("Access-Control-Allow-Credentials")
                            .expectBody()
                            .returnResult()
                            .getResponseBody();
            if (path.endsWith(".html")) {
                assertNotNull(actual);
                String html = new String(actual, StandardCharsets.UTF_8);
                String injected =
                        "<script data-prototype-hub-inspector src=\""
                                + storageProperties.getInspectorPath()
                                + "\"></script>";
                assertTrue(html.contains(injected));
                assertEquals(
                        new String(original, StandardCharsets.UTF_8), html.replace(injected, ""));
            } else {
                // WebTestClient represents a successful zero-length body as null.
                assertArrayEquals(original, actual == null ? new byte[0] : actual, path);
            }
        }
        var stored =
                versions.requireById(version.path("id").asText()).block(Duration.ofSeconds(10));
        assertNotNull(stored);
        try (var paths = Files.walk(FILES.resolve(stored.getStoragePath()))) {
            assertEquals(
                    fixture.path("entries").size(), paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void descriptionBoundariesRejectBeforeStorageAndRetainTheCurrentVersion() throws IOException {
        String project = project();
        String create = "/prototype-hub/projects/" + project + "/versions";
        JsonNode rejected = upload(HttpMethod.POST, create, "a".repeat(2001), "index.html", 400);
        assertEquals("PROTOTYPE_HUB_VERSION_DESCRIPTION_INVALID", rejected.path("code").asText());
        JsonNode emptyVersions =
                call(
                                HttpMethod.GET,
                                "/prototype-hub/versions?projectId=" + project,
                                admin,
                                null,
                                200)
                        .path("result");
        assertTrue(emptyVersions.isArray());
        assertEquals(0, emptyVersions.size());
        assertFalse(Files.exists(FILES.resolve("projects").resolve(project)));
        String maximum = "a".repeat(2000);
        JsonNode created =
                upload(HttpMethod.POST, create, maximum, "index.html", 200).path("result");
        assertEquals(maximum, created.path("description").asText());
        String version = created.path("id").asText();
        String update = "/prototype-hub/versions/" + version;
        var before = versions.requireById(version).block(Duration.ofSeconds(10));
        assertNotNull(before);
        rejected = upload(HttpMethod.PUT, update, "a".repeat(2001), "replacement.html", 400);
        assertEquals("PROTOTYPE_HUB_VERSION_DESCRIPTION_INVALID", rejected.path("code").asText());
        var after = versions.requireById(version).block(Duration.ofSeconds(10));
        assertNotNull(after);
        assertEquals(before.getStoragePath(), after.getStoragePath());
        assertEquals(maximum, after.getDescription());
        try (var paths =
                Files.list(FILES.resolve("projects").resolve(project).resolve("versions"))) {
            assertEquals(1L, paths.count());
        }
        assertTrue(
                upload(HttpMethod.PUT, update, null, "index.html", 200)
                        .path("result")
                        .path("description")
                        .isNull());
        assertEquals(
                "",
                upload(HttpMethod.POST, create, "", "index.html", 200)
                        .path("result")
                        .path("description")
                        .asText());
    }

    @Test
    void projectDeletionRequiresAllVersionsAndRejectsWithoutSideEffects() throws IOException {
        String project = project();
        JsonNode uploaded =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                null,
                                "index.html",
                                200)
                        .path("result");
        String version = uploaded.path("id").asText();
        String storagePath =
                versions.requireById(version).block(Duration.ofSeconds(10)).getStoragePath();
        JsonNode share =
                call(
                                HttpMethod.POST,
                                "/prototype-hub/versions/" + version + "/shares",
                                admin,
                                Map.of("description", "must survive rejected project deletion"),
                                200)
                        .path("result");
        String shareId = share.path("id").asText();
        Path projectFiles = FILES.resolve("projects").resolve(project);
        assertTrue(Files.exists(FILES.resolve(storagePath).resolve("index.html")));
        assertEquals(0L, cleanupTasks(project));

        JsonNode chinese =
                response(
                        client.delete()
                                .uri("/prototype-hub/projects/" + project)
                                .header("X-Access-Token", admin)
                                .header("Accept-Language", "zh-CN")
                                .exchange(),
                        409);
        assertEquals("PROTOTYPE_HUB_PROJECT_HAS_VERSIONS", chinese.path("code").asText());
        assertEquals("项目下存在版本，请先删除全部版本后再删除项目", chinese.path("message").asText());

        JsonNode english =
                response(
                        client.delete()
                                .uri("/prototype-hub/projects/" + project)
                                .header("X-Access-Token", admin)
                                .header("Accept-Language", "en-US")
                                .exchange(),
                        409);
        assertEquals("PROTOTYPE_HUB_PROJECT_HAS_VERSIONS", english.path("code").asText());
        assertEquals(
                "The project has versions. Delete all versions before deleting the project.",
                english.path("message").asText());
        assertEquals(
                "项目下存在版本，请先删除全部版本后再删除项目",
                messages.getMessage(
                        "error.prototype-hub.project_has_versions", null, Locale.CHINESE));
        assertEquals(
                "The project has versions. Delete all versions before deleting the project.",
                messages.getMessage(
                        "error.prototype-hub.project_has_versions", null, Locale.ENGLISH));

        call(HttpMethod.GET, "/prototype-hub/projects/" + project, admin, null, 200);
        assertNotNull(versions.findById(version).block(Duration.ofSeconds(10)));
        assertNotNull(shares.findById(shareId).block(Duration.ofSeconds(10)));
        assertEquals(0L, cleanupTasks(project));
        assertTrue(Files.exists(FILES.resolve(storagePath).resolve("index.html")));

        call(HttpMethod.DELETE, "/prototype-hub/versions/" + version, admin, null, 200);
        assertNull(versions.findById(version).block(Duration.ofSeconds(10)));
        assertNull(shares.findById(shareId).block(Duration.ofSeconds(10)));
        call(HttpMethod.DELETE, "/prototype-hub/projects/" + project, admin, null, 200);
        call(HttpMethod.GET, "/prototype-hub/projects/" + project, admin, null, 404);
        assertFalse(Files.exists(projectFiles));
    }

    long cleanupTasks(String project) {
        Number count =
                cleanup.createQuery()
                        .where(FileCleanupEntity::getProjectId, project)
                        .count()
                        .block(Duration.ofSeconds(10));
        assertNotNull(count);
        return count.longValue();
    }

    @Test
    void capabilityPreviewUrlsReadTheOriginalLegalZipFilenames() throws IOException {
        String project = project();
        for (String entry :
                List.of(
                        "目录/index%.html",
                        "目录/index%20page.html",
                        "目录/a b?#.html",
                        "index;v.html",
                        "dir;v/index.html")) {
            JsonNode version =
                    upload(
                                    HttpMethod.POST,
                                    "/prototype-hub/projects/" + project + "/versions",
                                    null,
                                    entry,
                                    200)
                            .path("result");
            assertEquals(entry, version.path("entryFile").asText());
            assertPreview(entry, version);
            JsonNode share =
                    call(
                                    HttpMethod.POST,
                                    "/prototype-hub/versions/"
                                            + version.path("id").asText()
                                            + "/shares",
                                    admin,
                                    Map.of(),
                                    200)
                            .path("result");
            JsonNode info =
                    call(
                                    HttpMethod.GET,
                                    "/prototype-hub/public/shares/" + share.path("token").asText(),
                                    null,
                                    null,
                                    200)
                            .path("result");
            assertPreview(entry, info);
        }
    }

    JsonNode previewSession(String version) {
        return call(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version + "/preview-session",
                        admin,
                        null,
                        200)
                .path("result");
    }

    void assertPreview(String entry, JsonNode info) {
        // 管理接口不发匿名长期能力；公开分享响应已经授予独立外部能力。
        if (info.hasNonNull("id")) info = previewSession(info.path("id").asText());
        String base = info.path("runtimeBaseUrl").asText();
        URI preview = URI.create(info.path("previewUrl").asText());
        assertFalse(preview.isAbsolute());
        assertEquals(entry, preview.getPath().substring(base.length() + 1));
        assertNull(preview.getRawQuery());
        assertNull(preview.getRawFragment());
        // uri(URI)不应用WebTestClient的base URL；解析到随机端口且不重新编码路径。
        URI resource = URI.create("http://localhost:" + port).resolve(preview);
        assertEquals(preview.getRawPath(), resource.getRawPath());
        client.get()
                .uri(resource)
                .header("Origin", "null")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectHeader()
                .valueEquals("Content-Security-Policy", "sandbox allow-scripts allow-forms")
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "null")
                .expectHeader()
                .doesNotExist("Access-Control-Allow-Credentials")
                .expectBody(String.class)
                .value(html -> assertTrue(html.contains("Original file: " + entry)));
    }

    @Test
    void shareDescriptionsPersistInManagementViewsAndStayPrivateInPublicViews() throws IOException {
        String project = project();
        String version =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                null,
                                "index.html",
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        String create = "/prototype-hub/versions/" + version + "/shares";

        JsonNode legacy = call(HttpMethod.POST, create, admin, Map.of(), 200).path("result");
        assertTrue(legacy.path("description").isNull());

        String maximum = "描".repeat(2000);
        JsonNode created =
                call(HttpMethod.POST, create, admin, Map.of("description", maximum), 200)
                        .path("result");
        assertEquals(maximum, created.path("description").asText());
        PrototypeShareEntity persisted =
                shares.findById(created.path("id").asText()).block(Duration.ofSeconds(10));
        assertNotNull(persisted);
        assertEquals(maximum, persisted.getDescription());

        JsonNode listed = call(HttpMethod.GET, create, admin, null, 200).path("result");
        JsonNode listedShare = null;
        for (JsonNode candidate : listed) {
            if (created.path("id").asText().equals(candidate.path("id").asText())) {
                listedShare = candidate;
                break;
            }
        }
        assertNotNull(listedShare);
        assertEquals(maximum, listedShare.path("description").asText());

        JsonNode publicInfo =
                call(
                                HttpMethod.GET,
                                "/prototype-hub/public/shares/" + created.path("token").asText(),
                                null,
                                null,
                                200)
                        .path("result");
        assertFalse(publicInfo.has("description"));

        JsonNode rejected =
                call(HttpMethod.POST, create, admin, Map.of("description", "a".repeat(2001)), 400);
        assertEquals("HTTP_400", rejected.path("code").asText());
        assertEquals(
                listed.size(),
                call(HttpMethod.GET, create, admin, null, 200).path("result").size());
    }

    @Test
    void newPasswordByteLimitsDoNotChangeOptionalOrPersistedShareVerification() throws IOException {
        String project = project();
        String version =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                null,
                                "index.html",
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        String create = "/prototype-hub/versions/" + version + "/shares";
        for (String password : List.of("a".repeat(73), "密".repeat(25))) {
            JsonNode error =
                    call(HttpMethod.POST, create, admin, Map.of("password", password), 400);
            assertEquals("PROTOTYPE_HUB_SHARE_PASSWORD_TOO_LONG", error.path("code").asText());
        }
        JsonNode emptyShares = call(HttpMethod.GET, create, admin, null, 200).path("result");
        assertTrue(emptyShares.isArray());
        assertEquals(0, emptyShares.size());
        for (String password : List.of("a".repeat(72), "密".repeat(24))) {
            JsonNode share =
                    call(HttpMethod.POST, create, admin, Map.of("password", password), 200)
                            .path("result");
            assertTrue(share.path("passwordRequired").asBoolean());
            String publicPath = "/prototype-hub/public/shares/" + share.path("token").asText();
            JsonNode waiting = call(HttpMethod.GET, publicPath, null, null, 200).path("result");
            assertTrue(waiting.path("accessKey").isNull());
            JsonNode granted =
                    call(
                                    HttpMethod.POST,
                                    publicPath + "/verify",
                                    null,
                                    Map.of("password", password),
                                    200)
                            .path("result");
            assertFalse(granted.path("accessKey").asText().isBlank());
            // BCrypt 的旧 hash 验证仍接受72字节后的尾部，不因新增创建限制锁住既存链接。
            assertEquals(
                    granted.path("accessKey"),
                    call(
                                    HttpMethod.POST,
                                    publicPath + "/verify",
                                    null,
                                    Map.of("password", password + "tail"),
                                    200)
                            .path("result")
                            .path("accessKey"));
            assertTrue(
                    call(HttpMethod.POST, publicPath + "/verify", null, Map.of(), 200)
                            .path("result")
                            .path("accessKey")
                            .isNull());
        }
        for (Map<String, String> optional :
                List.of(Map.<String, String>of(), Map.of("password", " ".repeat(80)))) {
            JsonNode share = call(HttpMethod.POST, create, admin, optional, 200).path("result");
            assertFalse(share.path("passwordRequired").asBoolean());
            assertFalse(
                    call(
                                    HttpMethod.GET,
                                    "/prototype-hub/public/shares/" + share.path("token").asText(),
                                    null,
                                    null,
                                    200)
                            .path("result")
                            .path("accessKey")
                            .asText()
                            .isBlank());
        }
    }

    @Test
    void managementPasswordChecksPreserveCredentialsAndWorkForInactiveShares() throws IOException {
        String project = project();
        String version =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                null,
                                "index.html",
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        String create = "/prototype-hub/versions/" + version + "/shares";
        String password = "  分享密碼  ";
        JsonNode share =
                call(HttpMethod.POST, create, admin, Map.of("password", password), 200)
                        .path("result");
        String id = share.path("id").asText();
        String publicPath = "/prototype-hub/public/shares/" + share.path("token").asText();
        PrototypeShareEntity original = shares.findById(id).block(Duration.ofSeconds(10));
        assertNotNull(original);
        assertFalse(share.has("password"));
        assertFalse(share.has("passwordHash"));
        assertFalse(share.has("accessKey"));
        assertPasswordCheck(id, admin, Map.of("password", password), true);
        assertPasswordCheck(id, admin, Map.of("password", password.strip()), false);
        assertPasswordCheck(id, admin, Map.of("password", "incorrect"), false);
        assertPasswordCheck(id, admin, Map.of("password", ""), false);
        assertPasswordCheck(id, admin, Map.of("password", "   "), false);
        assertPasswordCheck(id, admin, Map.of(), false);
        assertPasswordCheck(id, admin, java.util.Collections.singletonMap("password", null), false);
        assertPasswordCheck(id, admin, Map.of("password", "x".repeat(128)), false);
        assertPasswordCheck(id, admin, Map.of("password", password), true);
        // 管理校验不改变公开访问授权；匿名 GET 仍需密码且未获得访问密钥。
        JsonNode waiting = call(HttpMethod.GET, publicPath, null, null, 200).path("result");
        assertTrue(waiting.path("accessKey").isNull());
        assertFalse(waiting.has("password"));
        call(HttpMethod.POST, publicPath + "/verify", null, Map.of("password", "wrong"), 403);
        assertFalse(
                call(
                                HttpMethod.POST,
                                publicPath + "/verify",
                                null,
                                Map.of("password", password),
                                200)
                        .path("result")
                        .path("accessKey")
                        .asText()
                        .isBlank());

        assertEquals(
                1,
                shares.createUpdate()
                        .set(PrototypeShareEntity::getExpiresAt, 1L)
                        .where(PrototypeShareEntity::getId, id)
                        .execute()
                        .block(Duration.ofSeconds(10)));
        assertPasswordCheck(id, admin, Map.of("password", password), true);
        call(HttpMethod.GET, publicPath, null, null, 404);
        assertEquals(
                1,
                shares.createUpdate()
                        .setNull(PrototypeShareEntity::getExpiresAt)
                        .set(PrototypeShareEntity::getEnabled, false)
                        .where(PrototypeShareEntity::getId, id)
                        .execute()
                        .block(Duration.ofSeconds(10)));
        assertPasswordCheck(id, admin, Map.of("password", password), true);
        call(HttpMethod.POST, publicPath + "/verify", null, Map.of("password", password), 404);
        PrototypeShareEntity unchanged = shares.findById(id).block(Duration.ofSeconds(10));
        assertNotNull(unchanged);
        assertEquals(original.getPasswordHash(), unchanged.getPasswordHash());
        assertEquals(original.getAccessKey(), unchanged.getAccessKey());
        assertEquals(original.getToken(), unchanged.getToken());
        assertFalse(unchanged.getEnabled());
        assertNull(unchanged.getExpiresAt());

        JsonNode optional = call(HttpMethod.POST, create, admin, Map.of(), 200).path("result");
        assertPasswordCheck(optional.path("id").asText(), admin, Map.of(), true);
        assertPasswordCheck(
                optional.path("id").asText(),
                admin,
                java.util.Collections.singletonMap("password", null),
                true);
        assertPasswordCheck(
                optional.path("id").asText(), admin, Map.of("password", "unused"), true);
        String utf8Password = "密".repeat(24);
        JsonNode legacy =
                call(HttpMethod.POST, create, admin, Map.of("password", utf8Password), 200)
                        .path("result");
        // 既存 BCrypt 支持72字节后的尾部，管理校验不能套用新分享的创建限制。
        assertPasswordCheck(
                legacy.path("id").asText(), admin, Map.of("password", utf8Password), true);
        assertPasswordCheck(
                legacy.path("id").asText(), admin, Map.of("password", utf8Password + "tail"), true);
        call(
                HttpMethod.POST,
                "/prototype-hub/shares/" + id + "/verify-password",
                admin,
                Map.of("password", "x".repeat(129)),
                400);
        JsonNode missing =
                call(
                        HttpMethod.POST,
                        "/prototype-hub/shares/missing-share/verify-password",
                        admin,
                        Map.of(),
                        404);
        assertEquals("PROTOTYPE_HUB_SHARE_NOT_FOUND", missing.path("code").asText());
    }

    @Test
    void managementPasswordCheckUsesShareQueryPermissionAndProjectScope() throws IOException {
        String project = project();
        String version =
                upload(
                                HttpMethod.POST,
                                "/prototype-hub/projects/" + project + "/versions",
                                null,
                                "index.html",
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        JsonNode share =
                call(
                                HttpMethod.POST,
                                "/prototype-hub/versions/" + version + "/shares",
                                admin,
                                Map.of("password", "known"),
                                200)
                        .path("result");
        String id = share.path("id").asText();
        String path = "/prototype-hub/shares/" + id + "/verify-password";
        call(HttpMethod.POST, path, null, Map.of("password", "known"), 401);
        String reader = "share-reader-" + UUID.randomUUID();
        String readerId = createPasswordCheckUser(reader);
        call(HttpMethod.POST, "/role/MEMBER/users/_bind", admin, List.of(readerId), 200);
        call(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + readerId,
                admin,
                Map.of("role", "editor"),
                200);
        String readToken = passwordCheckUserToken(reader);
        assertPasswordCheck(id, readToken, Map.of("password", "known"), true);
        assertPasswordCheck(id, readToken, Map.of("password", "wrong"), false);
        // 项目 editor 仍只有 MEMBER 的通用查询动作；校验不需要 save，创建分享仍被拒绝。
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version + "/shares",
                readToken,
                Map.of(),
                403);
        String denied = "share-denied-" + UUID.randomUUID();
        createPasswordCheckUser(denied);
        call(
                HttpMethod.POST,
                path,
                passwordCheckUserToken(denied),
                Map.of("password", "known"),
                403);
    }

    void assertPasswordCheck(String id, String token, Object input, boolean expected) {
        JsonNode response =
                call(
                        HttpMethod.POST,
                        "/prototype-hub/shares/" + id + "/verify-password",
                        token,
                        input,
                        200);
        assertTrue(response.path("success").asBoolean());
        assertTrue(response.path("result").isBoolean());
        assertEquals(expected, response.path("result").asBoolean());
        assertFalse(response.has("password"));
        assertFalse(response.has("passwordHash"));
        assertFalse(response.has("accessKey"));
    }

    String createPasswordCheckUser(String username) {
        return call(
                        HttpMethod.POST,
                        "/user/detail/_create",
                        admin,
                        Map.of("name", username, "username", username, "password", PASSWORD),
                        200)
                .path("result")
                .path("id")
                .asText();
    }

    String passwordCheckUserToken(String username) {
        return call(
                        HttpMethod.POST,
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD),
                        200)
                .path("result")
                .path("token")
                .asText();
    }
}
