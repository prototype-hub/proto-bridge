package com.gaiys.proto.system.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.ProtoBridgeApplication;
import com.gaiys.proto.system.configuration.initializer.MenuStartupInitializer;
import com.gaiys.proto.system.entity.MenuEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
        classes = ProtoBridgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class MenuStartupInitializationIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String PASSWORD = UUID.randomUUID().toString();

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
    }

    @Autowired MenuStartupInitializationService initialization;
    @Autowired MenuStartupInitializer startup;
    @Autowired MenuService menus;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired WebTestClient client;

    @BeforeAll
    static void coldStartUsesPackagedJsonAndPreservesItOnRepeatedInitialization(
            @Autowired MenuService menus,
            @Autowired MenuStartupInitializationService initialization) {
        Set<String> expected =
                Set.of(
                        "131a644480a0c3253a08103fedfe3280",
                        "protohub",
                        "prototype-hub-recycle",
                        "615414e79a20b30ea5e0babd4e2ef756",
                        "system-basis",
                        "system-user",
                        "system-org",
                        "system-role",
                        "system-menu",
                        "system-permission",
                        "system-dictionary",
                        "system-position",
                        "ef25fdb5d2af0a56ee43dfb2e6f5a983",
                        "system-access-log",
                        "system-logs");
        Map<String, MenuEntity> existing =
                menus.createQuery().fetch().collectMap(MenuEntity::getId).block(TIMEOUT);
        assertNotNull(existing);
        assertEquals(expected, existing.keySet());
        assertEquals(42, existing.values().stream().mapToInt(row -> row.getButtons().size()).sum());
        assertFalse(
                Boolean.TRUE.equals(initialization.initializeIfEmpty(unreadable()).block(TIMEOUT)));
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
        assertEquals("原型项目", existing.get("protohub").getName());
        assertEquals("基础配置", existing.get("system-basis").getName());
        assertEquals("用户管理", existing.get("system-user").getName());
        assertEquals(
                "Access Logs",
                existing.get("system-access-log").getI18nMessages().get("name").get("en_US"));
        assertEquals("调整日志级别", existing.get("system-logs").getButtons().getFirst().get("name"));
        assertPackagedButtonPermissions(existing);
    }

    private static void assertPackagedButtonPermissions(Map<String, MenuEntity> menus) {
        // 校验入库资源的实际授权结果：只补主资源动作，辅助查询和显式文件权限保持原边界。
        assertButtonPermissions(
                menus,
                "system-basis",
                "update",
                Map.of("system-config", Set.of("query", "save"), "file", Set.of("save")));
        assertButtonPermissions(
                menus, "system-user", "add", Map.of("user", Set.of("query", "save")));
        assertButtonPermissions(
                menus, "system-dictionary", "add", Map.of("dictionary", Set.of("query", "save")));
        assertButtonPermissions(
                menus,
                "system-dictionary",
                "delete",
                Map.of("dictionary", Set.of("query", "delete")));
        assertButtonPermissions(
                menus,
                "system-org",
                "add",
                Map.of("organization", Set.of("query", "save"), "user", Set.of("query")));
        assertButtonPermissions(
                menus,
                "system-role",
                "add",
                Map.of("role", Set.of("query", "save"), "menu", Set.of("query")));
        assertButtonPermissions(
                menus,
                "system-role",
                "groupDelete",
                Map.of("role", Set.of("query", "delete"), "menu", Set.of("query")));
        assertButtonPermissions(
                menus,
                "system-role",
                "update",
                Map.of("role", Set.of("query", "save"), "menu", Set.of("query", "grant")));
        assertButtonPermissions(
                menus,
                "system-menu",
                "grant",
                Map.of("menu", Set.of("query", "grant"), "permission", Set.of("query")));
        assertButtonPermissions(
                menus, "system-permission", "add", Map.of("permission", Set.of("query", "save")));
        assertButtonPermissions(
                menus,
                "system-position",
                "add",
                Map.of("position", Set.of("query", "save"), "organization", Set.of("query")));
        assertButtonPermissions(
                menus,
                "system-position",
                "bind",
                Map.of(
                        "position",
                        Set.of("query", "save"),
                        "organization",
                        Set.of("query", "save"),
                        "user",
                        Set.of("query")));
        assertButtonPermissions(
                menus, "system-logs", "update", Map.of("system-logger", Set.of("query", "save")));
        // A shared recycle page has no ANDed base resource; tabs and restores grant independently.
        assertEquals(
                Map.of(),
                MenuGrantService.permissions(menus.get("prototype-hub-recycle"), List.of()));
        assertButtonPermissions(
                menus,
                "prototype-hub-recycle",
                "queryProjects",
                Map.of("prototype-hub-project", Set.of("query")));
        assertButtonPermissions(
                menus,
                "prototype-hub-recycle",
                "queryVersions",
                Map.of("prototype-hub-version", Set.of("query")));
        assertButtonPermissions(
                menus,
                "prototype-hub-recycle",
                "restore",
                Map.of("prototype-hub-project", Set.of("query", "save")));
        assertButtonPermissions(
                menus,
                "prototype-hub-recycle",
                "restoreVersion",
                Map.of("prototype-hub-version", Set.of("query", "save")));
    }

    private static void assertButtonPermissions(
            Map<String, MenuEntity> menus,
            String menu,
            String button,
            Map<String, Set<String>> expected) {
        assertEquals(
                expected,
                MenuGrantService.permissions(menus.get(menu), List.of(button)),
                menu + "/" + button);
    }

    @BeforeEach
    void emptyIsolatedDatabaseMenus() {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        database.sql("DELETE FROM s_menu").fetch().rowsUpdated().block(TIMEOUT);
    }

    @Test
    void emptyTableImportsAllPackagedMenusWithHierarchyAndPermissionsAndIsIdempotent()
            throws Exception {
        assertTrue(Boolean.TRUE.equals(initialization.initializeIfEmpty().block(TIMEOUT)));
        Map<String, MenuEntity> actual =
                menus.createQuery().fetch().collectMap(MenuEntity::getId).block(TIMEOUT);
        assertNotNull(actual);
        assertEquals(15, actual.size());
        assertPackagedButtonPermissions(actual);
        List<MenuEntity> expected;
        try (var input =
                new ClassPathResource(MenuStartupInitializationService.DEFAULT_RESOURCE)
                        .getInputStream()) {
            expected = mapper.readValue(input, new TypeReference<List<MenuEntity>>() {});
        }
        Map<String, MenuEntity> flat = new LinkedHashMap<>();
        flatten(expected, null, flat);
        assertEquals(flat.keySet(), actual.keySet());
        assertTrue(flat.values().stream().anyMatch(row -> row.getParentId() != null));
        assertTrue(
                flat.values().stream()
                        .anyMatch(
                                row ->
                                        row.getPermissions() != null
                                                && !row.getPermissions().isEmpty()));
        for (MenuEntity expectedRow : flat.values()) {
            MenuEntity saved = actual.get(expectedRow.getId());
            assertEquals(expectedRow.getParentId(), saved.getParentId(), saved.getId());
            assertEquals(expectedRow.getCode(), saved.getCode());
            assertEquals(expectedRow.getName(), saved.getName());
            assertEquals(saved.getName(), saved.getI18nMessages().get("name").get("zh_CN"));
            assertFalse(saved.getI18nMessages().get("name").get("en_US").isBlank());
            assertFalse(saved.getI18nMessages().get("name").get("en").isBlank());
            assertEquals(expectedRow.getComponentCode(), saved.getComponentCode());
            assertEquals(expectedRow.getUrl(), saved.getUrl());
            assertEquals(expectedRow.getPermission(), saved.getPermission());
            assertEquals(expectedRow.getPermissions(), saved.getPermissions());
            assertEquals(expectedRow.getButtons(), saved.getButtons());
            assertEquals(expectedRow.getOptions(), saved.getOptions());
            assertEquals(expectedRow.getAssetType(), saved.getAssetType());
            assertEquals(expectedRow.getAccessSupport(), saved.getAccessSupport());
            assertEquals(expectedRow.getI18nMessages(), saved.getI18nMessages());
            assertEquals("proto", saved.getOwner());
            assertNotNull(saved.getPath());
            if (saved.getParentId() != null) {
                MenuEntity parent = actual.get(saved.getParentId());
                assertTrue(saved.getPath().startsWith(parent.getPath()));
                assertEquals(parent.getLevel() + 1, saved.getLevel());
            }
        }
        String admin = login("admin");
        JsonNode tree = call("POST", "/menu/_all/tree", admin, Map.of());
        assertEquals(2, tree.size());
        assertEquals(15, menuCodes(tree).size());
        assertEquals("system/Log/Access", find(tree, "system-access-log").path("code").asText());
        JsonNode englishTree = call("POST", "/menu/_all/tree", admin, Map.of(), "en-US");
        JsonNode chineseTree = call("POST", "/menu/_all/tree", admin, Map.of(), "zh-CN");
        assertEquals(
                "原型管理",
                find(englishTree, "131a644480a0c3253a08103fedfe3280").path("name").asText());
        assertEquals(
                "Prototype Management",
                find(englishTree, "131a644480a0c3253a08103fedfe3280").path("i18nName").asText());
        assertEquals("基础配置", find(englishTree, "system-basis").path("name").asText());
        assertEquals(
                "Basic Configuration", find(englishTree, "system-basis").path("i18nName").asText());
        assertEquals(
                "Log Management",
                find(englishTree, "ef25fdb5d2af0a56ee43dfb2e6f5a983").path("i18nName").asText());
        assertEquals(
                "Access Logs", find(englishTree, "system-access-log").path("i18nName").asText());
        assertEquals("基础配置", find(chineseTree, "system-basis").path("i18nName").asText());
        assertEquals("系统日志", find(chineseTree, "system-logs").path("i18nName").asText());
        assertEquals(
                0L,
                database.sql(
                                "SELECT count(*) AS total FROM information_schema.columns WHERE"
                                        + " table_schema=current_schema() AND table_name='s_menu' AND"
                                        + " column_name='i18n_name'")
                        .map((row, metadata) -> row.get("total", Long.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals("基础配置", menus.findById("system-basis").block(TIMEOUT).getName());
        Map<String, String> before = new LinkedHashMap<>();
        actual.forEach((id, row) -> before.put(id, row.getPath()));
        assertFalse(
                Boolean.TRUE.equals(initialization.initializeIfEmpty(unreadable()).block(TIMEOUT)));
        Map<String, String> after =
                menus.createQuery()
                        .fetch()
                        .collectMap(MenuEntity::getId, MenuEntity::getPath)
                        .block(TIMEOUT);
        assertEquals(before, after);
    }

    @Test
    void startupRunnerImportsResourceWithAdministratorAndMemberHttpVisibility() {
        startup.run(new DefaultApplicationArguments());
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
        String admin = login("admin");
        JsonNode administratorTree = call("POST", "/menu/user-own/tree", admin, Map.of());
        Set<String> administratorCodes = menuCodes(administratorTree);
        assertEquals(
                Set.of(
                        "prototype-hub",
                        "prototype-hub/Project",
                        "prototype-hub/Recycle",
                        "system",
                        "system/Basis",
                        "system/User",
                        "system/Department",
                        "system/Role",
                        "system/Menu",
                        "system/Permission",
                        "system/Dictionary",
                        "system/Positions",
                        "system/Log",
                        "system/Log/Access",
                        "system/Log/System"),
                administratorCodes);
        String username = "startup-member-" + UUID.randomUUID().toString().replace("-", "");
        call(
                "POST",
                "/user/detail/_create",
                admin,
                Map.of(
                        "name",
                        "Startup member",
                        "username",
                        username,
                        "password",
                        PASSWORD,
                        "roleIdList",
                        List.of("MEMBER")));
        JsonNode memberTree = call("POST", "/menu/user-own/tree", login(username), Map.of());
        Set<String> memberCodes = menuCodes(memberTree);
        assertTrue(memberCodes.contains("prototype-hub"));
        // Raw legacy payloads retain empty folders. Page permissions must still hide every
        // management leaf; the frontend menu adapter handles these empty group containers.
        memberCodes.removeAll(Set.of("prototype-hub", "system", "system/Log"));
        assertEquals(Set.of("prototype-hub/Project", "prototype-hub/Recycle"), memberCodes);
        JsonNode project = find(memberTree, "protohub");
        assertTrue(project.path("granted").asBoolean());
        assertEquals(3, project.path("buttons").size());
        for (JsonNode button : project.path("buttons"))
            assertFalse(button.path("granted").asBoolean());
        assertFalse(memberCodes.contains("account-center"));
    }

    @Test
    void anyExistingDisabledMenuOfAnotherOwnerPreventsParsingAndPreservesMetadata() {
        database.sql(
                        "INSERT INTO s_menu(id,name,enabled,owner,access_support,code,options)"
                                + " VALUES"
                                + " ('existing-custom','Existing',false,'other-owner','unsupported','custom-code','{\"show\":false}')")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertFalse(
                Boolean.TRUE.equals(initialization.initializeIfEmpty(unreadable()).block(TIMEOUT)));
        assertEquals(1, menus.createQuery().count().block(TIMEOUT));
        MenuEntity existing = menus.findById("existing-custom").block(TIMEOUT);
        assertNotNull(existing);
        assertEquals("other-owner", existing.getOwner());
        assertEquals("custom-code", existing.getCode());
        assertFalse(existing.getEnabled());
        assertEquals(Map.of("show", false), existing.getOptions());
    }

    @Test
    void existingLegacyOwnerMenuPreventsImportWithoutChangingItsOwner() {
        database.sql(
                        "INSERT INTO s_menu(id,name,enabled,owner,access_support,code) VALUES"
                                + " ('legacy-owner','Legacy',true,'iot','unsupported','legacy-code')")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertFalse(
                Boolean.TRUE.equals(initialization.initializeIfEmpty(unreadable()).block(TIMEOUT)));
        assertEquals(1, menus.createQuery().count().block(TIMEOUT));
        assertEquals("iot", menus.findById("legacy-owner").block(TIMEOUT).getOwner());
    }

    @Test
    void mismatchedResourceOwnerIsRejectedBeforeWritingAnyRows() throws Exception {
        // 归属只在启动调用方对齐，资源中的跨归属输入仍须由既有校验器原子拒绝。
        Resource mismatched =
                new ByteArrayResource(
                        mapper.writeValueAsBytes(
                                List.of(
                                        Map.of(
                                                "id",
                                                "valid-owner",
                                                "name",
                                                "Valid",
                                                "owner",
                                                "proto"),
                                        Map.of(
                                                "id",
                                                "other-owner",
                                                "name",
                                                "Other",
                                                "owner",
                                                "iot"))));
        assertThrows(
                RuntimeException.class,
                () -> initialization.initializeIfEmpty(mismatched).block(TIMEOUT));
        assertEquals(0, menus.createQuery().count().block(TIMEOUT));
        assertTrue(Boolean.TRUE.equals(initialization.initializeIfEmpty().block(TIMEOUT)));
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
        assertTrue(
                menus.createQuery()
                        .fetch()
                        .all(row -> "proto".equals(row.getOwner()))
                        .block(TIMEOUT));
    }

    @Test
    void malformedEmptyOversizedAndInvalidTreeResourcesFailWithoutPartialRows() {
        List<Resource> invalid =
                List.of(
                        text("{"),
                        text("null"),
                        text("[]"),
                        text("{}"),
                        text("[{\"id\":\"bad-parent\",\"name\":\"Bad\",\"parentId\":\"missing\"}]"),
                        new ByteArrayResource(new byte[1024 * 1024 + 1]));
        for (Resource resource : invalid) {
            assertThrows(
                    RuntimeException.class,
                    () -> initialization.initializeIfEmpty(resource).block(TIMEOUT));
            assertEquals(0, menus.createQuery().count().block(TIMEOUT));
        }
        assertTrue(Boolean.TRUE.equals(initialization.initializeIfEmpty().block(TIMEOUT)));
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
    }

    @Test
    void databaseFailureAfterAnEarlierSaveBatchRollsBackAllRowsAndReleasesLock() throws Exception {
        List<Map<String, Object>> input = new ArrayList<>();
        for (int index = 0; index < 201; index++)
            input.add(
                    Map.of(
                            "id",
                            index == 200 ? "startup-fail" : "startup-" + index,
                            "name",
                            "Startup " + index,
                            "code",
                            "startup/" + index));
        Resource resource = new ByteArrayResource(mapper.writeValueAsBytes(input));
        database.sql(
                        "CREATE FUNCTION reject_startup_menu() RETURNS trigger LANGUAGE plpgsql AS"
                                + " $$ BEGIN IF NEW.id='startup-fail' THEN RAISE EXCEPTION 'forced"
                                + " startup menu failure'; END IF; RETURN NEW; END $$")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql(
                        "CREATE TRIGGER reject_startup_menu BEFORE INSERT ON s_menu "
                                + "FOR EACH ROW EXECUTE FUNCTION reject_startup_menu()")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> initialization.initializeIfEmpty(resource).block(TIMEOUT));
            assertEquals(0, menus.createQuery().count().block(TIMEOUT));
        } finally {
            database.sql("DROP TRIGGER reject_startup_menu ON s_menu")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
            database.sql("DROP FUNCTION reject_startup_menu()")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        }
        assertTrue(Boolean.TRUE.equals(initialization.initializeIfEmpty().block(TIMEOUT)));
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
    }

    @Test
    void concurrentInitializersImportExactlyOnceOnTheLockedConnection() throws Exception {
        byte[] bytes;
        try (var input =
                new ClassPathResource(MenuStartupInitializationService.DEFAULT_RESOURCE)
                        .getInputStream()) {
            bytes = input.readAllBytes();
        }
        AtomicInteger reads = new AtomicInteger();
        Resource resource =
                new ByteArrayResource(bytes) {
                    @Override
                    public InputStream getInputStream() throws IOException {
                        reads.incrementAndGet();
                        return super.getInputStream();
                    }
                };
        var result =
                Mono.zip(
                                initialization.initializeIfEmpty(resource),
                                initialization.initializeIfEmpty(resource))
                        .block(TIMEOUT);
        assertNotNull(result);
        assertNotEquals(result.getT1(), result.getT2());
        assertEquals(1, reads.get());
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
    }

    @Test
    void existingProductGetsOnlyMissingRecycleWithoutChangingMenusOrGrantingRoles()
            throws Exception {
        initialization.initializeIfEmpty().block(TIMEOUT);
        menus.deleteById("prototype-hub-recycle").block(TIMEOUT);
        menus.createUpdate()
                .set(MenuEntity::getName, "自定义项目入口")
                .where(MenuEntity::getId, "protohub")
                .execute()
                .block(TIMEOUT);
        Map<String, String> before = menuSnapshot();
        long grants =
                database.sql("SELECT count(*) AS total FROM s_role_menu_grant")
                        .map((row, metadata) -> row.get("total", Long.class))
                        .one()
                        .block(TIMEOUT);
        assertTrue(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        var recycle = menus.findById("prototype-hub-recycle").block(TIMEOUT);
        assertNotNull(recycle);
        assertEquals("回收站", recycle.getName());
        assertEquals("prototype-hub/Recycle", recycle.getComponentCode());
        assertEquals("/prototype-hub/recycle-bin", recycle.getUrl());
        assertEquals(Map.of(), MenuGrantService.permissions(recycle, List.of()));
        assertEquals(
                Map.of("prototype-hub-project", Set.of("query", "save")),
                MenuGrantService.permissions(recycle, List.of("restore")));
        assertEquals(
                Map.of("prototype-hub-version", Set.of("query", "save")),
                MenuGrantService.permissions(recycle, List.of("restoreVersion")));
        var after = menuSnapshot();
        after.remove("prototype-hub-recycle");
        assertEquals(before, after);
        assertEquals(
                grants,
                database.sql("SELECT count(*) AS total FROM s_role_menu_grant")
                        .map((row, metadata) -> row.get("total", Long.class))
                        .one()
                        .block(TIMEOUT));
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
    }

    @Test
    void existingProjectOnlyRecycleConfigurationAndRoleSelectionAreNeverUpgradedImplicitly()
            throws Exception {
        initialization.initializeIfEmpty().block(TIMEOUT);
        menus.createUpdate()
                .set(MenuEntity::getName, "自定义旧回收站")
                .set(
                        MenuEntity::getPermissions,
                        List.of(
                                Map.of(
                                        "permission",
                                        "prototype-hub-project",
                                        "actions",
                                        List.of("query"))))
                .set(
                        MenuEntity::getButtons,
                        List.of(
                                Map.of(
                                        "id",
                                        "restore",
                                        "name",
                                        "自定义恢复",
                                        "permissions",
                                        List.of(
                                                Map.of(
                                                        "permission",
                                                        "prototype-hub-project",
                                                        "actions",
                                                        List.of("save"))))))
                .where(MenuEntity::getId, "prototype-hub-recycle")
                .execute()
                .block(TIMEOUT);
        var before = menuSnapshot();
        var grants =
                database.sql(
                                "SELECT id,menus,managed_permissions FROM s_role_menu_grant ORDER"
                                        + " BY id")
                        .fetch()
                        .all()
                        .map(Object::toString)
                        .collectList()
                        .block(TIMEOUT);
        startup.run(new DefaultApplicationArguments());
        assertEquals(before, menuSnapshot());
        assertEquals(
                grants,
                database.sql(
                                "SELECT id,menus,managed_permissions FROM s_role_menu_grant ORDER"
                                        + " BY id")
                        .fetch()
                        .all()
                        .map(Object::toString)
                        .collectList()
                        .block(TIMEOUT));
        assertEquals(
                Map.of("prototype-hub-project", Set.of("query", "save")),
                MenuGrantService.permissions(
                        menus.findById("prototype-hub-recycle").block(TIMEOUT),
                        List.of("restore")));
    }

    @Test
    void recycleQueryAndRestoreButtonsGrantOnlyTheirOwnResourceOverHttp() {
        initialization.initializeIfEmpty().block(TIMEOUT);
        String admin = login("admin");
        for (String selected :
                List.of("", "queryProjects", "queryVersions", "restore", "restoreVersion")) {
            String role = UUID.randomUUID().toString().replace("-", "");
            call("POST", "/role", admin, Map.of("id", role, "name", "独立回收站权限"));
            call(
                    "PUT",
                    "/menu/role/" + role + "/_grant",
                    admin,
                    Map.of(
                            "menus",
                            List.of(
                                    Map.of(
                                            "id",
                                            "prototype-hub-recycle",
                                            "buttons",
                                            selected.isEmpty()
                                                    ? List.of()
                                                    : List.of(
                                                            Map.of(
                                                                    "id", selected, "granted",
                                                                    true))))));
            String username = "recycle-" + UUID.randomUUID().toString().replace("-", "");
            call(
                    "POST",
                    "/user/detail/_create",
                    admin,
                    Map.of(
                            "name",
                            "回收站权限用户",
                            "username",
                            username,
                            "password",
                            PASSWORD,
                            "roleIdList",
                            List.of(role)));
            String token = login(username);
            var recycle =
                    find(
                            call("POST", "/menu/user-own/tree", token, Map.of()),
                            "prototype-hub-recycle");
            assertFalse(recycle.isMissingNode());
            assertTrue(recycle.path("granted").asBoolean());
            for (JsonNode button : recycle.path("buttons"))
                assertEquals(
                        selected.equals(button.path("id").asText()),
                        button.path("granted").asBoolean());
            // Empty-base menus grant no actions. Selecting one tab/restore never grants its
            // sibling.
            boolean projects = selected.equals("queryProjects") || selected.equals("restore");
            boolean versions =
                    selected.equals("queryVersions") || selected.equals("restoreVersion");
            client.post()
                    .uri("/prototype-hub/projects/_deleted/_query")
                    .header("X-Access-Token", token)
                    .bodyValue(Map.of())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(projects ? 200 : 403);
            client.post()
                    .uri("/prototype-hub/versions/_deleted/_query")
                    .header("X-Access-Token", token)
                    .bodyValue(Map.of())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(versions ? 200 : 403);
            client.post()
                    .uri("/prototype-hub/projects/nonexistent-recycle-test/_restore")
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(selected.equals("restore") ? 404 : 403);
            client.post()
                    .uri("/prototype-hub/versions/nonexistent-recycle-test/_restore")
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(selected.equals("restoreVersion") ? 404 : 403);
        }
    }

    @Test
    void foreignOwnerParentNeverReceivesProductMenuAdditions() throws Exception {
        var foreignParent = new MenuEntity();
        foreignParent.setId("131a644480a0c3253a08103fedfe3280");
        foreignParent.setName("Other owner parent");
        foreignParent.setCode("prototype-hub");
        foreignParent.setOwner("custom");
        menus.save(Mono.just(foreignParent)).block(TIMEOUT);
        var foreignTree = menuSnapshot();
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        assertEquals(foreignTree, menuSnapshot());
    }

    @Test
    void targetedAdditionPreservesDisabledAndCustomEntriesAndRequiresTheProductParent()
            throws Exception {
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        initialization.initializeIfEmpty().block(TIMEOUT);
        menus.createUpdate()
                .set(MenuEntity::getEnabled, false)
                .where(MenuEntity::getId, "prototype-hub-recycle")
                .execute()
                .block(TIMEOUT);
        var disabled = menuSnapshot();
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        assertEquals(disabled, menuSnapshot());
        menus.deleteById("prototype-hub-recycle").block(TIMEOUT);
        menus.createUpdate()
                .set(MenuEntity::getEnabled, false)
                .where(MenuEntity::getId, "131a644480a0c3253a08103fedfe3280")
                .execute()
                .block(TIMEOUT);
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        menus.createUpdate()
                .set(MenuEntity::getEnabled, true)
                .where(MenuEntity::getId, "131a644480a0c3253a08103fedfe3280")
                .execute()
                .block(TIMEOUT);
        var custom = new MenuEntity();
        custom.setId("custom-recycle-route");
        custom.setName("Custom disabled recycle route");
        custom.setOwner("custom");
        custom.setCode("custom-recycle");
        custom.setUrl("/prototype-hub/recycle-bin");
        custom.setEnabled(false);
        menus.save(Mono.just(custom)).block(TIMEOUT);
        var customized = menuSnapshot();
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        assertEquals(customized, menuSnapshot());
        menus.createUpdate()
                .set(MenuEntity::getUrl, "/custom/recycle")
                .set(MenuEntity::getComponentCode, "prototype-hub/Recycle")
                .where(MenuEntity::getId, "custom-recycle-route")
                .execute()
                .block(TIMEOUT);
        var componentOverride = menuSnapshot();
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
        assertEquals(componentOverride, menuSnapshot());
        menus.deleteById("custom-recycle-route").block(TIMEOUT);
        // 已有产品标识被管理员改作其他菜单时，同样不恢复或重写它。
        menus.createUpdate()
                .set(MenuEntity::getCode, "custom-parent")
                .where(MenuEntity::getId, "131a644480a0c3253a08103fedfe3280")
                .execute()
                .block(TIMEOUT);
        assertFalse(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
    }

    @Test
    void concurrentTargetedAdditionsCreateOneTreeEntry() {
        initialization.initializeIfEmpty().block(TIMEOUT);
        menus.deleteById("prototype-hub-recycle").block(TIMEOUT);
        var outcomes =
                Mono.zip(
                                initialization.ensureRecycleMenuIfMissing(),
                                initialization.ensureRecycleMenuIfMissing())
                        .block(TIMEOUT);
        assertNotNull(outcomes);
        assertNotEquals(outcomes.getT1(), outcomes.getT2());
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
        var parent = menus.findById("131a644480a0c3253a08103fedfe3280").block(TIMEOUT);
        var recycle = menus.findById("prototype-hub-recycle").block(TIMEOUT);
        assertTrue(recycle.getPath().startsWith(parent.getPath()));
        assertEquals(parent.getLevel() + 1, recycle.getLevel());
    }

    @Test
    void failedTargetedAdditionRollsBackAndCanBeRetriedWithoutChangingExistingTree()
            throws Exception {
        initialization.initializeIfEmpty().block(TIMEOUT);
        menus.deleteById("prototype-hub-recycle").block(TIMEOUT);
        var before = menuSnapshot();
        database.sql(
                        "CREATE FUNCTION reject_recycle_menu() RETURNS trigger LANGUAGE plpgsql AS"
                                + " $$ BEGIN IF NEW.id='prototype-hub-recycle' THEN RAISE EXCEPTION"
                                + " 'forced recycle failure'; END IF; RETURN NEW; END $$")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql(
                        "CREATE TRIGGER reject_recycle_menu BEFORE INSERT ON s_menu FOR EACH ROW"
                                + " EXECUTE FUNCTION reject_recycle_menu()")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
            assertEquals(before, menuSnapshot());
        } finally {
            database.sql("DROP TRIGGER reject_recycle_menu ON s_menu")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
            database.sql("DROP FUNCTION reject_recycle_menu()")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        }
        assertTrue(initialization.ensureRecycleMenuIfMissing().block(TIMEOUT));
    }

    private Map<String, String> menuSnapshot() throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (MenuEntity menu : menus.createQuery().fetch().collectList().block(TIMEOUT))
            snapshot.put(menu.getId(), mapper.writeValueAsString(menu));
        return snapshot;
    }

    private Resource text(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }

    private String login(String username) {
        return call(
                        "POST",
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD))
                .path("token")
                .asText();
    }

    private JsonNode call(String method, String path, String token, Object body) {
        return call(method, path, token, body, "zh-CN");
    }

    private JsonNode call(String method, String path, String token, Object body, String locale) {
        var request =
                client.method(HttpMethod.valueOf(method))
                        .uri(path)
                        .header("Accept-Language", locale);
        if (token != null) request.header("X-Access-Token", token);
        JsonNode response =
                request.bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(response);
        return response.path("result");
    }

    private JsonNode find(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) return row;
            JsonNode child = find(row.path("children"), id);
            if (!child.isMissingNode()) return child;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private Set<String> menuCodes(JsonNode rows) {
        Set<String> codes = new HashSet<>();
        for (JsonNode row : rows) {
            codes.add(row.path("code").asText());
            codes.addAll(menuCodes(row.path("children")));
        }
        return codes;
    }

    private static Resource unreadable() {
        return new ByteArrayResource(new byte[0]) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new AssertionError("Nonempty tables must not open the menu resource");
            }
        };
    }

    private void flatten(List<MenuEntity> rows, String parent, Map<String, MenuEntity> flat) {
        for (MenuEntity row : rows) {
            if (parent != null) row.setParentId(parent);
            flat.put(row.getId(), row);
            if (row.getChildren() != null) flatten(row.getChildren(), row.getId(), flat);
        }
    }
}
