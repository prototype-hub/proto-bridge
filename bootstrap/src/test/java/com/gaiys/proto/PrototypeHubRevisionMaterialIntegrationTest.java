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
    void independentLinksHaveNoPayloadAndReuseFixedScopeCasAndReceipts() throws Exception {
        Fixture f = fixture();
        var input = link("link-create", "设计 PRD", "https://example.org:443/prd");
        JsonNode link = result(HttpMethod.POST, endpoint(f) + "/links", f.owner().token(), input);
        String id = link.path("id").asText();
        assertEquals("link", link.path("materialType").asText());
        for (String field : List.of("fileName", "mediaType", "fileSize", "sha256"))
            assertTrue(link.path(field).isNull(), field);
        assertFalse(link.has("storagePath"));
        assertEquals("UNSUPPORTED", link.path("textStatus").asText());
        assertEquals(
                link, result(HttpMethod.POST, endpoint(f) + "/links", f.owner().token(), input));
        call(
                HttpMethod.POST,
                endpoint(f) + "/links",
                f.owner().token(),
                link("link-create", "Changed", "https://example.org/prd"),
                409);
        assertEquals(
                1L,
                count(
                        "select count(*) as count from p_revision_material where material_type='link' and file_name='' and media_type='' and file_size=0 and sha256='' and storage_path=''"));
        assertFalse(
                Files.exists(FILES.resolve("projects").resolve(f.project()).resolve("materials")));
        assertEquals(
                1,
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/_query",
                                f.owner().token(),
                                Map.of("materialType", "link", "pageSize", 1))
                        .path("total")
                        .asInt());
        assertEquals(link, result(HttpMethod.GET, endpoint(f) + "/" + id, f.owner().token(), null));
        assertEquals(
                "PROTOTYPE_HUB_MATERIAL_TYPE_UNSUPPORTED",
                call(
                                HttpMethod.GET,
                                endpoint(f) + "/" + id + "/content",
                                f.owner().token(),
                                null,
                                400)
                        .path("code")
                        .asText());
        assertEquals(
                "UNSUPPORTED",
                result(HttpMethod.GET, endpoint(f) + "/" + id + "/text", f.owner().token(), null)
                        .path("status")
                        .asText());
        assertEquals(
                "UNSUPPORTED",
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + id + "/text/_extract",
                                f.owner().token(),
                                null)
                        .path("status")
                        .asText());
        var update =
                new LinkedHashMap<String, Object>(
                        link("link-update", "新标题", "https://example.org/new"));
        update.put("expectedVersion", 0);
        JsonNode changed =
                result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update);
        assertEquals(1, changed.path("configVersion").asInt());
        assertEquals("新标题", changed.path("sourceName").asText());
        assertEquals(
                changed, result(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update));
        call(
                HttpMethod.PUT,
                endpoint(f) + "/" + id,
                f.owner().token(),
                Map.of("clientRequestId", "clear-link", "expectedVersion", 1),
                400);
        update.put("clientRequestId", "stale-link");
        call(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update, 409);
        String current =
                upload(f.project(), f.version(), f.revision(), "R2")
                        .path("currentRevisionId")
                        .asText();
        call(
                HttpMethod.GET,
                endpoint(f).replace(f.revision(), current) + "/" + id,
                f.owner().token(),
                null,
                404);
        var deletion = mutation(1, "link-delete");
        result(HttpMethod.POST, endpoint(f) + "/" + id + "/_delete", f.owner().token(), deletion);
        result(HttpMethod.POST, endpoint(f) + "/" + id + "/_delete", f.owner().token(), deletion);
        call(HttpMethod.POST, endpoint(f) + "/links", f.owner().token(), input, 409);
        call(HttpMethod.PUT, endpoint(f) + "/" + id, f.owner().token(), update, 404);
        assertEquals(
                0L,
                count(
                        "select count(*) as count from p_file_cleanup where resource_type='material'"));
    }

    @Test
    void linksRequireCurrentActionsAndMembershipAndKeepIndependentCountBudget() throws Exception {
        Fixture f = fixture();
        var input = link("owned-link", "Requirement", "https://example.org/prd");
        JsonNode row = result(HttpMethod.POST, endpoint(f) + "/links", f.owner().token(), input);
        String detail = endpoint(f) + "/" + row.path("id").asText();
        Actor viewer = actor(List.of("query", "save"));
        member(f.project(), viewer.id(), "viewer");
        result(HttpMethod.GET, detail, viewer.token(), null);
        call(HttpMethod.POST, endpoint(f) + "/links", viewer.token(), input, 403);
        Actor noSave = actor(List.of("query"));
        member(f.project(), noSave.id(), "editor");
        call(HttpMethod.POST, endpoint(f) + "/links", noSave.token(), input, 403);
        Actor noQuery = actor(List.of("save"));
        member(f.project(), noQuery.id(), "editor");
        call(HttpMethod.GET, detail, noQuery.token(), null, 403);
        Actor outsider = actor(List.of("query", "save"));
        call(HttpMethod.GET, detail, outsider.token(), null, 404);
        call(HttpMethod.POST, endpoint(f) + "/links", outsider.token(), input, 404);
        call(HttpMethod.GET, detail, null, null, 401);
        call(HttpMethod.POST, endpoint(f) + "/links", null, input, 401);
        database.sql("delete from p_project_member where project_id=:p and user_id=:u")
                .bind("p", f.project())
                .bind("u", viewer.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, detail, viewer.token(), null, 404);
        database.sql(
                        "insert into p_revision_material (id,project_id,version_id,revision_id,material_type,file_name,media_type,file_size,sha256,storage_path,source_tool,source_name,source_url,source_notes,config_version,created_by,updated_by,created_at,updated_at,text_status,text_content,text_character_count,text_extracted_at,text_extractor_version) select 'bounded-link-' || n, project_id,version_id,revision_id,material_type,file_name,media_type,file_size,sha256,storage_path,source_tool,source_name,source_url,source_notes,config_version,created_by,updated_by,created_at,updated_at,text_status,text_content,text_character_count,text_extracted_at,text_extractor_version from p_revision_material cross join generate_series(1,99) n where id=:id")
                .bind("id", row.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.POST,
                endpoint(f) + "/links",
                f.owner().token(),
                link("too-many-links", "Another", "https://example.org/another"),
                409);
        result(HttpMethod.POST, endpoint(f) + "/links", f.owner().token(), input);
        material(
                f,
                f.owner().token(),
                "notes.txt",
                "original".getBytes(StandardCharsets.UTF_8),
                "file-after-links",
                200);
        assertEquals(101, query(f, f.owner().token(), 0, 1).path("total").asInt());
    }

    @Test
    void linkValidationAndParallelCasNeverOverwriteOrDuplicateAnIntent() throws Exception {
        Fixture f = fixture();
        for (String url :
                List.of(
                        "javascript:alert(1)",
                        "file:///tmp/prd",
                        "/relative",
                        "https://u:p@example.org/prd",
                        "https://example.org:65536/prd",
                        "https://example.org/a b"))
            call(
                    HttpMethod.POST,
                    endpoint(f) + "/links",
                    f.owner().token(),
                    link("invalid", "Title", url),
                    400);
        call(
                HttpMethod.POST,
                endpoint(f) + "/links",
                f.owner().token(),
                Map.of("sourceName", "Title", "sourceUrl", "https://example.org"),
                400);
        call(
                HttpMethod.POST,
                endpoint(f) + "/links",
                f.owner().token(),
                link("invalid-name", " ", "https://example.org"),
                400);
        var input = link("parallel-link", "Original", "https://example.org/prd");
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                result(
                                        HttpMethod.POST,
                                        endpoint(f) + "/links",
                                        f.owner().token(),
                                        input));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                result(
                                        HttpMethod.POST,
                                        endpoint(f) + "/links",
                                        f.owner().token(),
                                        input));
        JsonNode row = first.get(30, TimeUnit.SECONDS);
        assertEquals(row, second.get(30, TimeUnit.SECONDS));
        assertEquals(1L, count("select count(*) as count from p_revision_material"));
        String target = endpoint(f) + "/" + row.path("id").asText();
        var left =
                new LinkedHashMap<String, Object>(
                        link("parallel-left", "Left", "https://example.org/left"));
        left.put("expectedVersion", 0);
        var right =
                new LinkedHashMap<String, Object>(
                        link("parallel-right", "Right", "https://example.org/right"));
        right.put("expectedVersion", 0);
        var leftResult =
                CompletableFuture.supplyAsync(
                        () -> status(HttpMethod.PUT, target, f.owner().token(), left));
        var rightResult =
                CompletableFuture.supplyAsync(
                        () -> status(HttpMethod.PUT, target, f.owner().token(), right));
        assertEquals(
                List.of(200, 409),
                java.util.stream.Stream.of(
                                leftResult.get(30, TimeUnit.SECONDS),
                                rightResult.get(30, TimeUnit.SECONDS))
                        .sorted()
                        .toList());
        assertEquals(
                1,
                result(HttpMethod.GET, target, f.owner().token(), null)
                        .path("configVersion")
                        .asInt());
    }

    @Test
    void linkRecycleRestoreAndPermanentPurgeNeverQueueAPayload() throws Exception {
        Fixture f = fixture();
        JsonNode row =
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/links",
                        f.owner().token(),
                        link("retained-link", "Retained", "https://example.org/retained"));
        String detail = endpoint(f) + "/" + row.path("id").asText();
        call(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version(),
                f.owner().token(),
                null,
                200);
        call(HttpMethod.GET, detail, f.owner().token(), null, 404);
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                f.owner().token(),
                null);
        assertEquals(row, result(HttpMethod.GET, detail, f.owner().token(), null));
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
        assertEquals(
                0L,
                count(
                        "select count(*) as count from p_file_cleanup where resource_type='material'"));
    }

    private Map<String, Object> link(String key, String name, String url) {
        return Map.of("clientRequestId", key, "sourceName", name, "sourceUrl", url);
    }

    @Test
    void pdfPreviewReturnsOnlyOnePngAndKeepsFixedOriginalBytesAndCurrentPermissions()
            throws Exception {
        Fixture f = fixture();
        byte[] pdf;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
                var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 2; i++) {
                var page =
                        new org.apache.pdfbox.pdmodel.PDPage(
                                new org.apache.pdfbox.pdmodel.common.PDRectangle(300, 150));
                document.addPage(page);
                try (var content =
                        new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(i == 0 ? java.awt.Color.RED : java.awt.Color.BLUE);
                    content.addRect(0, 0, 300, 150);
                    content.fill();
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        JsonNode row =
                material(f, f.owner().token(), "layout.pdf", pdf, "pdf-preview", 200)
                        .path("result");
        String base = endpoint(f) + "/" + row.path("id").asText();
        client.get()
                .uri(base + "/preview?page=1")
                .header("X-Access-Token", f.owner().token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.IMAGE_PNG)
                .expectHeader()
                .valueEquals("X-Preview-Page", "1")
                .expectHeader()
                .valueEquals("X-Preview-Pages", "2")
                .expectHeader()
                .valueEquals("X-Preview-Width", "400")
                .expectHeader()
                .valueEquals("X-Preview-Height", "200")
                .expectHeader()
                .valueEquals("Cache-Control", "private, no-store")
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Content-Disposition", "inline; filename=preview.png")
                .expectHeader()
                .valueEquals("Content-Security-Policy", "sandbox; default-src 'none'")
                .expectBody()
                .consumeWith(
                        response -> {
                            try {
                                var image =
                                        javax.imageio.ImageIO.read(
                                                new java.io.ByteArrayInputStream(
                                                        response.getResponseBody()));
                                assertEquals(0x0000ff, image.getRGB(50, 50) & 0xffffff);
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                        });
        client.get()
                .uri(base + "/content")
                .header("X-Access-Token", f.owner().token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PDF)
                .expectHeader()
                .value("Content-Disposition", value -> assertTrue(value.startsWith("attachment;")))
                .expectBody()
                .consumeWith(response -> assertArrayEquals(pdf, response.getResponseBody()));
        assertEquals(
                "PROTOTYPE_HUB_MATERIAL_PREVIEW_PAGE_INVALID",
                call(HttpMethod.GET, base + "/preview?page=99", f.owner().token(), null, 400)
                        .path("code")
                        .asText());
        call(HttpMethod.GET, base + "/preview?page=1.5", f.owner().token(), null, 400);
        call(HttpMethod.GET, base + "/preview", null, null, 401);
        Actor outsider = actor(List.of("query"));
        call(HttpMethod.GET, base + "/preview", outsider.token(), null, 404);
        Actor viewer = actor(List.of("save"));
        member(f.project(), viewer.id(), "viewer");
        call(HttpMethod.GET, base + "/preview", viewer.token(), null, 403);
        JsonNode broken =
                material(
                                f,
                                f.owner().token(),
                                "broken.pdf",
                                "%PDF-broken".getBytes(StandardCharsets.US_ASCII),
                                "broken-pdf",
                                200)
                        .path("result");
        assertEquals(
                "PROTOTYPE_HUB_MATERIAL_PREVIEW_INVALID",
                call(
                                HttpMethod.GET,
                                endpoint(f) + "/" + broken.path("id").asText() + "/preview",
                                f.owner().token(),
                                null,
                                422)
                        .path("code")
                        .asText());
        JsonNode link =
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/links",
                        f.owner().token(),
                        link("pdf-test-link", "External", "https://example.org"));
        call(
                HttpMethod.GET,
                endpoint(f) + "/" + link.path("id").asText() + "/preview",
                f.owner().token(),
                null,
                400);
        String storage =
                database.sql("select storage_path from p_revision_material where id=:id")
                        .bind("id", row.path("id").asText())
                        .map((r, metadata) -> r.get("storage_path", String.class))
                        .one()
                        .block(TIMEOUT);
        byte[] corrupt = pdf.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(FILES.resolve(storage), corrupt);
        call(HttpMethod.GET, base + "/preview", f.owner().token(), null, 404);
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
                24L,
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
        result(
                HttpMethod.POST,
                endpoint(f) + "/links",
                f.owner().token(),
                link("link-after-file-budget", "Independent", "https://example.org/prd"));
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
