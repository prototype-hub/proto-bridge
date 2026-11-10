package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.system.configuration.initializer.AdministratorInitializer;
import com.gaiys.proto.system.entity.RoleMenuGrantEntity;
import com.gaiys.proto.system.service.RoleMenuGrantService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hswebframework.web.authorization.ReactiveAuthenticationInitializeService;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultAuthorizationSettingService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultPermissionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/** 用自定义保留用户名验证认证派生、菜单、原生写入保护与启动恢复，不依赖 ADMIN 角色全量授权。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false",
            "proto.admin.username=reserved-root"
        })
@Testcontainers
@Import(BuiltInAdministratorIntegrationTest.ProbeController.class)
class BuiltInAdministratorIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String RESOURCE = "builtin-dynamic-probe";

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
        registry.add("proto.admin.password", () -> PASSWORD);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired DefaultAuthorizationSettingService settings;
    @Autowired DefaultDimensionUserService bindings;
    @Autowired DefaultReactiveUserService users;
    @Autowired DefaultPermissionService permissions;
    @Autowired RoleMenuGrantService menuGrants;
    @Autowired AdministratorInitializer initializer;
    @Autowired ReactiveAuthenticationInitializeService authentication;
    String admin;
    String adminId;

    @BeforeEach
    void login() {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("reserved-root", PASSWORD);
        adminId = call("GET", "/authorize/me", admin, null, 200).path("user").path("id").asText();
        assertFalse(adminId.isBlank());
    }

    @Test
    void currentResourcesAndMenusAreAvailableWithoutRoleGrantsAndDisabledResourcesStayDenied() {
        var original =
                settings.createQuery()
                        .where("dimensionType", "role")
                        .and("dimensionTarget", "ADMIN")
                        .fetch()
                        .collectList()
                        .block(TIMEOUT);
        var originalMenus = menuGrants.findById("ADMIN").block(TIMEOUT);
        RoleMenuGrantEntity empty = new RoleMenuGrantEntity();
        empty.setId("ADMIN");
        empty.setMenus(Map.of());
        settings.createDelete()
                .where("dimensionType", "role")
                .and("dimensionTarget", "ADMIN")
                .execute()
                .block(TIMEOUT);
        menuGrants.save(empty).block(TIMEOUT);
        String menu = key();
        String disabled = key();
        try {
            permissions.deleteById(RESOURCE).block(TIMEOUT);
            assertTrue(
                    authentication
                            .initUserAuthorization(adminId)
                            .block(TIMEOUT)
                            .hasPermission("prototype-hub-license", "query"));
            call("GET", "/prototype-hub/license", admin, null, 200);
            call("GET", "/test/builtin-dynamic", admin, null, 403);
            resource((byte) 1);
            assertTrue(
                    hasAction(call("GET", "/authorize/me", admin, null, 200), RESOURCE, "query"));
            assertEquals("ok", call("GET", "/test/builtin-dynamic", admin, null, 200).asText());
            call(
                    "POST",
                    "/menu",
                    admin,
                    Map.of(
                            "id",
                            menu,
                            "name",
                            "Reserved root dynamic menu",
                            "code",
                            "probe/" + menu,
                            "owner",
                            "iot",
                            "options",
                            Map.of("show", false),
                            "enabled",
                            true,
                            "permissions",
                            List.of(Map.of("permission", RESOURCE, "actions", List.of("query"))),
                            "buttons",
                            List.of(Map.of("id", "query", "name", "Read"))),
                    200);
            call(
                    "POST",
                    "/menu",
                    admin,
                    Map.of(
                            "id",
                            disabled,
                            "name",
                            "Disabled menu",
                            "code",
                            "probe/" + disabled,
                            "enabled",
                            false),
                    200);
            JsonNode own = call("POST", "/menu/user-own/tree", admin, Map.of(), 200);
            JsonNode visible = find(own, menu);
            assertNotNull(visible);
            assertTrue(visible.path("options").has("show"));
            assertFalse(visible.path("options").path("show").asBoolean());
            assertEquals("iot", visible.path("owner").asText());
            assertTrue(visible.path("buttons").get(0).path("granted").asBoolean());
            assertNull(find(own, disabled));
            assertEquals(
                    0,
                    settings.createQuery()
                            .where("dimensionType", "role")
                            .and("dimensionTarget", "ADMIN")
                            .count()
                            .block(TIMEOUT));
            resource((byte) 0);
            assertFalse(
                    hasAction(call("GET", "/authorize/me", admin, null, 200), RESOURCE, "query"));
            call("GET", "/test/builtin-dynamic", admin, null, 403);
            assertNull(find(call("POST", "/menu/user-own/tree", admin, Map.of(), 200), menu));
        } finally {
            resource((byte) 1);
            settings.save(original).block(TIMEOUT);
            if (originalMenus == null) menuGrants.deleteById("ADMIN").block(TIMEOUT);
            else menuGrants.save(originalMenus).block(TIMEOUT);
            call("DELETE", "/menu/" + menu, admin, null, 200);
            call("DELETE", "/menu/" + disabled, admin, null, 200);
        }
    }

    @Test
    void businessNativeAndBatchBindingPathsPreserveReservedAdministratorMembership() {
        DimensionUserEntity binding = adminBinding();
        call("POST", "/role/ADMIN/users/_unbind", admin, List.of(adminId), 409);
        call("PUT", "/user/detail/" + adminId + "/role/_bind", admin, List.of(), 409);
        call(
                "PUT",
                "/user/detail/" + adminId + "/role/_bind",
                admin,
                List.of("ADMIN", "MEMBER"),
                200);
        assertEquals(binding.getId(), adminBinding().getId());
        for (String path :
                List.of(
                        "/dimension-user/" + binding.getId(),
                        "/dimension-user/user/" + adminId,
                        "/dimension-user/user/" + adminId + "/dimension/ADMIN",
                        "/dimension-user/dimension/ADMIN")) call("DELETE", path, admin, null, 409);
        call("POST", "/api/dimension-user/user/role/ADMIN/_unbind", admin, List.of(adminId), 409);
        call(
                "PUT",
                "/dimension-user/" + binding.getId(),
                admin,
                Map.of("dimensionId", "MEMBER", "dimensionName", "Member"),
                409);
        call(
                "PATCH",
                "/dimension-user",
                admin,
                List.of(Map.of("id", binding.getId(), "dimensionId", "MEMBER")),
                409);
        String other = createUser("batch-" + key());
        call("POST", "/role/ADMIN/users/_bind", admin, List.of(other), 200);
        assertThrows(
                Exception.class,
                () ->
                        bindings.createDelete()
                                .where(DimensionUserEntity::getDimensionId, "ADMIN")
                                .in(DimensionUserEntity::getUserId, List.of(other, adminId))
                                .execute()
                                .block(TIMEOUT));
        assertEquals(
                2,
                bindings.createQuery()
                        .where(DimensionUserEntity::getDimensionId, "ADMIN")
                        .in(DimensionUserEntity::getUserId, List.of(other, adminId))
                        .count()
                        .block(TIMEOUT));
        assertThrows(
                Exception.class,
                () ->
                        bindings.createUpdate()
                                .set(DimensionUserEntity::getUserId, other)
                                .where(DimensionUserEntity::getId, binding.getId())
                                .execute()
                                .block(TIMEOUT));
        assertThrows(
                Exception.class,
                () ->
                        bindings.createUpdate()
                                .setNull(DimensionUserEntity::getDimensionTypeId)
                                .where(DimensionUserEntity::getId, binding.getId())
                                .execute()
                                .block(TIMEOUT));
        assertNotNull(adminBinding());
        call("POST", "/role/ADMIN/users/_unbind", admin, List.of(other), 200);
    }

    @Test
    void reservedAccountCannotBeRenamedDeletedOrDisabledButProfileAndPasswordRemainWritable() {
        call("PATCH", "/user", admin, Map.of("id", adminId, "username", "replacement"), 409);
        call(
                "PUT",
                "/user/me",
                admin,
                Map.of("name", "Updated administrator", "username", "replacement"),
                400);
        call("PUT", "/user/me", admin, Map.of("name", "Updated administrator"), 200);
        UserEntity rename = new UserEntity();
        rename.setUsername("replacement");
        assertThrows(
                Exception.class, () -> users.updateById(adminId, Mono.just(rename)).block(TIMEOUT));
        call("PATCH", "/user", admin, Map.of("id", adminId, "status", 0), 409);
        call("PUT", "/user/" + adminId + "/0", admin, null, 409);
        call("PUT", "/user/" + adminId + "/2", admin, null, 409);
        call("DELETE", "/user/" + adminId, admin, null, 409);
        String other = createUser("status-batch-" + key());
        assertThrows(
                Exception.class,
                () -> users.changeState(Mono.just(adminId), (byte) 0).block(TIMEOUT));
        assertThrows(
                Exception.class,
                () ->
                        users.createUpdate()
                                .set(UserEntity::getStatus, (byte) 0)
                                .in(UserEntity::getId, List.of(other, adminId))
                                .execute()
                                .block(TIMEOUT));
        assertThrows(
                Exception.class,
                () ->
                        users.createUpdate()
                                .setNull(UserEntity::getStatus)
                                .where(UserEntity::getId, adminId)
                                .execute()
                                .block(TIMEOUT));
        assertThrows(
                Exception.class,
                () ->
                        users.createUpdate()
                                .setNull(UserEntity::getUsername)
                                .where(UserEntity::getId, adminId)
                                .execute()
                                .block(TIMEOUT));
        assertEquals((byte) 1, users.findById(other).block(TIMEOUT).getStatus());
        assertThrows(
                Exception.class,
                () ->
                        users.createDelete()
                                .in(UserEntity::getId, List.of(other, adminId))
                                .execute()
                                .block(TIMEOUT));
        assertNotNull(users.findById(other).block(TIMEOUT));
        String changedPassword = UUID.randomUUID().toString();
        try {
            call(
                    "PATCH",
                    "/user",
                    admin,
                    Map.of(
                            "id",
                            adminId,
                            "name",
                            "Updated administrator",
                            "password",
                            changedPassword),
                    200);
            String changed = login("reserved-root", changedPassword);
            assertEquals(
                    "Updated administrator",
                    call("GET", "/authorize/me", changed, null, 200)
                            .path("user")
                            .path("name")
                            .asText());
        } finally {
            UserEntity reset = new UserEntity();
            reset.setId(adminId);
            reset.setPassword(PASSWORD);
            users.saveUser(Mono.just(reset)).block(TIMEOUT);
        }
        assertFalse(login("reserved-root", PASSWORD).isBlank());
    }

    @Test
    void otherAdminMembersAndAdminTypedAccountsRemainRevocable() {
        var original =
                settings.createQuery()
                        .where("dimensionType", "role")
                        .and("dimensionTarget", "ADMIN")
                        .and("permission", RESOURCE)
                        .fetch()
                        .collectList()
                        .block(TIMEOUT);
        settings.createDelete()
                .where("dimensionType", "role")
                .and("dimensionTarget", "ADMIN")
                .and("permission", RESOURCE)
                .execute()
                .block(TIMEOUT);
        String setting = key();
        try {
            String ordinary = createUser("admin");
            users.createUpdate()
                    .set(UserEntity::getType, "admin")
                    .where(UserEntity::getId, ordinary)
                    .execute()
                    .block(TIMEOUT);
            String token = login("admin", PASSWORD);
            assertFalse(
                    hasAction(call("GET", "/authorize/me", token, null, 200), RESOURCE, "query"));
            call("GET", "/test/builtin-dynamic", token, null, 403);
            call("POST", "/role/ADMIN/users/_bind", admin, List.of(ordinary), 200);
            call("GET", "/test/builtin-dynamic", token, null, 403);
            call(
                    "PATCH",
                    "/autz-setting",
                    admin,
                    Map.of(
                            "id",
                            setting,
                            "permission",
                            RESOURCE,
                            "dimensionType",
                            "role",
                            "dimensionTarget",
                            "ADMIN",
                            "state",
                            1,
                            "actions",
                            List.of("query")),
                    200);
            call("GET", "/test/builtin-dynamic", token, null, 200);
            settings.deleteById(setting).block(TIMEOUT);
            call("GET", "/test/builtin-dynamic", token, null, 403);
            call("POST", "/role/ADMIN/users/_unbind", admin, List.of(ordinary), 200);
            call("PUT", "/user/" + ordinary + "/0", admin, null, 200);
            call("DELETE", "/user/" + ordinary, admin, null, 200);
        } finally {
            settings.deleteById(setting).block(TIMEOUT);
            settings.save(original).block(TIMEOUT);
        }
    }

    @Test
    void startupRestoresOnlyMissingBindingWithoutChangingPasswordStateOrRoleGrants() {
        UserEntity before = users.findById(adminId).block(TIMEOUT);
        var grants =
                settings.createQuery()
                        .where("dimensionType", "role")
                        .and("dimensionTarget", "ADMIN")
                        .fetch()
                        .collectList()
                        .block(TIMEOUT);
        database.sql(
                        "DELETE FROM s_dimension_user WHERE user_id=:user AND"
                                + " dimension_type_id='role' AND dimension_id='ADMIN'")
                .bind("user", adminId)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertFalse(
                authentication
                        .initUserAuthorization(adminId)
                        .block(TIMEOUT)
                        .hasDimension("role", "ADMIN"));
        initializer.run(new DefaultApplicationArguments());
        initializer.run(new DefaultApplicationArguments());
        assertNotNull(adminBinding());
        assertTrue(
                authentication
                        .initUserAuthorization(adminId)
                        .block(TIMEOUT)
                        .hasDimension("role", "ADMIN"));
        assertTrue(hasAdminDimension(call("GET", "/authorize/me", admin, null, 200)));
        call("GET", "/prototype-hub/license", admin, null, 200);
        UserEntity after = users.findById(adminId).block(TIMEOUT);
        assertEquals(before.getPassword(), after.getPassword());
        assertEquals(before.getSalt(), after.getSalt());
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(
                grants.size(),
                settings.createQuery()
                        .where("dimensionType", "role")
                        .and("dimensionTarget", "ADMIN")
                        .count()
                        .block(TIMEOUT));
        try {
            database.sql("UPDATE s_user SET status=0 WHERE id=:id")
                    .bind("id", adminId)
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
            initializer.run(new DefaultApplicationArguments());
            assertEquals((byte) 0, users.findById(adminId).block(TIMEOUT).getStatus());
            assertNull(authentication.initUserAuthorization(adminId).block(TIMEOUT));
        } finally {
            database.sql("UPDATE s_user SET status=1 WHERE id=:id")
                    .bind("id", adminId)
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        }
    }

    private DimensionUserEntity adminBinding() {
        return bindings.createQuery()
                .where(DimensionUserEntity::getUserId, adminId)
                .and(DimensionUserEntity::getDimensionTypeId, "role")
                .and(DimensionUserEntity::getDimensionId, "ADMIN")
                .fetchOne()
                .block(TIMEOUT);
    }

    private void resource(byte status) {
        call(
                "PATCH",
                "/permission",
                admin,
                Map.of(
                        "id",
                        RESOURCE,
                        "name",
                        "Dynamic reserved-account probe",
                        "status",
                        status,
                        "actions",
                        List.of(Map.of("action", "query", "name", "Read"))),
                200);
    }

    private String createUser(String username) {
        return call(
                        "POST",
                        "/user/detail/_create",
                        admin,
                        Map.of("name", "Ordinary user", "username", username, "password", PASSWORD),
                        200)
                .path("id")
                .asText();
    }

    private String login(String username, String password) {
        return call(
                        "POST",
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", password),
                        200)
                .path("token")
                .asText();
    }

    private JsonNode call(String method, String path, String token, Object body, int status) {
        var request = client.method(HttpMethod.valueOf(method)).uri(path);
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
        return response.path("result");
    }

    private boolean hasAction(JsonNode auth, String permission, String action) {
        for (JsonNode row : auth.path("permissions"))
            if (permission.equals(row.path("id").asText()))
                for (JsonNode value : row.path("actions"))
                    if (action.equals(value.asText())) return true;
        return false;
    }

    private boolean hasAdminDimension(JsonNode auth) {
        for (JsonNode dimension : auth.path("dimensions"))
            if ("role".equals(dimension.path("type").path("id").asText())
                    && "ADMIN".equals(dimension.path("id").asText())) return true;
        return false;
    }

    private JsonNode find(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) return row;
            JsonNode child = find(row.path("children"), id);
            if (child != null) return child;
        }
        return null;
    }

    private String key() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @RestController
    @Resource(id = RESOURCE, name = "Reserved account dynamic probe")
    static class ProbeController {
        @GetMapping("/test/builtin-dynamic")
        @QueryAction
        public Mono<String> query() {
            return Mono.just("ok");
        }
    }
}
