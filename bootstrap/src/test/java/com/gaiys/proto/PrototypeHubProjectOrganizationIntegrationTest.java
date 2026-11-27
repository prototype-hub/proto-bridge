package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.*;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Real HTTP/PostgreSQL evidence for shared metadata, private preferences and scoped grouped
 * pagination.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml", "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
                    "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000"
        })
@Testcontainers
class PrototypeHubProjectOrganizationIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final java.nio.file.Path FILES = temporaryStorage();
    private static final String PROJECTS = "/prototype-hub/projects";

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry p) {
        p.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        p.add("spring.r2dbc.username", DATABASE::getUsername);
        p.add("spring.r2dbc.password", DATABASE::getPassword);
        p.add("proto.admin.password", () -> PASSWORD);
        p.add("prototype-hub.storage.root", FILES::toString);
        p.add("spring.r2dbc.pool.max-size", () -> 6);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ReactiveRepository<PrototypeProjectEntity, String> projects;
    @Autowired ReactiveRepository<PrototypeProjectTagEntity, String> tags;
    @Autowired ReactiveRepository<PrototypeProjectFavoriteEntity, String> favorites;
    @Autowired PrototypeRetentionService retention;
    @Autowired com.gaiys.proto.prototypehub.service.query.PrototypeScopeQuery scope;
    private String admin;

    @BeforeEach
    void reset() {
        PrototypeHubTestData.clear(database);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
    }

    @AfterAll
    static void removeFiles() throws Exception {
        com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor.deleteRecursively(FILES);
    }

    @Test
    void archiveKeepsOrdinaryWritesExistingSharePreviewQuotaAndStatistics() throws Exception {
        String id = create(admin).path("id").asText();
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("index.html"));
            zip.write(
                    "<!doctype html><html><body>Organization archive fixture</body></html>"
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var parts = new org.springframework.http.client.MultipartBodyBuilder();
        parts.part("versionName", "Archive version");
        parts.part("file", new org.springframework.core.io.ByteArrayResource(bytes.toByteArray()))
                .filename("archive.zip");
        JsonNode version =
                client.post()
                        .uri(PROJECTS + "/" + id + "/versions")
                        .header("X-Access-Token", admin)
                        .body(
                                org.springframework.web.reactive.function.BodyInserters
                                        .fromMultipartData(parts.build()))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        JsonNode share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                        admin,
                        Map.of());
        String publicPath = "/prototype-hub/public/shares/" + share.path("token").asText();
        JsonNode grant = result(HttpMethod.GET, publicPath, null, null);
        JsonNode usage = result(HttpMethod.GET, PROJECTS + "/_usage", admin, null).path("projects");
        JsonNode statistics =
                result(HttpMethod.GET, "/prototype-hub/statistics?days=7", admin, null);
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                admin,
                organization(0, "Archived", true, "archive", "preserved"));
        assertEquals(0, page(admin, Map.of("archived", false)).path("total").asInt());
        assertEquals(1, page(admin, Map.of("archived", true)).path("total").asInt());
        assertEquals(
                grant.path("accessKey"),
                result(HttpMethod.GET, publicPath, null, null).path("accessKey"));
        result(
                HttpMethod.GET,
                publicPath + "/" + grant.path("accessKey").asText() + "/pages",
                null,
                null);
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version.path("id").asText() + "/preview-session",
                admin,
                null);
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id,
                admin,
                Map.of("name", "Still editable", "cover", "/legacy.png"));
        assertEquals(
                usage, result(HttpMethod.GET, PROJECTS + "/_usage", admin, null).path("projects"));
        JsonNode after = result(HttpMethod.GET, "/prototype-hub/statistics?days=7", admin, null);
        for (String field : List.of("projectCount", "versionCount", "shareCount"))
            assertEquals(statistics.path(field), after.path(field));
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                admin,
                organization(1, "Archived", false, "unarchive", "preserved"));
        assertEquals(1, page(admin, Map.of("archived", false)).path("total").asInt());
    }

    @Test
    void rolesIntersectGenericActionsAndFavoritesArePrivateIncludingAdministrator() {
        Actor owner = actor(List.of("query", "save", "delete"));
        Actor viewer = actor(List.of("query"));
        Actor saveOnly = actor(List.of("save"));
        Actor outsider = actor(List.of("query", "save"));
        String id = create(owner.token()).path("id").asText();
        member(id, viewer, "viewer");
        member(id, saveOnly, "editor");
        JsonNode updated =
                result(
                        HttpMethod.PUT,
                        PROJECTS + "/" + id + "/_organization",
                        saveOnly.token(),
                        organization(0, "shared", false, "o1", " b ", "a", "a", " "));
        assertEquals("shared", updated.path("groupName").asText());
        assertEquals(List.of("a", "b"), strings(updated.path("tags")));
        assertEquals(1, updated.path("organizationVersion").asLong());
        call(HttpMethod.POST, PROJECTS + "/_query", saveOnly.token(), Map.of(), 403);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                viewer.token(),
                organization(1, "bad", false, "denied"),
                403);
        JsonNode fav =
                result(
                        HttpMethod.PUT,
                        PROJECTS + "/" + id + "/_favorite",
                        viewer.token(),
                        favorite(0, true, "f1"));
        assertTrue(fav.path("favorite").asBoolean());
        assertEquals(1, fav.path("favoriteVersion").asLong());
        for (String token : List.of(owner.token(), admin))
            assertFalse(
                    result(HttpMethod.GET, PROJECTS + "/" + id, token, null)
                            .path("favorite")
                            .asBoolean());
        assertEquals(1, page(viewer.token(), Map.of("favoriteOnly", true)).path("total").asInt());
        assertEquals(0, page(admin, Map.of("favoriteOnly", true)).path("total").asInt());
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                saveOnly.token(),
                favorite(0, true, "save-only"),
                403);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                outsider.token(),
                favorite(0, true, "outsider"),
                404);
        result(HttpMethod.DELETE, PROJECTS + "/" + id + "/members/" + viewer.id(), admin, null);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                viewer.token(),
                favorite(0, true, "f1"),
                404);
        assertEquals(0, page(viewer.token(), Map.of("favoriteOnly", true)).path("total").asInt());
        database.sql("UPDATE s_user SET status=0 WHERE id=:id")
                .bind("id", viewer.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                viewer.token(),
                favorite(1, false, "disabled"),
                403);
        call(
                HttpMethod.POST,
                PROJECTS + "/_organization/options",
                viewer.token(),
                Map.of("kind", "group"),
                403);
    }

    @Test
    void originalKeysReturnCurrentStateAndCasHasOneConcurrentWinner() {
        Actor owner = actor(List.of("query", "save", "delete"));
        String id = create(owner.token()).path("id").asText();
        var first = organization(0, "first", true, "race-a", "one");
        var other = organization(0, "second", false, "race-b", "two");
        var responses =
                Flux.merge(
                                Mono.fromCallable(
                                                () ->
                                                        raw(
                                                                HttpMethod.PUT,
                                                                PROJECTS
                                                                        + "/"
                                                                        + id
                                                                        + "/_organization",
                                                                owner.token(),
                                                                first))
                                        .subscribeOn(Schedulers.boundedElastic()),
                                Mono.fromCallable(
                                                () ->
                                                        raw(
                                                                HttpMethod.PUT,
                                                                PROJECTS
                                                                        + "/"
                                                                        + id
                                                                        + "/_organization",
                                                                owner.token(),
                                                                other))
                                        .subscribeOn(Schedulers.boundedElastic()))
                        .collectList()
                        .block(TIMEOUT);
        assertEquals(List.of(200, 409), responses.stream().map(Reply::status).sorted().toList());
        var winning =
                responses.stream()
                        .filter(r -> r.status() == 200)
                        .findFirst()
                        .orElseThrow()
                        .body()
                        .path("result");
        var original = winning.path("groupName").asText().equals("first") ? first : other;
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                owner.token(),
                organization(1, "latest", false, "next", "latest"));
        JsonNode replay =
                result(
                        HttpMethod.PUT,
                        PROJECTS + "/" + id + "/_organization",
                        owner.token(),
                        original);
        assertEquals("latest", replay.path("groupName").asText());
        assertEquals(2, replay.path("organizationVersion").asLong());
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                owner.token(),
                organization(0, "different", false, (String) original.get("clientRequestId")),
                409);
        long modified = replay.path("updatedAt").asLong();
        var trueRequest = favorite(0, true, "favorite-original");
        result(HttpMethod.PUT, PROJECTS + "/" + id + "/_favorite", owner.token(), trueRequest);
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                owner.token(),
                favorite(1, false, "favorite-off"));
        JsonNode saved =
                result(
                        HttpMethod.PUT,
                        PROJECTS + "/" + id + "/_favorite",
                        owner.token(),
                        trueRequest);
        assertFalse(saved.path("favorite").asBoolean());
        assertEquals(2, saved.path("favoriteVersion").asLong());
        assertEquals(modified, saved.path("updatedAt").asLong());
        assertEquals(2, saved.path("organizationVersion").asLong());
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                owner.token(),
                favorite(0, true, "stale-new"),
                409);
        assertEquals(1, favorites.createQuery().count().block(TIMEOUT));
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id,
                owner.token(),
                Map.of("name", "Cover update", "cover", "/existing.png"));
        JsonNode unchanged = result(HttpMethod.GET, PROJECTS + "/" + id, owner.token(), null);
        assertEquals("latest", unchanged.path("groupName").asText());
        assertEquals(List.of("latest"), strings(unchanged.path("tags")));
        assertEquals("/existing.png", unchanged.path("cover").asText());
        database.sql("UPDATE p_project SET organization_version=9007199254740991 WHERE id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                owner.token(),
                organization(9007199254740991L, "overflow", false, "max"),
                409);
        database.sql(
                        "UPDATE p_project_favorite SET config_version=9007199254740991 WHERE project_id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                owner.token(),
                favorite(9007199254740991L, true, "max"),
                409);
        assertEquals(
                9007199254740991L,
                result(
                                HttpMethod.PUT,
                                PROJECTS + "/" + id + "/_favorite",
                                owner.token(),
                                trueRequest)
                        .path("favoriteVersion")
                        .asLong());
    }

    @Test
    void groupedOptionsAndCombinedFiltersStayScopedCompleteAndStableBeyondTwoHundred() {
        Actor owner = actor(List.of("query", "save"));
        Actor hidden = actor(List.of("query", "save"));
        var rows =
                IntStream.range(0, 205)
                        .mapToObj(i -> entity(owner.id(), String.format("Group %03d", i), i))
                        .toList();
        projects.insertBatch(rows).block(TIMEOUT);
        var labels = new ArrayList<PrototypeProjectTagEntity>();
        for (int i = 0; i < rows.size(); i++) {
            labels.add(tag(rows.get(i).getId(), String.format("Tag %03d", i)));
            labels.add(tag(rows.get(i).getId(), "common"));
        }
        tags.insertBatch(labels).block(TIMEOUT);
        var secret = entity(hidden.id(), "Forbidden", 999);
        projects.insert(secret).block(TIMEOUT);
        tags.insert(tag(secret.getId(), "Forbidden")).block(TIMEOUT);
        var one = rows.get(3);
        var saved = new PrototypeProjectFavoriteEntity();
        saved.setId(key());
        saved.setProjectId(one.getId());
        saved.setUserId(owner.id());
        saved.setFavorite(true);
        saved.setConfigVersion(1L);
        saved.setCreatedAt(1L);
        saved.setUpdatedAt(1L);
        favorites.insert(saved).block(TIMEOUT);
        var request =
                new com.gaiys.proto.prototypehub.web.request.ProjectOrganizationRequests.Options();
        request.setKind("group");
        request.setPageIndex(10);
        request.setPageSize(20);
        assertEquals(
                205,
                scope.organizationOptions(owner.id(), false, request).block(TIMEOUT).getTotal());
        JsonNode options = options(owner.token(), "group", Map.of("pageIndex", 10, "pageSize", 20));
        assertEquals(205, options.path("total").asInt());
        assertEquals(5, options.path("data").size());
        assertEquals("Group 200", options.path("data").get(0).asText());
        request.setKind("tag");
        request.setPageIndex(0);
        assertEquals(
                206,
                scope.organizationOptions(owner.id(), false, request).block(TIMEOUT).getTotal());
        assertEquals(206, options(owner.token(), "tag", Map.of()).path("total").asInt());
        request.setKind("group");
        request.setKeyword("Forbidden");
        assertEquals(
                0, scope.organizationOptions(owner.id(), false, request).block(TIMEOUT).getTotal());
        assertEquals(
                0,
                options(owner.token(), "group", Map.of("keyword", "Forbidden"))
                        .path("total")
                        .asInt());
        assertEquals(
                0,
                options(owner.token(), "group", Map.of("keyword", "group")).path("total").asInt());
        assertEquals(
                5,
                options(owner.token(), "tag", Map.of("keyword", "Tag 20")).path("total").asInt());
        assertEquals(
                205,
                options(owner.token(), "group", Map.of("pageIndex", 10000)).path("total").asInt());
        assertTrue(
                options(owner.token(), "group", Map.of("pageIndex", 10000)).path("data").isEmpty());
        JsonNode combined =
                page(
                        owner.token(),
                        Map.of(
                                "archived",
                                true,
                                "groupName",
                                "Group 003",
                                "tag",
                                "common",
                                "favoriteOnly",
                                true,
                                "keyword",
                                "Project",
                                "pageSize",
                                1));
        assertEquals(1, combined.path("total").asInt());
        assertEquals(one.getId(), combined.path("data").get(0).path("id").asText());
        assertEquals(205, page(owner.token(), Map.of("tag", "common")).path("total").asInt());
        assertEquals(205, result(HttpMethod.GET, PROJECTS, owner.token(), null).size());
        assertEquals(205, page(owner.token(), Map.of()).path("total").asInt());
        assertEquals(103, page(owner.token(), Map.of("archived", false)).path("total").asInt());
        assertEquals(0, page(owner.token(), Map.of("groupName", "")).path("total").asInt());
        assertEquals(
                205,
                page(owner.token(), Map.of("pageIndex", 10000, "total", 1, "rePaging", true))
                        .path("total")
                        .asInt());
        // The representative plan observes actual grouped SQL work on the real database, not Java
        // truncation.
        var plan =
                database.sql(
                                "EXPLAIN (ANALYZE, FORMAT TEXT) SELECT group_name FROM p_project WHERE owner_id=:owner AND deleted_time IS NULL GROUP BY group_name ORDER BY group_name LIMIT 20 OFFSET 200")
                        .bind("owner", owner.id())
                        .fetch()
                        .all()
                        .collectList()
                        .block(TIMEOUT);
        assertTrue(plan.stream().anyMatch(row -> row.toString().contains("actual")));
        assertTrue(plan.stream().anyMatch(row -> row.toString().contains("Limit")));
        assertTrue(
                plan.stream()
                        .anyMatch(
                                row ->
                                        row.toString().contains("Group")
                                                || row.toString().contains("Unique")));
    }

    @Test
    void strictInputsAndVersionRecycleContractsRejectWrongOrInjectedFields() {
        Actor owner = actor(List.of("query", "save"));
        String id = create(owner.token()).path("id").asText();
        var valid = organization(0, " group ", false, "strict", " a ", "a", " ");
        for (var change :
                List.of(
                        Map.of("archived", "true"),
                        Map.of("expectedVersion", "0"),
                        Map.of("expectedVersion", 0.5),
                        Map.of("tags", List.of(1)),
                        Map.of("tags", Collections.nCopies(11, "a")),
                        Map.of("groupName", "bad\u0000"),
                        Map.of("actor", "other"))) {
            var invalid = new HashMap<String, Object>(valid);
            invalid.putAll(change);
            call(
                    HttpMethod.PUT,
                    PROJECTS + "/" + id + "/_organization",
                    owner.token(),
                    invalid,
                    400);
        }
        call(
                HttpMethod.POST,
                PROJECTS + "/_query",
                owner.token(),
                Map.of("archived", "false"),
                400);
        call(HttpMethod.POST, PROJECTS + "/_query", owner.token(), Map.of("tag", " "), 400);
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/_query",
                admin,
                Map.of("archived", false),
                400);
        call(
                HttpMethod.POST,
                PROJECTS + "/_deleted/_query",
                admin,
                Map.of("favoriteOnly", true),
                400);
        JsonNode normalized =
                result(
                        HttpMethod.PUT,
                        PROJECTS + "/" + id + "/_organization",
                        owner.token(),
                        valid);
        assertEquals("group", normalized.path("groupName").asText());
        assertEquals(List.of("a"), strings(normalized.path("tags")));
        assertEquals(
                "",
                result(
                                HttpMethod.PUT,
                                PROJECTS + "/" + id + "/_organization",
                                owner.token(),
                                organization(1, "\u00a0\ufeff\u001c", false, "clear"))
                        .path("groupName")
                        .asText());
    }

    @Test
    void softRestorePreservesOrganizationAndPermanentGraphAndAccountDeletionCleanRelations() {
        Actor owner = actor(List.of("query", "save", "delete"));
        Actor viewer = actor(List.of("query"));
        String id = create(owner.token()).path("id").asText();
        member(id, viewer, "viewer");
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_organization",
                owner.token(),
                organization(0, "Retained", true, "retained", "keep"));
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                viewer.token(),
                favorite(0, true, "personal"));
        result(HttpMethod.DELETE, PROJECTS + "/" + id, owner.token(), null);
        call(
                HttpMethod.PUT,
                PROJECTS + "/" + id + "/_favorite",
                viewer.token(),
                favorite(0, true, "personal"),
                404);
        result(HttpMethod.POST, PROJECTS + "/" + id + "/_restore", owner.token(), null);
        JsonNode restored = result(HttpMethod.GET, PROJECTS + "/" + id, viewer.token(), null);
        assertTrue(restored.path("favorite").asBoolean());
        assertTrue(restored.path("archivedTime").isNumber());
        assertEquals(List.of("keep"), strings(restored.path("tags")));
        result(HttpMethod.DELETE, "/user/" + viewer.id(), admin, null);
        assertEquals(0, favorites.createQuery().count().block(TIMEOUT));
        assertEquals(
                "Retained",
                result(HttpMethod.GET, PROJECTS + "/" + id, owner.token(), null)
                        .path("groupName")
                        .asText());
        result(HttpMethod.DELETE, PROJECTS + "/" + id, owner.token(), null);
        database.sql("UPDATE p_project SET deleted_time=1 WHERE id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeProject(id).block(TIMEOUT));
        assertFalse(retention.purgeProject(id).block(TIMEOUT));
        assertEquals(0, tags.createQuery().count().block(TIMEOUT));
        assertEquals(
                0,
                database.sql("SELECT count(*) AS n FROM p_creation_receipt WHERE resource_id=:id")
                        .bind("id", id)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block(TIMEOUT));
    }

    @Test
    void concurrentAccountDeletionCannotLeaveANewFavoriteBinding() {
        Actor owner = actor(List.of("query", "save"));
        Actor viewer = actor(List.of("query"));
        String id = create(owner.token()).path("id").asText();
        member(id, viewer, "viewer");
        var results =
                Flux.merge(
                                Mono.fromCallable(
                                                () ->
                                                        raw(
                                                                HttpMethod.PUT,
                                                                PROJECTS + "/" + id + "/_favorite",
                                                                viewer.token(),
                                                                favorite(0, true, "race")))
                                        .subscribeOn(Schedulers.boundedElastic()),
                                Mono.fromCallable(
                                                () ->
                                                        raw(
                                                                HttpMethod.DELETE,
                                                                "/user/" + viewer.id(),
                                                                admin,
                                                                null))
                                        .subscribeOn(Schedulers.boundedElastic()))
                        .collectList()
                        .block(TIMEOUT);
        assertTrue(
                results.stream()
                        .allMatch(reply -> List.of(200, 401, 403, 404).contains(reply.status())));
        assertEquals(
                0,
                favorites
                        .createQuery()
                        .where(PrototypeProjectFavoriteEntity::getUserId, viewer.id())
                        .count()
                        .block(TIMEOUT));
        assertEquals(
                id,
                result(HttpMethod.GET, PROJECTS + "/" + id, owner.token(), null)
                        .path("id")
                        .asText());
    }

    private JsonNode create(String token) {
        return result(
                HttpMethod.POST,
                PROJECTS,
                token,
                Map.of("name", "Project", "cover", "/legacy.png"));
    }

    private JsonNode page(String token, Object body) {
        return result(HttpMethod.POST, PROJECTS + "/_query", token, body);
    }

    private JsonNode options(String token, String kind, Map<String, Object> values) {
        var input = new HashMap<String, Object>(values);
        input.put("kind", kind);
        return result(HttpMethod.POST, PROJECTS + "/_organization/options", token, input);
    }

    private Map<String, Object> organization(
            long version, String group, boolean archived, String request, String... labels) {
        var map = new HashMap<String, Object>();
        map.put("expectedVersion", version);
        map.put("groupName", group);
        map.put("tags", List.of(labels));
        map.put("archived", archived);
        map.put("clientRequestId", request);
        return map;
    }

    private Map<String, Object> favorite(long version, boolean value, String request) {
        return Map.of("favorite", value, "expectedVersion", version, "clientRequestId", request);
    }

    private PrototypeProjectEntity entity(String owner, String group, int i) {
        var row = new PrototypeProjectEntity();
        row.setId(key());
        row.setOwnerId(owner);
        row.setCreatorId(owner);
        row.setModifierId(owner);
        row.setName("Project " + i);
        row.setCreateTime(1L);
        row.setModifyTime(1L);
        row.setAccessEpoch(key());
        row.setGroupName(group);
        row.setArchivedTime(i % 2 == 1 ? 1L : null);
        return row;
    }

    private PrototypeProjectTagEntity tag(String project, String value) {
        var row = new PrototypeProjectTagEntity();
        row.setId(key());
        row.setProjectId(project);
        row.setTagName(value);
        return row;
    }

    private void member(String project, Actor actor, String role) {
        result(
                HttpMethod.PUT,
                PROJECTS + "/" + project + "/members/" + actor.id(),
                admin,
                Map.of("role", role));
    }

    private Actor actor(List<String> actions) {
        String role = key(), username = "organization-" + role;
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Organization test"));
        result(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        key(),
                        "permission",
                        "prototype-hub-project",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        actions,
                        "state",
                        1));
        String id =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Organization test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(id));
        return new Actor(id, login(username));
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

    private JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var reply = raw(method, path, token, body);
        assertEquals(status, reply.status(), reply.body().toString());
        return reply.body();
    }

    private Reply raw(HttpMethod method, String path, String token, Object body) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.bodyValue(body);
        var reply = request.exchange().expectBody(JsonNode.class).returnResult();
        assertNotNull(reply.getResponseBody());
        return new Reply(reply.getStatus().value(), reply.getResponseBody());
    }

    private static List<String> strings(JsonNode value) {
        var result = new ArrayList<String>();
        value.forEach(item -> result.add(item.asText()));
        return result;
    }

    private static java.nio.file.Path temporaryStorage() {
        try {
            return java.nio.file.Files.createTempDirectory("prototype-hub-organization-");
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static String key() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Actor(String id, String token) {}

    private record Reply(int status, JsonNode body) {}
}
