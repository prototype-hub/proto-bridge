package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeCopyEntryEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeCopySnapshotEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewMessageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.service.PrototypeSearchService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 本机真实HTTP/PostgreSQL验证三来源权限、字面搜索及COUNT/分页；不连接任何业务部署。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml", "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
                    "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000", "prototype-hub.review.mail.enabled=false"
        })
@Testcontainers
class PrototypeHubSearchIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String ENDPOINT = "/prototype-hub/search/_query";
    private static final Map<String, List<String>> ACTIONS =
            Map.of("prototype-hub-version", List.of("query"));

    @Container
    static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withLabel("prototypehub.owner", "gyf-f20-search-integration");

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
        properties.add("spring.r2dbc.pool.max-size", () -> 4);
        properties.add("spring.r2dbc.pool.initial-size", () -> 1);
        properties.add("proto.admin.password", () -> PASSWORD);
    }

    @Autowired WebTestClient client;
    @Autowired ObjectMapper mapper;
    @Autowired DatabaseClient database;
    @Autowired ReactiveRepository<PrototypeProjectEntity, String> projects;

    @Autowired
    ReactiveRepository<org.hswebframework.web.system.authorization.api.entity.UserEntity, String>
            users;

    @Autowired
    ReactiveRepository<
                    org.hswebframework.web.system.authorization.api.entity.DimensionEntity, String>
            dimensions;

    @Autowired
    ReactiveRepository<
                    org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity,
                    String>
            dimensionUsers;

    @Autowired ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    @Autowired ReactiveRepository<PrototypeVersionEntity, String> versions;
    @Autowired ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    @Autowired ReactiveRepository<PrototypeCopyEntryEntity, String> copies;
    @Autowired ReactiveRepository<PrototypeCopySnapshotEntity, String> snapshots;
    @Autowired ReactiveRepository<PrototypeRevisionMaterialEntity, String> materials;
    @Autowired ReactiveRepository<PrototypeReviewThreadEntity, String> threads;
    @Autowired ReactiveRepository<PrototypeReviewMessageEntity, String> messages;
    private String admin;

    @BeforeEach
    void isolate() {
        database.sql("truncate table p_copy_entry,p_copy_snapshot")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        PrototypeHubTestData.clear(database);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
    }

    @Test
    void readAndEditScopesLimitBothCountAndDataWithoutGrantingNewActions() {
        Actor owner = actor(ACTIONS),
                editor = actor(ACTIONS),
                viewer = actor(ACTIONS),
                outsider = actor(ACTIONS);
        Actor noQuery = actor(Map.of());
        Fixture own = fixture(owner.id(), "Current project"),
                hidden = fixture(outsider.id(), "Hidden project");
        member(own, editor.id(), "editor");
        member(own, viewer.id(), "viewer");
        member(own, noQuery.id(), "editor");
        seed(own, "Needle_100%", 1);
        seed(hidden, "Needle_100%", 1);
        for (String type : List.of("COPY", "MATERIAL")) {
            for (Actor actor : List.of(owner, editor, viewer, outsider))
                assertPage(type, actor.token(), "Needle_100%", 1, 1);
            assertPage(type, admin, "Needle_100%", 2, 2);
        }
        assertPage("FEEDBACK", viewer.token(), "Needle_100%", 0, 0);
        for (Actor actor : List.of(owner, editor, outsider))
            assertPage("FEEDBACK", actor.token(), "Needle_100%", 1, 1);
        assertPage("FEEDBACK", admin, "Needle_100%", 2, 2);
        call(HttpMethod.POST, ENDPOINT, noQuery.token(), body("COPY", "Needle_100%"), 403);
        call(HttpMethod.POST, ENDPOINT, null, body("COPY", "Needle_100%"), 401);
        for (String type : List.of("COPY", "MATERIAL", "FEEDBACK")) {
            JsonNode row = query(type, owner.token(), "Needle_100%").path("data").get(0);
            assertEquals(own.project(), row.path("projectId").asText());
            assertEquals(own.version(), row.path("versionId").asText());
            assertEquals(own.revision(), row.path("revisionId").asText());
            assertEquals(10, row.path("revisionCreatedAt").asInt());
            for (String forbidden :
                    List.of("storagePath", "guestId", "sourceUrl", "previewKey", "manifestJson"))
                assertFalse(row.has(forbidden));
            if (type.equals("FEEDBACK")) {
                assertNotEquals(row.path("sourceId"), row.path("threadId"));
                assertEquals("internal", row.path("visibility").asText());
                assertEquals("Needle_100%", row.path("title").asText());
            }
        }
    }

    @Test
    void optionalIdsOnlyNarrowTheAuthorizedParentChainAndDoNotFollowCurrentRevision() {
        Actor owner = actor(ACTIONS);
        Fixture first = fixture(owner.id(), "First"), second = fixture(owner.id(), "Second");
        seed(first, "frozen", 1);
        seed(second, "frozen", 1);
        for (String type : List.of("COPY", "MATERIAL", "FEEDBACK")) {
            var scoped = body(type, "frozen");
            scoped.put("revisionId", first.revision());
            assertEquals(
                    first.revision(),
                    page(owner.token(), scoped).path("data").get(0).path("revisionId").asText());
            scoped.put("versionId", second.version());
            assertEquals(0, page(owner.token(), scoped).path("total").asInt());
            scoped.put("versionId", first.version());
            scoped.put("projectId", second.project());
            assertEquals(0, page(owner.token(), scoped).path("total").asInt());
            scoped.put("projectId", "missing");
            assertEquals(0, page(owner.token(), scoped).path("total").asInt());
        }
        versions.createUpdate()
                .set(PrototypeVersionEntity::getCurrentRevisionId, second.revision())
                .where(PrototypeVersionEntity::getId, first.version())
                .execute()
                .block(TIMEOUT);
        var originalProject = body("COPY", "frozen");
        originalProject.put("projectId", first.project());
        assertEquals(
                first.revision(),
                page(owner.token(), originalProject)
                        .path("data")
                        .get(0)
                        .path("revisionId")
                        .asText());
        copies.createUpdate()
                .set(PrototypeCopyEntryEntity::getProjectId, second.project())
                .where(PrototypeCopyEntryEntity::getProjectId, first.project())
                .execute()
                .block(TIMEOUT);
        assertPage("COPY", owner.token(), "frozen", 1, 1);
    }

    @Test
    void currentMembershipAccountAndMenuPermissionAreRecheckedOnEverySearch() {
        Actor owner = actor(ACTIONS), editor = actor(ACTIONS);
        Fixture fixture = fixture(owner.id(), "Private");
        seed(fixture, "fresh", 1);
        member(fixture, editor.id(), "editor");
        assertPage("FEEDBACK", editor.token(), "fresh", 1, 1);
        members.createUpdate()
                .set(PrototypeProjectMemberEntity::getRole, "viewer")
                .where(PrototypeProjectMemberEntity::getUserId, editor.id())
                .execute()
                .block(TIMEOUT);
        assertPage("FEEDBACK", editor.token(), "fresh", 0, 0);
        assertPage("COPY", editor.token(), "fresh", 1, 1);
        members.createDelete()
                .where(PrototypeProjectMemberEntity::getUserId, editor.id())
                .execute()
                .block(TIMEOUT);
        assertPage("COPY", editor.token(), "fresh", 0, 0);
        revoke(owner.role());
        call(HttpMethod.POST, ENDPOINT, owner.token(), body("MATERIAL", "fresh"), 403);
    }

    @Test
    void inactiveAccountsAndDisabledOrRemovedAdministratorBindingsDoNotRetainSearchAccess() {
        Actor owner = actor(ACTIONS), administrator = actor(ACTIONS);
        Fixture f = fixture(owner.id(), "Admin scope");
        seed(f, "administrator-scope", 1);
        result(HttpMethod.POST, "/role/ADMIN/users/_bind", admin, List.of(administrator.id()));
        assertPage("FEEDBACK", administrator.token(), "administrator-scope", 1, 1);
        var role = dimensions.findById("ADMIN").block(TIMEOUT);
        Map<String, Object> original = role.getProperties();
        assertNotNull(original);
        try {
            var disabled = new java.util.LinkedHashMap<String, Object>(original);
            disabled.put("state", "disabled");
            // 正常写入口保护内置角色；绑定参数模拟数据库中已有的禁用状态，验证实时读边界。
            database.sql("update s_dimension set properties=:properties where id=:id")
                    .bind("properties", mapper.valueToTree(disabled).toString())
                    .bind("id", "ADMIN")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
            assertPage("FEEDBACK", administrator.token(), "administrator-scope", 0, 0);
        } finally {
            database.sql("update s_dimension set properties=:properties where id=:id")
                    .bind("properties", mapper.valueToTree(original).toString())
                    .bind("id", "ADMIN")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        }
        assertPage("FEEDBACK", administrator.token(), "administrator-scope", 1, 1);
        dimensionUsers
                .createDelete()
                .where(
                        org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity
                                ::getUserId,
                        administrator.id())
                .and(
                        org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity
                                ::getDimensionId,
                        "ADMIN")
                .execute()
                .block(TIMEOUT);
        assertPage("FEEDBACK", administrator.token(), "administrator-scope", 0, 0);
        users.createUpdate()
                .set(
                        org.hswebframework.web.system.authorization.api.entity.UserEntity
                                ::getStatus,
                        (byte) 0)
                .where(
                        org.hswebframework.web.system.authorization.api.entity.UserEntity::getId,
                        owner.id())
                .execute()
                .block(TIMEOUT);
        call(HttpMethod.POST, ENDPOINT, owner.token(), body("COPY", "administrator-scope"), 401);
    }

    @Test
    void softDeleteAndRestoreTakeEffectWithoutReindexingAndOrphanSourcesNeverAppear() {
        Actor owner = actor(ACTIONS);
        Fixture f = fixture(owner.id(), "Retained");
        seed(f, "retained", 1);
        for (boolean project : List.of(false, true)) {
            if (project)
                projects.createUpdate()
                        .set(PrototypeProjectEntity::getDeletedTime, System.currentTimeMillis())
                        .where(PrototypeProjectEntity::getId, f.project())
                        .execute()
                        .block(TIMEOUT);
            else
                versions.createUpdate()
                        .set(PrototypeVersionEntity::getDeletedTime, System.currentTimeMillis())
                        .where(PrototypeVersionEntity::getId, f.version())
                        .execute()
                        .block(TIMEOUT);
            for (String type : List.of("COPY", "MATERIAL", "FEEDBACK"))
                assertPage(type, admin, "retained", 0, 0);
            if (project)
                projects.createUpdate()
                        .setNull(PrototypeProjectEntity::getDeletedTime)
                        .where(PrototypeProjectEntity::getId, f.project())
                        .execute()
                        .block(TIMEOUT);
            else
                versions.createUpdate()
                        .setNull(PrototypeVersionEntity::getDeletedTime)
                        .where(PrototypeVersionEntity::getId, f.version())
                        .execute()
                        .block(TIMEOUT);
            for (String type : List.of("COPY", "MATERIAL", "FEEDBACK"))
                assertPage(type, owner.token(), "retained", 1, 1);
        }
        revisions.deleteById(f.revision()).block(TIMEOUT);
        for (String type : List.of("COPY", "MATERIAL", "FEEDBACK"))
            assertPage(type, admin, "retained", 0, 0);
    }

    @Test
    void literalFieldsAndMetadataChangesAreVisibleWithoutSearchingUrlsOrDocumentBytes() {
        Actor owner = actor(ACTIONS);
        Fixture f = fixture(owner.id(), "Literals");
        seed(f, "Keep_100%中文<script>", 1);
        for (String type : List.of("COPY", "MATERIAL", "FEEDBACK")) {
            assertPage(type, owner.token(), "KEEP_100%中文<script>", 1, 1);
            assertPage(type, owner.token(), "_100%", 1, 1);
            assertPage(type, owner.token(), "Keep_100%中文<script>different", 0, 0);
        }
        assertPage("MATERIAL", owner.token(), "url-only-not-searchable", 0, 0);
        materials
                .createUpdate()
                .set(PrototypeRevisionMaterialEntity::getFileName, "renamed.pdf")
                .set(PrototypeRevisionMaterialEntity::getSourceNotes, "metadata-change")
                .where(PrototypeRevisionMaterialEntity::getRevisionId, f.revision())
                .execute()
                .block(TIMEOUT);
        var row = query("MATERIAL", owner.token(), "METADATA-CHANGE").path("data").get(0);
        assertEquals("sourceNotes", row.path("matchField").asText());
        assertEquals("renamed.pdf", row.path("title").asText());
        assertTrue(row.path("snippet").asText().contains("metadata-change"));
        materials
                .createDelete()
                .where(PrototypeRevisionMaterialEntity::getRevisionId, f.revision())
                .execute()
                .block(TIMEOUT);
        assertPage("MATERIAL", owner.token(), "metadata-change", 0, 0);
        copies.createUpdate()
                .set(PrototypeCopyEntryEntity::getBusinessTerm, "business-field")
                .set(PrototypeCopyEntryEntity::getNote, "note-field")
                .where(PrototypeCopyEntryEntity::getRevisionId, f.revision())
                .execute()
                .block(TIMEOUT);
        assertEquals(
                "businessTerm",
                query("COPY", owner.token(), "business-field")
                        .path("data")
                        .get(0)
                        .path("matchField")
                        .asText());
        assertEquals(
                "note",
                query("COPY", owner.token(), "note-field")
                        .path("data")
                        .get(0)
                        .path("matchField")
                        .asText());
        assertEquals(
                "pagePath",
                query("COPY", owner.token(), "nested-page")
                        .path("data")
                        .get(0)
                        .path("matchField")
                        .asText());
    }

    @Test
    void invalidInputFailsBeforeQueryAndOutOfRangePagesStayEmpty() {
        Actor owner = actor(ACTIONS);
        Fixture f = fixture(owner.id(), "Paging");
        seed(f, "pagination", 3);
        for (String type : List.of("COPY", "MATERIAL", "FEEDBACK")) {
            var input = body(type, "pagination");
            input.put("pageSize", 2);
            var first = page(owner.token(), input);
            assertEquals(3, first.path("total").asInt());
            assertEquals(2, first.path("data").size());
            input.put("pageIndex", 1);
            var last = page(owner.token(), input);
            assertEquals(1, last.path("data").size());
            assertNotEquals(
                    first.path("data").get(0).path("sourceId"),
                    last.path("data").get(0).path("sourceId"));
            input.put("pageIndex", 10000);
            var beyond = page(owner.token(), input);
            assertEquals(3, beyond.path("total").asInt());
            assertEquals(0, beyond.path("data").size());
            assertEquals(10000, beyond.path("pageIndex").asInt());
        }
        for (Map<String, Object> invalid :
                List.<Map<String, Object>>of(
                        Map.of("type", "UNKNOWN", "keyword", "ok"),
                        Map.of("type", "COPY", "keyword", " "),
                        Map.of("type", "COPY", "keyword", "x".repeat(101)),
                        Map.of("keyword", "ok"),
                        Map.of("type", "COPY", "keyword", "ok", "pageSize", 51),
                        Map.of("type", "COPY", "keyword", "ok", "pageIndex", -1),
                        Map.of("type", "COPY", "keyword", "ok", "revisionId", "../other"),
                        Map.of("type", "COPY", "keyword", "ok", "pageSize", "twenty")))
            call(HttpMethod.POST, ENDPOINT, owner.token(), invalid, 400);
    }

    @Test
    void representativeThousandsOfRowsStayDatabasePagedWithHiddenMatchesExcluded()
            throws Exception {
        Actor owner = actor(ACTIONS), hidden = actor(ACTIONS);
        List<PrototypeCopyEntryEntity> copyRows = new ArrayList<>();
        List<PrototypeRevisionMaterialEntity> materialRows = new ArrayList<>();
        List<PrototypeReviewMessageEntity> messageRows = new ArrayList<>();
        // 24 independent fixed revisions, 100 materials each: fixture respects the per-revision
        // cap.
        for (int scope = 0; scope < 24; scope++) {
            Fixture f = fixture(scope < 6 ? owner.id() : hidden.id(), "Scale " + scope);
            for (int i = 0; i < 100; i++) {
                copyRows.add(copy(f, "scale-needle", i));
                materialRows.add(material(f, "scale-needle", i));
                messageRows.add(message(f, "scale-needle", i));
            }
        }
        copies.insertBatch(copyRows).block(TIMEOUT);
        materials.insertBatch(materialRows).block(TIMEOUT);
        messages.insertBatch(messageRows).block(TIMEOUT);
        for (String type : List.of("COPY", "MATERIAL", "FEEDBACK")) {
            var input = body(type, "scale-needle");
            input.put("pageIndex", 5);
            input.put("pageSize", 20);
            Captured captured = capture(() -> page(owner.token(), input));
            assertEquals(600, captured.response().path("total").asInt());
            assertEquals(20, captured.response().path("data").size());
            String table =
                    switch (type) {
                        case "COPY" -> "p_copy_entry";
                        case "MATERIAL" -> "p_revision_material";
                        default -> "p_review_message";
                    };
            List<String> sourceQueries =
                    captured.sql().stream().filter(sql -> sql.contains(table)).toList();
            assertEquals(2, sourceQueries.size(), "exactly COUNT and paged data, no per-row SQL");
            assertEquals(1, sourceQueries.stream().filter(sql -> sql.contains("count(")).count());
            assertEquals(1, sourceQueries.stream().filter(sql -> sql.contains("limit")).count());
            String nativePage =
                    captured.nativeSql().stream()
                            .filter(
                                    sql ->
                                            sql.contains(table)
                                                    && sql.toLowerCase(Locale.ROOT)
                                                            .contains("limit"))
                            .findFirst()
                            .orElseThrow();
            String output = System.getProperty("prototype.search.plan-output");
            if (output != null) {
                var directory = java.nio.file.Path.of(output);
                java.nio.file.Files.createDirectories(directory);
                java.nio.file.Files.writeString(directory.resolve(type + ".sql"), nativePage);
            }
            System.out.println(
                    "Search representative evidence: type="
                            + type
                            + " sourceRows=2400 visible=600 hidden=1800 page=5 pageSize=20 httpMs="
                            + captured.millis());
        }
    }

    private Fixture fixture(String owner, String name) {
        String project = id(), version = id(), revision = id(), snapshot = id(), thread = id();
        var p = new PrototypeProjectEntity();
        p.setId(project);
        p.setName(name);
        p.setOwnerId(owner);
        p.setAccessEpoch(id());
        p.setCreatorId(owner);
        p.setModifierId(owner);
        p.setCreateTime(1L);
        p.setModifyTime(1L);
        projects.insert(p).block(TIMEOUT);
        var v = new PrototypeVersionEntity();
        v.setId(version);
        v.setProjectId(project);
        v.setVersionName("Version");
        v.setStoragePath("private-fixture/" + version);
        v.setPreviewKey(id());
        v.setEntryFile("index.html");
        v.setStatus("ready");
        v.setCreatorId(owner);
        v.setCreateTime(2L);
        v.setCurrentRevisionId(revision);
        versions.insert(v).block(TIMEOUT);
        var r = new PrototypeRevisionEntity();
        r.setId(revision);
        r.setProjectId(project);
        r.setVersionId(version);
        r.setVersionName("Version");
        r.setStoragePath("private-fixture/" + revision);
        r.setEntryFile("index.html");
        r.setManifestJson("{}");
        r.setManifestSha256("0".repeat(64));
        r.setFileCount(1);
        r.setTotalBytes(1L);
        r.setCreatorId(owner);
        r.setCreateTime(10L);
        revisions.insert(r).block(TIMEOUT);
        var s = new PrototypeCopySnapshotEntity();
        s.setId(snapshot);
        s.setProjectId(project);
        s.setVersionId(version);
        s.setRevisionId(revision);
        s.setPageId("page");
        s.setPagePath("nested-page/index.html");
        s.setManifestSha256("0".repeat(64));
        s.setFileSha256("0".repeat(64));
        s.setExtractorVersion(1);
        s.setExtractionState("READY");
        s.setStaticCount(0);
        s.setConfigVersion(0L);
        s.setExtractedAt(10L);
        snapshots.insert(s).block(TIMEOUT);
        var t = new PrototypeReviewThreadEntity();
        t.setId(thread);
        t.setProjectId(project);
        t.setVersionId(version);
        t.setRevisionId(revision);
        t.setShareId(id());
        t.setPageId("page");
        t.setPageName("Page");
        t.setPagePath("nested-page/index.html");
        t.setGuestId(id());
        t.setVerificationLevel("unverified");
        t.setAuthorName("Guest");
        t.setSummary("Thread summary");
        t.setState("open");
        t.setConfigVersion(0L);
        t.setCreateTime(10L);
        t.setPublicUpdatedAt(10L);
        threads.insert(t).block(TIMEOUT);
        return new Fixture(project, version, revision, snapshot, thread);
    }

    private void seed(Fixture f, String text, int count) {
        for (int i = 0; i < count; i++) {
            copies.insert(copy(f, text, i)).block(TIMEOUT);
            materials.insert(material(f, text, i)).block(TIMEOUT);
            messages.insert(message(f, text, i)).block(TIMEOUT);
        }
    }

    private PrototypeCopyEntryEntity copy(Fixture f, String text, int order) {
        var row = new PrototypeCopyEntryEntity();
        row.setId(id());
        row.setSnapshotId(f.snapshot());
        row.setProjectId(f.project());
        row.setVersionId(f.version());
        row.setRevisionId(f.revision());
        row.setPageId("page");
        row.setKind(order % 2);
        row.setSourceOrder(order);
        row.setText(text);
        row.setBusinessTerm("");
        row.setNote("");
        row.setRequirementUrl("");
        row.setOccurrenceCount(1);
        row.setOccurrencesJson("[]");
        row.setConfigVersion(0L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private PrototypeRevisionMaterialEntity material(Fixture f, String text, int order) {
        var row = new PrototypeRevisionMaterialEntity();
        row.setId(id());
        row.setProjectId(f.project());
        row.setVersionId(f.version());
        row.setRevisionId(f.revision());
        row.setMaterialType("document");
        row.setFileName(text);
        row.setMediaType("text/plain");
        row.setFileSize(1L);
        row.setSha256("0".repeat(64));
        row.setStoragePath("private-fixture/material");
        row.setSourceUrl("https://example.invalid/url-only-not-searchable");
        row.setConfigVersion(0L);
        row.setCreatedBy("fixture");
        row.setUpdatedBy("fixture");
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private PrototypeReviewMessageEntity message(Fixture f, String text, int order) {
        var row = new PrototypeReviewMessageEntity();
        row.setId(id());
        row.setProjectId(f.project());
        row.setVersionId(f.version());
        row.setRevisionId(f.revision());
        row.setThreadId(f.thread());
        row.setText(text);
        row.setAuthorType("member");
        row.setAuthorName("Member");
        row.setVisibility(order % 2 == 0 ? "internal" : "public");
        row.setCreateTime(100L);
        return row;
    }

    private void member(Fixture f, String user, String role) {
        var row = new PrototypeProjectMemberEntity();
        row.setId(id());
        row.setProjectId(f.project());
        row.setUserId(user);
        row.setRole(role);
        row.setCreateTime(1L);
        members.insert(row).block(TIMEOUT);
    }

    private void revoke(String role) {
        result(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        UUID.nameUUIDFromBytes(
                                        (role + ":prototype-hub-version")
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", ""),
                        "permission",
                        "prototype-hub-version",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        List.of(),
                        "state",
                        1));
    }

    private JsonNode query(String type, String token, String keyword) {
        return page(token, body(type, keyword));
    }

    private JsonNode page(String token, Object body) {
        return result(HttpMethod.POST, ENDPOINT, token, body);
    }

    private Map<String, Object> body(String type, String keyword) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("keyword", keyword);
        return body;
    }

    private void assertPage(String type, String token, String keyword, int total, int rows) {
        JsonNode response = query(type, token, keyword);
        assertEquals(total, response.path("total").asInt());
        assertEquals(rows, response.path("data").size());
    }

    private Captured capture(Supplier<JsonNode> operation) {
        Logger logger = (Logger) LoggerFactory.getLogger(PrototypeSearchService.Row.class);
        Level previous = logger.getLevel();
        boolean additive = logger.isAdditive();
        var events = new CopyOnWriteArrayList<ILoggingEvent>();
        var appender =
                new AppenderBase<ILoggingEvent>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        event.prepareForDeferredProcessing();
                        events.add(event);
                    }
                };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        long started = System.nanoTime();
        try {
            JsonNode response = operation.get();
            List<String> sql = new ArrayList<>(), nativeSql = new ArrayList<>();
            for (var event : events) {
                String text = event.getFormattedMessage();
                if (text.startsWith("==>  Preparing:") || text.startsWith("==>    Execute:"))
                    sql.add(
                            text.substring(text.indexOf(':') + 1)
                                    .strip()
                                    .replaceAll("\\s+", " ")
                                    .toLowerCase(Locale.ROOT));
                if (text.startsWith("==>     Native:"))
                    nativeSql.add(text.substring(text.indexOf(':') + 1).strip());
            }
            return new Captured(
                    response, sql, nativeSql, (System.nanoTime() - started) / 1_000_000);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    private record Captured(
            JsonNode response, List<String> sql, List<String> nativeSql, long millis) {}

    private record Fixture(
            String project, String version, String revision, String snapshot, String thread) {}

    private Actor actor(Map<String, List<String>> actions) {
        String role = id();
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Search test"));
        for (var entry : actions.entrySet()) {
            result(
                    HttpMethod.PATCH,
                    "/autz-setting",
                    admin,
                    Map.of(
                            "id",
                            UUID.nameUUIDFromBytes(
                                            (role + ":" + entry.getKey())
                                                    .getBytes(StandardCharsets.UTF_8))
                                    .toString()
                                    .replace("-", ""),
                            "permission",
                            entry.getKey(),
                            "dimensionType",
                            "role",
                            "dimensionTarget",
                            role,
                            "actions",
                            entry.getValue(),
                            "state",
                            1));
        }
        String username = "search-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Search test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(user));
        return new Actor(user, login(username), role);
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

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Actor(String id, String token, String role) {}
}
