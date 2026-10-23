package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.service.query.PrototypeScopeQuery;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.api.AfterAll;
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

/** 分页前的数据权限、字面筛选、稳定排序和数据库有界读取的真实 HTTP/PostgreSQL 回归。 */
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
class PrototypeHubPaginationIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();
    private static final String PROJECTS = "/prototype-hub/projects/_query";
    private static final String VERSIONS = "/prototype-hub/versions/_query";
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
        properties.add("spring.r2dbc.username", DATABASE::getUsername);
        properties.add("spring.r2dbc.password", DATABASE::getPassword);
        properties.add("spring.r2dbc.pool.max-size", () -> 4);
        properties.add("spring.r2dbc.pool.initial-size", () -> 1);
        properties.add("proto.admin.password", () -> PASSWORD);
        properties.add("prototype-hub.storage.root", FILES::toString);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired ReactiveRepository<PrototypeProjectEntity, String> projects;
    @Autowired ReactiveRepository<PrototypeVersionEntity, String> versions;
    @Autowired ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    @Autowired ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    @Autowired ReactiveSqlExecutor sqlExecutor;
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
    void pageAndTotalUseTheSameOwnerMemberAndAdministratorScope() {
        Actor owner = actor(ALL_ACTIONS);
        Actor editor = actor(ALL_ACTIONS);
        Actor viewer = actor(ALL_ACTIONS);
        Actor outsider = actor(ALL_ACTIONS);
        String first = project(owner.id(), "Visible one", 100).getId();
        String second = project(owner.id(), "Visible two", 200).getId();
        String hidden = project(outsider.id(), "Hidden", 300).getId();
        member(first, editor.id(), "editor");
        member(first, viewer.id(), "viewer");
        version(first, "Visible one", 100);
        version(second, "Visible two", 200);
        version(hidden, "Hidden", 300);
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            assertPage(endpoint, owner.token(), 2, 1);
            assertPage(endpoint, editor.token(), 1, 1);
            assertPage(endpoint, viewer.token(), 1, 1);
            assertPage(endpoint, outsider.token(), 1, 1);
            assertPage(endpoint, admin, 3, 1);
            assertEquals(
                    0,
                    page(endpoint, owner.token(), Map.of("keyword", "Hidden"))
                            .path("total")
                            .asInt());
            JsonNode editorPage = page(endpoint, editor.token(), Map.of());
            assertEquals(
                    first,
                    editorPage
                            .path("data")
                            .get(0)
                            .path(endpoint.equals(PROJECTS) ? "id" : "projectId")
                            .asText());
        }
        JsonNode viewerProject = page(PROJECTS, viewer.token(), Map.of()).path("data").get(0);
        assertEquals("viewer", viewerProject.path("role").asText());
        assertFalse(viewerProject.path("capabilities").path("edit").asBoolean());
        assertEquals(
                "owner",
                page(PROJECTS, owner.token(), Map.of()).path("data").get(0).path("role").asText());
        assertEquals(
                "admin", page(PROJECTS, admin, Map.of()).path("data").get(0).path("role").asText());
    }

    @Test
    void allJavaBlankOwnerFormsAreExcludedBeforeCountAndPageButRemainVisibleToAdmin() {
        Actor viewer = actor(ALL_ACTIONS);
        project(viewer.id(), "Visible", 1);
        List<String> blanks = new ArrayList<>();
        blanks.add(null);
        blanks.add("");
        IntStream.rangeClosed(0, Character.MAX_VALUE)
                .filter(Character::isWhitespace)
                .forEach(codePoint -> blanks.add(String.valueOf((char) codePoint)));
        for (String blank : blanks) {
            String id = project(blank, "Pending", 999).getId();
            member(id, viewer.id(), "viewer");
            version(id, "Pending", 999);
        }
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            JsonNode visible = page(endpoint, viewer.token(), Map.of("pageSize", 1));
            assertEquals(endpoint.equals(PROJECTS) ? 1 : 0, visible.path("total").asInt());
            assertEquals(endpoint.equals(PROJECTS) ? 1 : 0, visible.path("data").size());
            JsonNode all = page(endpoint, admin, Map.of("pageSize", 100));
            assertEquals(
                    blanks.size() + (endpoint.equals(PROJECTS) ? 1 : 0), all.path("total").asInt());
        }
    }

    @Test
    void explicitProjectScopeAndGenericQueryActionsCannotBeBypassedByPagination() {
        Actor owner = actor(ALL_ACTIONS);
        Actor outsider = actor(ALL_ACTIONS);
        Actor noVersionQuery = actor(Map.of("prototype-hub-project", List.of("query")));
        String project = project(owner.id(), "Private", 100).getId();
        version(project, "Private version", 100);
        member(project, noVersionQuery.id(), "viewer");
        call(HttpMethod.POST, VERSIONS + "?projectId=" + project, outsider.token(), Map.of(), 404);
        call(HttpMethod.POST, VERSIONS + "?projectId=missing", owner.token(), Map.of(), 404);
        call(
                HttpMethod.POST,
                VERSIONS + "?projectId=" + project,
                noVersionQuery.token(),
                Map.of(),
                403);
        call(HttpMethod.POST, PROJECTS, null, Map.of(), 401);
        assertPage(VERSIONS + "?projectId=" + project, owner.token(), 1, 1);
    }

    @Test
    void inactiveOwnerDoesNotDiscardStillValidMembersAndNonBreakingSpacesAreNotBlank() {
        Actor owner = actor(ALL_ACTIONS);
        Actor viewer = actor(ALL_ACTIONS);
        String first = project(owner.id(), "Disabled owner", 100).getId();
        member(first, viewer.id(), "viewer");
        version(first, "Disabled owner", 100);
        // Java isBlank does not include NBSP; existing access semantics remain authoritative.
        String second = project("\u00a0", "Non breaking", 100).getId();
        member(second, viewer.id(), "viewer");
        version(second, "Non breaking", 100);
        database.sql("update s_user set status=0 where id=:id")
                .bind("id", owner.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            assertPage(endpoint, viewer.token(), 2, 1);
        }
        assertTrue(
                page(PROJECTS, viewer.token(), Map.of())
                        .path("data")
                        .get(0)
                        .path("claimRequired")
                        .asBoolean());
    }

    @Test
    void sortingUsesIdAsStableTieBreakerAcrossPagesForBothFieldsAndDirections() {
        Actor owner = actor(ALL_ACTIONS);
        List<String> projectIds = new ArrayList<>();
        List<String> versionIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            var project = project(owner.id(), "Same", 1234);
            projectIds.add(project.getId());
            versionIds.add(version(project.getId(), "Same", 1234).getId());
        }
        projectIds.sort(Comparator.naturalOrder());
        versionIds.sort(Comparator.naturalOrder());
        for (String field : List.of("createTime", "name")) {
            for (String direction : List.of("asc", "desc")) {
                for (String endpoint : List.of(PROJECTS, VERSIONS)) {
                    List<String> ids = new ArrayList<>();
                    for (int page = 0; page < 3; page++) {
                        JsonNode response =
                                page(
                                        endpoint,
                                        owner.token(),
                                        Map.of(
                                                "pageIndex",
                                                page,
                                                "pageSize",
                                                2,
                                                "sortBy",
                                                field,
                                                "sortOrder",
                                                direction));
                        assertEquals(5, response.path("total").asInt());
                        response.path("data").forEach(row -> ids.add(row.path("id").asText()));
                    }
                    assertEquals(endpoint.equals(PROJECTS) ? projectIds : versionIds, ids);
                }
            }
        }
    }

    @Test
    void selectedSortFieldAndDirectionDeterminePageOrder() {
        Actor owner = actor(ALL_ACTIONS);
        String oldProject = project(owner.id(), "Zeta", 100).getId();
        String newProject = project(owner.id(), "Alpha", 200).getId();
        String oldVersion = version(oldProject, "Zeta", 100).getId();
        String newVersion = version(newProject, "Alpha", 200).getId();
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            String old = endpoint.equals(PROJECTS) ? oldProject : oldVersion;
            String latest = endpoint.equals(PROJECTS) ? newProject : newVersion;
            assertEquals(
                    List.of(old, latest),
                    page(
                                    endpoint,
                                    owner.token(),
                                    Map.of("sortBy", "createTime", "sortOrder", "asc"))
                            .path("data")
                            .findValuesAsText("id"));
            assertEquals(
                    List.of(latest, old),
                    page(
                                    endpoint,
                                    owner.token(),
                                    Map.of("sortBy", "createTime", "sortOrder", "desc"))
                            .path("data")
                            .findValuesAsText("id"));
            assertEquals(
                    List.of(latest, old),
                    page(endpoint, owner.token(), Map.of("sortBy", "name", "sortOrder", "asc"))
                            .path("data")
                            .findValuesAsText("id"));
            assertEquals(
                    List.of(old, latest),
                    page(endpoint, owner.token(), Map.of("sortBy", "name", "sortOrder", "desc"))
                            .path("data")
                            .findValuesAsText("id"));
        }
    }

    @Test
    void searchIsTrimmedCaseSensitiveLiteralContainsForChineseAndSqlWildcards() {
        Actor owner = actor(ALL_ACTIONS);
        String project = project(owner.id(), "alpha 中文 50% under_score slash\\value", 100).getId();
        version(project, "alpha 中文 50% under_score slash\\value", 100);
        String other =
                project(owner.id(), "ALPHA unrelated 500 underXscore slashvalue", 200).getId();
        version(other, "ALPHA unrelated 500 underXscore slashvalue", 200);
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            for (String literal : List.of("  中文  ", "%", "_", "\\", "alpha")) {
                JsonNode found = page(endpoint, owner.token(), Map.of("keyword", literal));
                assertEquals(1, found.path("total").asInt(), literal);
                assertEquals(1, found.path("data").size(), literal);
                assertEquals(
                        project,
                        found.path("data")
                                .get(0)
                                .path(endpoint.equals(PROJECTS) ? "id" : "projectId")
                                .asText());
            }
            assertEquals(
                    2,
                    page(endpoint, owner.token(), Map.of("keyword", " \t\n "))
                            .path("total")
                            .asInt());
            assertEquals(
                    0,
                    page(endpoint, owner.token(), Map.of("keyword", "' OR 1=1 --"))
                            .path("total")
                            .asInt());
        }
    }

    @Test
    void invalidPagingAndSortInputsAreRejectedInsteadOfExpandingTheQuery() {
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            for (Object invalid :
                    List.of(
                            Map.of("pageIndex", -1), Map.of("pageIndex", 10001),
                            Map.of("pageSize", 0), Map.of("pageSize", 101),
                            Map.of("sortBy", "id desc;drop table p_project"),
                                    Map.of("sortOrder", "sideways"),
                            Map.of("keyword", "x".repeat(129)), Map.of("pageIndex", "invalid"))) {
                call(HttpMethod.POST, endpoint, admin, invalid, 400);
            }
        }
        assertEquals(
                0,
                database.sql("select count(*) as count from p_project")
                        .map((row, metadata) -> row.get("count", Long.class))
                        .one()
                        .block(TIMEOUT));
    }

    @Test
    void defaultsAndOutOfRangePagesRetainActualTotalWithoutTrustingClientTotal() {
        Actor owner = actor(ALL_ACTIONS);
        String project = project(owner.id(), "Only", 100).getId();
        version(project, "Only", 100);
        for (String endpoint : List.of(PROJECTS, VERSIONS)) {
            JsonNode defaults = page(endpoint, owner.token(), Map.of());
            assertEquals(0, defaults.path("pageIndex").asInt());
            assertEquals(20, defaults.path("pageSize").asInt());
            JsonNode pastEnd =
                    page(
                            endpoint,
                            owner.token(),
                            Map.of(
                                    "pageIndex",
                                    10000,
                                    "pageSize",
                                    100,
                                    "total",
                                    999999,
                                    "rePaging",
                                    true));
            assertEquals(1, pastEnd.path("total").asInt());
            assertEquals(10000, pastEnd.path("pageIndex").asInt());
            assertEquals(100, pastEnd.path("pageSize").asInt());
            assertTrue(pastEnd.path("data").isEmpty());
        }
    }

    @Test
    void publishedArrayEndpointsRemainCompatible() {
        Actor owner = actor(ALL_ACTIONS);
        String first = project(owner.id(), "First", 100).getId();
        String second = project(owner.id(), "Second", 200).getId();
        version(first, "First", 100);
        version(second, "Second", 200);
        for (String endpoint : List.of("/prototype-hub/projects", "/prototype-hub/versions")) {
            JsonNode rows = result(HttpMethod.GET, endpoint, owner.token(), null);
            assertTrue(rows.isArray());
            assertEquals(2, rows.size());
            assertEquals(
                    second,
                    rows.get(0).path(endpoint.endsWith("projects") ? "id" : "projectId").asText());
        }
    }

    @Test
    void representativePagesAreLimitedInPostgresAndRevisionCountsOnlyReadPageIds()
            throws Exception {
        Actor owner = actor(ALL_ACTIONS);
        List<PrototypeProjectEntity> projectRows = new ArrayList<>();
        List<PrototypeVersionEntity> versionRows = new ArrayList<>();
        List<PrototypeRevisionEntity> revisionRows = new ArrayList<>();
        for (int index = 0; index < 125; index++) {
            var project = projectEntity(owner.id(), "Project " + index, index);
            var version = versionEntity(project.getId(), "Version " + index, index);
            projectRows.add(project);
            versionRows.add(version);
            for (int number = 0; number < 3; number++) {
                var revision = revisionEntity(version, number);
                if (number == 0) version.setCurrentRevisionId(revision.getId());
                revisionRows.add(revision);
            }
        }
        // Test-only legacy-sized seed bypasses creation quotas without changing deployment policy.
        projects.insertBatch(projectRows).block(TIMEOUT);
        versions.insertBatch(versionRows).block(TIMEOUT);
        revisions.insertBatch(revisionRows).block(TIMEOUT);
        Captured page =
                capture(
                        () ->
                                page(
                                        VERSIONS,
                                        owner.token(),
                                        Map.of("pageIndex", 2, "pageSize", 20)));
        assertEquals(125, page.response().path("total").asInt());
        assertEquals(20, page.response().path("data").size());
        page.response()
                .path("data")
                .forEach(row -> assertEquals(3, row.path("revisionCount").asInt()));
        List<String> versionSelects =
                page.statements().stream()
                        .filter(sql -> sql.contains("p_version") && sql.contains("limit"))
                        .toList();
        assertEquals(1, versionSelects.size());
        String versionSelect = versionSelects.get(0);
        assertTrue(versionSelect.contains("offset"), versionSelect);
        List<String> revisionSelects =
                page.statements().stream().filter(sql -> sql.contains("p_revision")).toList();
        assertEquals(1, revisionSelects.size(), "Revision counts must use one grouped page query");
        String revisionSelect = revisionSelects.get(0);
        assertTrue(revisionSelect.contains("group by"), revisionSelect);
        assertTrue(
                Pattern.compile("\\bcount\\s*\\(\\s*1\\s*\\)").matcher(revisionSelect).find(),
                revisionSelect);
        // ORM 可以输出 IN(...) 或 IN (...)；校验真实括号结构与二十个绑定槽，而非空格风格。
        var pageIds = Pattern.compile("\\bin\\s*\\(([^)]*)\\)").matcher(revisionSelect);
        assertTrue(pageIds.find(), revisionSelect);
        List<String> slots = List.of(pageIds.group(1).strip().split("\\s*,\\s*"));
        assertEquals(IntStream.rangeClosed(1, 20).mapToObj(index -> "$" + index).toList(), slots);
        for (String sql : page.statements()) {
            assertFalse(sql.contains("manifest_json"), sql);
            assertFalse(sql.contains("manifest_sha256"), sql);
            assertFalse(sql.contains("p_share"), sql);
        }
        String countParameters =
                page.parameters().stream()
                        .filter(
                                value ->
                                        value.contains(
                                                page.response()
                                                        .path("data")
                                                        .get(0)
                                                        .path("id")
                                                        .asText()))
                        .findFirst()
                        .orElseThrow();
        for (JsonNode row : page.response().path("data"))
            assertTrue(countParameters.contains(row.path("id").asText()));
        for (var version : versionRows) {
            if (page.response().path("data").findValuesAsText("id").contains(version.getId()))
                continue;
            assertFalse(countParameters.contains(version.getId()));
        }
        JsonNode plan =
                mapper.readTree(
                        database.sql(
                                        "EXPLAIN (ANALYZE, FORMAT JSON) "
                                                + page.nativeSql().stream()
                                                        .filter(
                                                                sql ->
                                                                        sql.contains("p_version")
                                                                                && sql.toLowerCase(
                                                                                                Locale
                                                                                                        .ROOT)
                                                                                        .contains(
                                                                                                "limit"))
                                                        .findFirst()
                                                        .orElseThrow())
                                .map((row, metadata) -> row.get(0, String.class))
                                .one()
                                .block(TIMEOUT));
        assertEquals("Limit", plan.get(0).path("Plan").path("Node Type").asText());
        assertEquals(20, plan.get(0).path("Plan").path("Actual Rows").asInt());
        Captured projectPage =
                capture(
                        () ->
                                page(
                                        PROJECTS,
                                        owner.token(),
                                        Map.of("pageIndex", 2, "pageSize", 20)));
        assertEquals(125, projectPage.response().path("total").asInt());
        assertEquals(20, projectPage.response().path("data").size());
        assertEquals(
                1,
                projectPage.statements().stream()
                        .filter(sql -> sql.contains("p_project") && sql.contains("limit"))
                        .count());
        System.out.println(
                "Pagination representative evidence: projects=125 versions=125 revisions=375"
                        + " pageSize=20 projectMs="
                        + projectPage.millis()
                        + " versionMs="
                        + page.millis()
                        + " postgresPageRows=20 groupedRevisionQueries=1 manifestReads=0");
    }

    private Captured capture(Supplier<JsonNode> operation) {
        var executor = (R2dbcReactiveSqlExecutor) sqlExecutor;
        var previous = executor.getLogger();
        Logger logger = (Logger) LoggerFactory.getLogger(getClass().getName() + ".SQL");
        // QueryHelper 将映射类型的 logger 写入 Reactor 上下文，优先于执行器默认 logger。
        // 同时观察这两条官方日志路径，避免只抓到无上下文的修订聚合而遗漏真实分页 SQL。
        List<LoggerState> loggers =
                List.of(
                                logger,
                                (Logger)
                                        LoggerFactory.getLogger(
                                                PrototypeScopeQuery.ProjectScope.class),
                                (Logger)
                                        LoggerFactory.getLogger(
                                                PrototypeScopeQuery.VersionScope.class))
                        .stream()
                        .map(item -> new LoggerState(item, item.getLevel(), item.isAdditive()))
                        .toList();
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
        for (var state : loggers) {
            state.logger().addAppender(appender);
            state.logger().setLevel(Level.DEBUG);
            state.logger().setAdditive(false);
        }
        executor.setLogger(logger);
        long started = System.nanoTime();
        try {
            JsonNode response = operation.get();
            List<String> statements = new ArrayList<>(),
                    nativeSql = new ArrayList<>(),
                    parameters = new ArrayList<>();
            for (var event : events) {
                String text = event.getFormattedMessage();
                if (text.startsWith("==>  Preparing:") || text.startsWith("==>    Execute:"))
                    statements.add(
                            text.substring(text.indexOf(':') + 1)
                                    .strip()
                                    .replaceAll("\\s+", " ")
                                    .toLowerCase(Locale.ROOT));
                if (text.startsWith("==>     Native:"))
                    nativeSql.add(text.substring(text.indexOf(':') + 1).strip());
                if (text.startsWith("==> Parameters:")) parameters.add(text);
            }
            return new Captured(
                    response,
                    statements,
                    nativeSql,
                    parameters,
                    (System.nanoTime() - started) / 1_000_000);
        } finally {
            executor.setLogger(previous);
            for (var state : loggers) {
                state.logger().detachAppender(appender);
                state.logger().setLevel(state.level());
                state.logger().setAdditive(state.additive());
            }
            appender.stop();
        }
    }

    private record LoggerState(Logger logger, Level level, boolean additive) {}

    private record Captured(
            JsonNode response,
            List<String> statements,
            List<String> nativeSql,
            List<String> parameters,
            long millis) {}

    private void assertPage(String endpoint, String token, int total, int rows) {
        JsonNode page = page(endpoint, token, Map.of("pageSize", 1));
        assertEquals(total, page.path("total").asInt(), endpoint);
        assertEquals(rows, page.path("data").size(), endpoint);
    }

    private JsonNode page(String endpoint, String token, Object body) {
        return result(HttpMethod.POST, endpoint, token, body);
    }

    private PrototypeProjectEntity project(String owner, String name, long created) {
        var entity = projectEntity(owner, name, created);
        projects.insert(entity).block(TIMEOUT);
        return entity;
    }

    private PrototypeProjectEntity projectEntity(String owner, String name, long created) {
        var entity = new PrototypeProjectEntity();
        entity.setId(id());
        entity.setName(name);
        entity.setOwnerId(owner);
        entity.setAccessEpoch(id());
        entity.setCreatorId(owner == null || owner.isBlank() ? "legacy" : owner);
        entity.setModifierId("fixture");
        entity.setCreateTime(created);
        entity.setModifyTime(created);
        return entity;
    }

    private PrototypeVersionEntity version(String project, String name, long created) {
        var entity = versionEntity(project, name, created);
        versions.insert(entity).block(TIMEOUT);
        return entity;
    }

    private PrototypeVersionEntity versionEntity(String project, String name, long created) {
        var entity = new PrototypeVersionEntity();
        entity.setId(id());
        entity.setProjectId(project);
        entity.setVersionName(name);
        entity.setStoragePath("fixture/" + entity.getId());
        entity.setEntryFile("index.html");
        entity.setPreviewKey(id());
        entity.setStatus("ready");
        entity.setCreatorId("fixture");
        entity.setCreateTime(created);
        return entity;
    }

    private PrototypeRevisionEntity revisionEntity(PrototypeVersionEntity version, int number) {
        var entity = new PrototypeRevisionEntity();
        entity.setId(id());
        entity.setProjectId(version.getProjectId());
        entity.setVersionId(version.getId());
        entity.setVersionName(version.getVersionName());
        entity.setStoragePath("fixture/" + entity.getId());
        entity.setEntryFile("index.html");
        entity.setManifestJson("{\"largeFixture\":\"" + "x".repeat(16384) + "\"}");
        entity.setManifestSha256("0".repeat(64));
        entity.setFileCount(1);
        entity.setTotalBytes(16384L);
        entity.setCreatorId("fixture");
        entity.setCreateTime((long) number);
        return entity;
    }

    private void member(String project, String user, String role) {
        var entity = new PrototypeProjectMemberEntity();
        entity.setId(id());
        entity.setProjectId(project);
        entity.setUserId(user);
        entity.setRole(role);
        entity.setCreateTime(1L);
        members.insert(entity).block(TIMEOUT);
    }

    private Actor actor(Map<String, List<String>> actions) {
        String role = id();
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Pagination test"));
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
        String username = "page-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Pagination test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(user));
        return new Actor(user, login(username));
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

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-pagination-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Actor(String id, String token) {}
}
