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
class PrototypeHubRevisionMaterialIntegrationTest {
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
    void sourceDeclarationsBindToTheActualRevisionWithoutChangingManifestAndOldClientsStillWork()
            throws Exception {
        Fixture f = fixture();
        assertEquals(0, query(f, f.owner().token(), 0, 20).path("total").asInt());
        String manifest =
                database.sql("select manifest_sha256 from p_revision where id=:id")
                        .bind("id", f.revision())
                        .map((r, m) -> r.get("manifest_sha256", String.class))
                        .one()
                        .block(TIMEOUT);
        var declaration =
                Map.of(
                        "sourceTool",
                        "Axure",
                        "sourceName",
                        "Original export",
                        "clientRequestId",
                        "legacy-source");
        JsonNode source =
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/prototype-source",
                        f.owner().token(),
                        declaration);
        assertNotEquals(f.revision(), source.path("id").asText());
        assertEquals("prototype", source.path("materialType").asText());
        client.get()
                .uri(endpoint(f) + "/" + source.path("id").asText() + "/content")
                .header("X-Access-Token", f.owner().token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Content-Type", "application/zip")
                .expectBody()
                .consumeWith(
                        response -> {
                            byte[] original = response.getResponseBody();
                            assertNotNull(original);
                            assertEquals(source.path("fileSize").asLong(), original.length);
                            try {
                                assertEquals(
                                        source.path("sha256").asText(),
                                        java.util.HexFormat.of()
                                                .formatHex(
                                                        java.security.MessageDigest.getInstance(
                                                                        "SHA-256")
                                                                .digest(original)));
                            } catch (java.security.NoSuchAlgorithmException error) {
                                throw new AssertionError(error);
                            }
                        });
        assertEquals(
                source,
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/prototype-source",
                        f.owner().token(),
                        declaration));
        assertEquals(1, query(f, f.owner().token(), 0, 20).path("total").asInt());
        assertEquals(
                manifest,
                database.sql("select manifest_sha256 from p_revision where id=:id")
                        .bind("id", f.revision())
                        .map((r, m) -> r.get("manifest_sha256", String.class))
                        .one()
                        .block(TIMEOUT));
        var replacement =
                upload(f.project(), f.version(), f.revision(), "R2", "Replacement export");
        String current = replacement.path("currentRevisionId").asText();
        assertNotEquals(f.revision(), current);
        var currentRows =
                result(
                        HttpMethod.POST,
                        endpoint(f).replace(f.revision(), current) + "/_query",
                        f.owner().token(),
                        Map.of());
        assertEquals(
                "Replacement export", currentRows.path("data").get(0).path("sourceName").asText());
        assertEquals(
                "Original export",
                query(f, f.owner().token(), 0, 20).path("data").get(0).path("sourceName").asText());
        assertFalse(source.has("storagePath"));
        result(
                HttpMethod.POST,
                endpoint(f) + "/" + source.path("id").asText() + "/_delete",
                f.owner().token(),
                mutation(0, "delete-declaration"));
        JsonNode recreated =
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/prototype-source",
                        f.owner().token(),
                        Map.of(
                                "sourceName",
                                "New declaration",
                                "clientRequestId",
                                "recreated-source"));
        assertNotEquals(source.path("id"), recreated.path("id"));
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + source.path("id").asText(),
                f.owner().token(),
                Map.of(
                        "sourceName",
                        "Stale overwrite",
                        "clientRequestId",
                        "stale-before-delete",
                        "expectedVersion",
                        0),
                404);
        call(
                HttpMethod.POST,
                endpoint(f) + "/prototype-source",
                f.owner().token(),
                declaration,
                409);
        assertEquals(
                "New declaration",
                query(f, f.owner().token(), 0, 20).path("data").get(0).path("sourceName").asText());
        assertEquals(2L, count("select count(*) as count from p_revision"));
        assertEquals(
                1,
                query(
                                new Fixture(f.project(), f.version(), current, f.owner()),
                                f.owner().token(),
                                0,
                                20)
                        .path("total")
                        .asInt());
    }

    @Test
    void privateMaterialUploadReplayPagingAndOriginalByteDownloadStayOnTheFixedRevision()
            throws Exception {
        Fixture f = fixture();
        byte[] pdf = "%PDF-1.7\nOriginal private document\n%%EOF".getBytes(StandardCharsets.UTF_8);
        JsonNode a =
                material(f, f.owner().token(), "设计.pdf", pdf, "document-create", 200)
                        .path("result");
        JsonNode replay =
                material(f, f.owner().token(), "设计.pdf", pdf, "document-create", 200)
                        .path("result");
        assertEquals(a, replay);
        assertEquals(1L, count("select count(*) as count from p_revision_material"));
        material(f, f.owner().token(), "changed.pdf", pdf, "document-create", 409);
        material(f, f.owner().token(), "Image.png", png(), "image-create", 200);
        var first = query(f, f.owner().token(), 0, 1);
        var second = query(f, f.owner().token(), 1, 1);
        assertEquals(2, first.path("total").asInt());
        assertEquals(1, first.path("data").size());
        assertNotEquals(
                first.path("data").get(0).path("id"), second.path("data").get(0).path("id"));
        assertEquals(0, query(f, f.owner().token(), 99, 1).path("data").size());
        assertEquals(
                1,
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/_query",
                                f.owner().token(),
                                Map.of("materialType", "image"))
                        .path("total")
                        .asInt());
        client.get()
                .uri(endpoint(f) + "/" + a.path("id").asText() + "/content")
                .header("X-Access-Token", f.owner().token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Content-Type", "application/pdf")
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Cache-Control", "private, no-store")
                .expectHeader()
                .value("Content-Disposition", value -> assertTrue(value.startsWith("attachment;")))
                .expectHeader()
                .value("Content-Security-Policy", value -> assertTrue(value.contains("sandbox")))
                .expectBody()
                .consumeWith(response -> assertArrayEquals(pdf, response.getResponseBody()));
        String current =
                upload(f.project(), f.version(), f.revision(), "R2")
                        .path("currentRevisionId")
                        .asText();
        assertEquals(
                0,
                result(
                                HttpMethod.POST,
                                endpoint(f).replace(f.revision(), current) + "/_query",
                                f.owner().token(),
                                Map.of())
                        .path("total")
                        .asInt());
        call(
                HttpMethod.GET,
                endpoint(f).replace(f.revision(), current)
                        + "/"
                        + a.path("id").asText()
                        + "/content",
                f.owner().token(),
                null,
                404);
    }

    @Test
    void menuActionsAndCurrentMembershipIntersectAndGuestsCannotReadOrWrite() throws Exception {
        Fixture f = fixture();
        var row =
                material(
                                f,
                                f.owner().token(),
                                "notes.txt",
                                "private".getBytes(StandardCharsets.UTF_8),
                                "owner-create",
                                200)
                        .path("result");
        Actor viewer = actor(List.of("query", "save"));
        member(f.project(), viewer.id(), "viewer");
        Actor editor = actor(List.of("query", "save"));
        member(f.project(), editor.id(), "editor");
        Actor noSave = actor(List.of("query"));
        member(f.project(), noSave.id(), "editor");
        Actor outsider = actor(List.of("query", "save"));
        assertEquals(1, query(f, viewer.token(), 0, 20).path("total").asInt());
        material(f, viewer.token(), "notes.txt", new byte[] {1}, "viewer-write", 403);
        material(f, noSave.token(), "notes.txt", new byte[] {1}, "no-save", 403);
        material(f, outsider.token(), "notes.txt", new byte[] {1}, "outsider-write", 404);
        call(HttpMethod.POST, endpoint(f) + "/_query", outsider.token(), Map.of(), 404);
        call(HttpMethod.POST, endpoint(f) + "/_query", null, Map.of(), 401);
        call(
                HttpMethod.GET,
                endpoint(f) + "/" + row.path("id").asText() + "/content",
                null,
                null,
                401);
        material(
                f,
                editor.token(),
                "editor.txt",
                "ok".getBytes(StandardCharsets.UTF_8),
                "editor-write",
                200);
        database.sql("delete from p_project_member where project_id=:p and user_id=:u")
                .bind("p", f.project())
                .bind("u", editor.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        material(
                f,
                editor.token(),
                "editor.txt",
                "ok".getBytes(StandardCharsets.UTF_8),
                "editor-write",
                404);
        Fixture other = fixture();
        call(
                HttpMethod.GET,
                endpoint(other) + "/" + row.path("id").asText() + "/content",
                other.owner().token(),
                null,
                404);
    }

    @Test
    void invalidBytesAndDangerousFormatsFailExplicitlyWithoutRowsOrCandidateFiles()
            throws Exception {
        Fixture f = fixture();
        for (String name :
                List.of(
                        "index.html",
                        "image.svg",
                        "source.rp",
                        "archive.zip",
                        "fake.pdf",
                        "fake.docx",
                        "fake.png")) {
            assertTrue(
                    material(f, f.owner().token(), name, new byte[] {1, 2, 3}, id(), 400)
                            .path("code")
                            .asText()
                            .startsWith("PROTOTYPE_HUB_MATERIAL_"));
        }
        material(f, f.owner().token(), "invalid.txt", new byte[] {(byte) 0xff}, id(), 400);
        material(f, f.owner().token(), "large.png", new byte[5 * 1024 * 1024 + 1], id(), 413);
        assertEquals(0L, count("select count(*) as count from p_revision_material"));
        Path files = FILES.resolve("projects").resolve(f.project()).resolve("materials");
        if (Files.exists(files))
            try (var paths = Files.walk(files)) {
                assertFalse(paths.anyMatch(Files::isRegularFile));
            }
    }

    @Test
    void absentLegacyArchiveFailsExplicitlyRatherThanInventingADeclaration() throws Exception {
        Fixture f = fixture();
        String site =
                database.sql("select storage_path from p_revision where id=:id")
                        .bind("id", f.revision())
                        .map((r, m) -> r.get("storage_path", String.class))
                        .one()
                        .block(TIMEOUT);
        Files.delete(FILES.resolve(site).getParent().resolve("original.zip"));
        assertEquals(
                "PROTOTYPE_HUB_MATERIAL_FILE_MISSING",
                call(
                                HttpMethod.POST,
                                endpoint(f) + "/prototype-source",
                                f.owner().token(),
                                Map.of(
                                        "sourceName",
                                        "Unavailable archive",
                                        "clientRequestId",
                                        "missing-source"),
                                404)
                        .path("code")
                        .asText());
        assertEquals(0L, count("select count(*) as count from p_revision_material"));
    }

    @Test
    void sourceCasUnknownRetriesAndDeletionDoNotOverwriteOrResurrectPayloads() throws Exception {
        Fixture f = fixture();
        String id =
                material(
                                f,
                                f.owner().token(),
                                "notes.txt",
                                "content".getBytes(StandardCharsets.UTF_8),
                                "create",
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        var update =
                Map.of(
                        "expectedVersion",
                        0,
                        "clientRequestId",
                        "update",
                        "sourceName",
                        "Reviewed source");
        JsonNode changed =
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update);
        assertEquals(1, changed.path("configVersion").asInt());
        assertEquals(
                changed, result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update));
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + id,
                f.owner().token(),
                Map.of("expectedVersion", 0, "clientRequestId", "stale", "sourceName", "Overwrite"),
                409);
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + id,
                f.owner().token(),
                Map.of("expectedVersion", 1.1, "clientRequestId", "decimal"),
                400);
        var cleared =
                result(
                        HttpMethod.PUT,
                        endpoint(f) + "/" + id,
                        f.owner().token(),
                        Map.of("expectedVersion", 1, "clientRequestId", "clear"));
        assertTrue(
                cleared.path("sourceName").isNull() || cleared.path("sourceName").isMissingNode());
        result(
                HttpMethod.POST,
                endpoint(f) + "/" + id + "/_delete",
                f.owner().token(),
                mutation(2, "delete"));
        result(
                HttpMethod.POST,
                endpoint(f) + "/" + id + "/_delete",
                f.owner().token(),
                mutation(2, "delete"));
        material(
                f,
                f.owner().token(),
                "notes.txt",
                "content".getBytes(StandardCharsets.UTF_8),
                "create",
                409);
        assertEquals(0L, count("select count(*) as count from p_revision_material"));
        assertEquals(
                1L,
                count(
                        "select count(*) as count from p_file_cleanup where resource_type='material'"));
        cleanup.retryPending().block(TIMEOUT);
        Path files = FILES.resolve("projects").resolve(f.project()).resolve("materials");
        try (var paths = Files.walk(files)) {
            assertFalse(paths.anyMatch(Files::isRegularFile));
        }
    }

    @Test
    void recycleRestoreRetainsMaterialsAndPermanentCleanupRemovesTheirGraphAndFiles()
            throws Exception {
        Fixture f = fixture();
        material(
                f,
                f.owner().token(),
                "notes.txt",
                "content".getBytes(StandardCharsets.UTF_8),
                "create",
                200);
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        call(HttpMethod.POST, endpoint(f) + "/_query", f.owner().token(), Map.of(), 404);
        assertEquals(1L, count("select count(*) as count from p_revision_material"));
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                f.owner().token(),
                null);
        assertEquals(1, query(f, f.owner().token(), 0, 20).path("total").asInt());
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        database.sql("update p_version set deleted_time=:t where id=:id")
                .bind(
                        "t",
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 1000)
                .bind("id", f.version())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(f.version()).block(TIMEOUT));
        assertEquals(0L, count("select count(*) as count from p_revision_material"));
        cleanup.retryPending().block(TIMEOUT);
        try (var paths = Files.walk(FILES.resolve("projects").resolve(f.project()))) {
            assertFalse(paths.anyMatch(Files::isRegularFile));
        }
        assertEquals(
                "固定修订私有资料与人工来源声明",
                database.sql("select obj_description('p_revision_material'::regclass) as d")
                        .map((r, m) -> r.get("d", String.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(
                19L,
                count(
                        "select count(*) as count from pg_attribute where attrelid='p_revision_material'::regclass and attnum>0 and not attisdropped and col_description(attrelid,attnum) ~ '[一-龥]'"));
        assertEquals(
                1L,
                count(
                        "select count(*) as count from information_schema.columns where table_schema='public' and table_name='p_revision_material' and column_name='id' and data_type='character varying' and character_maximum_length=64"));
        assertEquals(
                1L,
                count(
                        "select count(*) as count from pg_index i join pg_attribute a on a.attrelid=i.indrelid and a.attnum=any(i.indkey) where i.indrelid='p_revision_material'::regclass and i.indisprimary and a.attname='id' and i.indnatts=1"));
        assertEquals(
                "资料或来源声明唯一标识，不可复用",
                database.sql(
                                "select col_description(attrelid,attnum) as d from pg_attribute where attrelid='p_revision_material'::regclass and attname='id'")
                        .map((r, m) -> r.get("d", String.class))
                        .one()
                        .block(TIMEOUT));
    }

    @Test
    void parallelRetryAndCasHaveOneWinnerAndRevisionMaterialCountIsAuthoritative()
            throws Exception {
        Fixture f = fixture();
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        var a =
                CompletableFuture.supplyAsync(
                        () ->
                                material(f, f.owner().token(), "notes.txt", bytes, "parallel", 200)
                                        .path("result"));
        var b =
                CompletableFuture.supplyAsync(
                        () ->
                                material(f, f.owner().token(), "notes.txt", bytes, "parallel", 200)
                                        .path("result"));
        String row = a.get(30, TimeUnit.SECONDS).path("id").asText();
        assertEquals(row, b.get(30, TimeUnit.SECONDS).path("id").asText());
        assertEquals(1L, count("select count(*) as count from p_revision_material"));
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + row,
                                        f.owner().token(),
                                        Map.of(
                                                "clientRequestId",
                                                "race-a",
                                                "expectedVersion",
                                                0,
                                                "sourceName",
                                                "A")));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                status(
                                        HttpMethod.PUT,
                                        endpoint(f) + "/" + row,
                                        f.owner().token(),
                                        Map.of(
                                                "clientRequestId",
                                                "race-b",
                                                "expectedVersion",
                                                0,
                                                "sourceName",
                                                "B")));
        assertEquals(
                List.of(200, 409),
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        for (int i = 1; i < 100; i++)
            material(f, f.owner().token(), "notes.txt", bytes, "bounded-" + i, 200);
        assertEquals(
                "PROTOTYPE_HUB_MATERIAL_LIMIT_REACHED",
                material(f, f.owner().token(), "notes.txt", bytes, "one-too-many", 409)
                        .path("code")
                        .asText());
        assertEquals(100, query(f, f.owner().token(), 0, 1).path("total").asInt());
    }

    private JsonNode material(
            Fixture f, String token, String name, byte[] bytes, String key, int status) {
        var parts = new MultipartBodyBuilder();
        parts.part(
                        "file",
                        new ByteArrayResource(bytes) {
                            @Override
                            public String getFilename() {
                                return name;
                            }
                        })
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        var request =
                client.post()
                        .uri(endpoint(f))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) request.header("X-Access-Token", token);
        return request.body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                output);
        return output.toByteArray();
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
        return upload(project, version, expected, label, null);
    }

    private JsonNode upload(
            String project, String version, String expected, String label, String sourceName)
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
        if (sourceName != null) {
            parts.part("sourceTool", "Axure");
            parts.part("sourceName", sourceName);
            parts.part("sourceUrl", "https://example.org/design");
        }
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
        return "/prototype-hub/versions/"
                + f.version()
                + "/revisions/"
                + f.revision()
                + "/materials";
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

    private JsonNode query(Fixture f, String token, int index, int size) {
        return result(
                HttpMethod.POST,
                endpoint(f) + "/_query",
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
