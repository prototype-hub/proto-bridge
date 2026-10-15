package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/** 原生维度接口必须遵守与业务角色入口相同的删除不变量；权限仍为 dimension:delete。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
@Import(NativeFrameworkApiContractIntegrationTest.FailureConfiguration.class)
class NativeFrameworkApiContractIntegrationTest {
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

    @Autowired WebTestClient client;
    @Autowired ApplicationContext context;
    @Autowired PositionDeleteFailure deletionFailure;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        admin =
                call(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", "admin", "password", PASSWORD),
                                200)
                        .path("token")
                        .asText();
        assertFalse(admin.isBlank());
    }

    JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
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

    @Test
    void nativeDimensionDeleteAllowsUnusedRolesButCannotDeleteTheProtectedMemberRole() {
        // 默认管理员同时持有 role:delete 与 dimension:delete；本测试不假定两种资源权限等价。
        String role = UUID.randomUUID().toString().replace("-", "");
        call(
                HttpMethod.POST,
                "/role",
                admin,
                Map.of("id", role, "name", "Unused native role"),
                200);
        call(HttpMethod.DELETE, "/api/dimension/" + role, admin, null, 200);
        call(HttpMethod.GET, "/role/" + role, admin, null, 404);

        call(HttpMethod.DELETE, "/role/MEMBER", admin, null, 409);
        assertEquals(
                "MEMBER",
                call(HttpMethod.GET, "/role/MEMBER", admin, null, 200).path("id").asText());
        // 保留 ADMIN 确保失败取证不会破坏后续鉴权；200 表示真实业务守卫绕行，不能放宽断言。
        call(HttpMethod.DELETE, "/dimension/MEMBER", admin, null, 409);
        assertEquals(
                "MEMBER",
                call(HttpMethod.GET, "/role/MEMBER", admin, null, 200).path("id").asText());
    }

    @Test
    void nativeDimensionDeleteRejectsPositionReferencesAndAllowsDeletionAfterReferenceRemoval() {
        String role = UUID.randomUUID().toString().replace("-", "");
        String organization = UUID.randomUUID().toString().replace("-", "");
        String position = UUID.randomUUID().toString().replace("-", "");
        call(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Referenced role"), 200);
        call(
                HttpMethod.POST,
                "/organization",
                admin,
                Map.of("id", organization, "name", "Native delete guard organization"),
                200);
        call(
                HttpMethod.POST,
                "/position/" + organization + "/_create",
                admin,
                Map.of(
                        "id",
                        position,
                        "name",
                        "Referenced position",
                        "roles",
                        List.of(Map.of("id", role))),
                200);

        call(HttpMethod.DELETE, "/role/" + role, admin, null, 409);
        call(HttpMethod.DELETE, "/api/dimension/" + role, admin, null, 409);
        assertEquals(
                role, call(HttpMethod.GET, "/role/" + role, admin, null, 200).path("id").asText());
        assertEquals(
                role,
                call(HttpMethod.GET, "/position/" + position, admin, null, 200)
                        .path("roles")
                        .get(0)
                        .path("id")
                        .asText());

        call(HttpMethod.DELETE, "/position/" + position, admin, null, 200);
        call(HttpMethod.DELETE, "/dimension/" + role, admin, null, 200);
        call(HttpMethod.GET, "/role/" + role, admin, null, 404);
    }

    @Test
    void nativeRolePutCannotDisableAProtectedRole() {
        JsonNode original = call(HttpMethod.GET, "/dimension/MEMBER", admin, null, 200);
        Map<String, Object> disabled =
                Map.of(
                        "name",
                        original.path("name").asText(),
                        "properties",
                        Map.of("groupId", "default", "state", "disabled"));
        call(
                HttpMethod.PATCH,
                "/role",
                admin,
                Map.of("id", "MEMBER", "name", original.path("name").asText(), "state", "disabled"),
                409);
        try {
            call(HttpMethod.PUT, "/dimension/MEMBER", admin, disabled, 409);
            assertEquals(
                    original.path("properties"),
                    call(HttpMethod.GET, "/dimension/MEMBER", admin, null, 200).path("properties"));
        } finally {
            // 候选实测失败也恢复内置状态，避免后续共享数据库场景受到取证写入影响。
            JsonNode current = call(HttpMethod.GET, "/dimension/MEMBER", admin, null, 200);
            if (!original.path("properties").equals(current.path("properties")))
                call(
                        HttpMethod.PUT,
                        "/dimension/MEMBER",
                        admin,
                        Map.of(
                                "name",
                                original.path("name").asText(),
                                "properties",
                                original.path("properties")),
                        200);
        }
    }

    @Test
    void nativeRoleSaveRequiresAnExistingGroupAndPreservesTheStoredRoleOnFailure() {
        String role = UUID.randomUUID().toString().replace("-", "");
        String missingGroup = UUID.randomUUID().toString().replace("-", "");
        call(
                HttpMethod.POST,
                "/role",
                admin,
                Map.of("id", role, "name", "Group guarded role", "groupId", "default"),
                200);
        JsonNode original = call(HttpMethod.GET, "/dimension/" + role, admin, null, 200);
        call(
                HttpMethod.PATCH,
                "/role",
                admin,
                Map.of("id", role, "name", "Rejected group", "groupId", missingGroup),
                404);
        try {
            call(
                    HttpMethod.PATCH,
                    "/dimension",
                    admin,
                    Map.of(
                            "id",
                            role,
                            "typeId",
                            "role",
                            "name",
                            "Rejected group",
                            "properties",
                            Map.of("groupId", missingGroup, "state", "enabled")),
                    404);
            JsonNode unchanged = call(HttpMethod.GET, "/dimension/" + role, admin, null, 200);
            assertEquals(original.path("name"), unchanged.path("name"));
            assertEquals(original.path("properties"), unchanged.path("properties"));

            call(
                    HttpMethod.PUT,
                    "/dimension/" + role,
                    admin,
                    Map.of(
                            "name",
                            original.path("name").asText(),
                            "properties",
                            Map.of("state", "invalid-state")),
                    400);
            String organization = UUID.randomUUID().toString().replace("-", "");
            call(
                    HttpMethod.POST,
                    "/organization",
                    admin,
                    Map.of("id", organization, "name", "Role type collision organization"),
                    200);
            call(
                    HttpMethod.PATCH,
                    "/dimension",
                    admin,
                    Map.of("id", organization, "typeId", "role", "name", "Rejected type collision"),
                    409);
            assertEquals(
                    "org",
                    call(HttpMethod.GET, "/dimension/" + organization, admin, null, 200)
                            .path("typeId")
                            .asText());
            call(
                    HttpMethod.PUT,
                    "/dimension/" + role,
                    admin,
                    Map.of("typeId", "org", "name", original.path("name").asText()),
                    409);
            assertEquals(
                    "role",
                    call(HttpMethod.GET, "/dimension/" + role, admin, null, 200)
                            .path("typeId")
                            .asText());
            assertNativeCreateAndSaveCanonicalizeDefaultGroup();

            String user =
                    call(
                                    HttpMethod.POST,
                                    "/user/detail/_create",
                                    admin,
                                    Map.of(
                                            "user",
                                            Map.of(
                                                    "name",
                                                    "Native rename user",
                                                    "username",
                                                    "rename-" + UUID.randomUUID(),
                                                    "password",
                                                    PASSWORD),
                                            "roleIdList",
                                            List.of(),
                                            "orgIdList",
                                            List.of(),
                                            "positions",
                                            List.of()),
                                    200)
                            .path("id")
                            .asText();
            Map<String, Object> binding = nativeBinding("role", role, user);
            call(HttpMethod.POST, "/dimension-user", admin, binding, 200);
            call(
                    HttpMethod.PUT,
                    "/dimension/" + role,
                    admin,
                    Map.of(
                            "name",
                            "Native renamed role",
                            "properties",
                            Map.of("groupId", "default_group", "state", "enabled")),
                    200);
            JsonNode renamed = call(HttpMethod.GET, "/dimension/" + role, admin, null, 200);
            assertEquals("default", renamed.path("properties").path("groupId").asText());
            assertEquals(
                    "Native renamed role",
                    call(HttpMethod.GET, "/dimension-user/" + binding.get("id"), admin, null, 200)
                            .path("dimensionName")
                            .asText());
        } finally {
            call(HttpMethod.DELETE, "/role/" + role, admin, null, 200);
        }
    }

    private void assertNativeCreateAndSaveCanonicalizeDefaultGroup() {
        String role = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> properties = Map.of("groupId", "default_group", "state", "enabled");
        call(
                HttpMethod.POST,
                "/dimension",
                admin,
                Map.of(
                        "id",
                        role,
                        "typeId",
                        "role",
                        "name",
                        "Native alias role",
                        "properties",
                        properties),
                200);
        try {
            assertEquals(
                    "default",
                    call(HttpMethod.GET, "/dimension/" + role, admin, null, 200)
                            .path("properties")
                            .path("groupId")
                            .asText());
            call(
                    HttpMethod.PATCH,
                    "/dimension",
                    admin,
                    Map.of(
                            "id",
                            role,
                            "typeId",
                            "role",
                            "name",
                            "Native saved alias role",
                            "properties",
                            properties),
                    200);
            JsonNode saved = call(HttpMethod.GET, "/dimension/" + role, admin, null, 200);
            assertEquals("default", saved.path("properties").path("groupId").asText());
            assertEquals("Native saved alias role", saved.path("name").asText());
        } finally {
            call(HttpMethod.DELETE, "/dimension/" + role, admin, null, 200);
        }
    }

    @Test
    void nativeOrganizationUnbindRemovesOnlyTheSelectedOrganizationsPositionMemberships() {
        String organization = UUID.randomUUID().toString().replace("-", "");
        String otherOrganization = UUID.randomUUID().toString().replace("-", "");
        String position = UUID.randomUUID().toString().replace("-", "");
        String otherPosition = UUID.randomUUID().toString().replace("-", "");
        call(
                HttpMethod.POST,
                "/organization",
                admin,
                Map.of("id", organization, "name", "Unbind selected organization"),
                200);
        call(
                HttpMethod.POST,
                "/organization",
                admin,
                Map.of("id", otherOrganization, "name", "Unbind preserved organization"),
                200);
        call(
                HttpMethod.POST,
                "/position/" + organization + "/_create",
                admin,
                Map.of("id", position, "name", "Selected position", "roles", List.of()),
                200);
        call(
                HttpMethod.POST,
                "/position/" + otherOrganization + "/_create",
                admin,
                Map.of("id", otherPosition, "name", "Preserved position", "roles", List.of()),
                200);
        String user =
                call(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "user",
                                        Map.of(
                                                "name",
                                                "Native unbind user",
                                                "username",
                                                "native-" + UUID.randomUUID(),
                                                "password",
                                                PASSWORD),
                                        "roleIdList",
                                        List.of(),
                                        "orgIdList",
                                        List.of(),
                                        "positions",
                                        List.of()),
                                200)
                        .path("id")
                        .asText();
        List<String> users = List.of(user);
        call(HttpMethod.POST, "/organization/" + organization + "/users/_bind", admin, users, 200);
        call(
                HttpMethod.POST,
                "/organization/" + otherOrganization + "/users/_bind",
                admin,
                users,
                200);
        call(
                HttpMethod.POST,
                "/organization/" + organization + "/position/" + position + "/users/_bind",
                admin,
                users,
                200);
        call(
                HttpMethod.POST,
                "/organization/"
                        + otherOrganization
                        + "/position/"
                        + otherPosition
                        + "/users/_bind",
                admin,
                users,
                200);
        assertEquals(
                2,
                call(HttpMethod.GET, "/user/detail/" + user, admin, null, 200)
                        .path("positions")
                        .size());

        // 先证明现有业务解绑明确清除本组织职位，保留其他组织的独立绑定。
        call(
                HttpMethod.POST,
                "/organization/" + organization + "/users/_unbind",
                admin,
                users,
                200);
        assertOnlyOtherMembership(user, otherOrganization, otherPosition);
        call(HttpMethod.POST, "/organization/" + organization + "/users/_bind", admin, users, 200);
        call(
                HttpMethod.POST,
                "/organization/" + organization + "/position/" + position + "/users/_bind",
                admin,
                users,
                200);
        assertEquals(
                2,
                call(HttpMethod.GET, "/user/detail/" + user, admin, null, 200)
                        .path("positions")
                        .size());
        try {
            call(
                    HttpMethod.POST,
                    "/api/dimension-user/user/org/" + organization + "/_unbind",
                    admin,
                    users,
                    200);
            assertOnlyOtherMembership(user, otherOrganization, otherPosition);
        } finally {
            // 同一测试上下文下清理本场景两组绑定，不依赖执行顺序或遗留授权缓存。
            call(
                    HttpMethod.POST,
                    "/organization/" + organization + "/users/_unbind",
                    admin,
                    users,
                    200);
            call(
                    HttpMethod.POST,
                    "/organization/" + otherOrganization + "/users/_unbind",
                    admin,
                    users,
                    200);
        }
    }

    private void assertOnlyOtherMembership(String user, String organization, String position) {
        JsonNode detail = call(HttpMethod.GET, "/user/detail/" + user, admin, null, 200);
        assertEquals(1, detail.path("orgIdList").size());
        assertEquals(organization, detail.path("orgIdList").get(0).asText());
        assertEquals(1, detail.path("positions").size());
        assertEquals(position, detail.path("positions").get(0).path("id").asText());
    }

    @Test
    void
            nativeManagedBindingsRequireRealMatchingTargetsAndUsersWithoutRejectingExternalDimensions() {
        String user =
                call(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "user",
                                        Map.of(
                                                "name",
                                                "Native binding user",
                                                "username",
                                                "binding-" + UUID.randomUUID(),
                                                "password",
                                                PASSWORD),
                                        "roleIdList",
                                        List.of(),
                                        "orgIdList",
                                        List.of(),
                                        "positions",
                                        List.of()),
                                200)
                        .path("id")
                        .asText();
        String type = UUID.randomUUID().toString().replace("-", "");
        String externalDimension = UUID.randomUUID().toString().replace("-", "");
        call(
                HttpMethod.POST,
                "/dimension-type",
                admin,
                Map.of("id", type, "name", "External audit type"),
                200);
        call(
                HttpMethod.POST,
                "/dimension",
                admin,
                Map.of("id", externalDimension, "typeId", type, "name", "Native binding target"),
                200);
        Map<String, Object> external = nativeBinding(type, externalDimension, user);
        call(HttpMethod.POST, "/dimension-user", admin, external, 200);
        JsonNode saved =
                call(HttpMethod.GET, "/dimension-user/" + external.get("id"), admin, null, 200);
        assertEquals(type, saved.path("dimensionTypeId").asText());
        assertEquals("audit", saved.path("relation").asText());
        call(HttpMethod.DELETE, "/dimension-user/" + external.get("id"), admin, null, 200);
        call(HttpMethod.DELETE, "/dimension/" + externalDimension, admin, null, 200);
        call(HttpMethod.DELETE, "/dimension-type/" + type, admin, null, 200);

        // 同样的合法原生请求形状与权限，只对项目已管理的 type 应用既有存在性和类型规则。
        Map<String, Object> missingTarget =
                nativeBinding("position", UUID.randomUUID().toString(), user);
        Map<String, Object> wrongType = nativeBinding("org", "MEMBER", user);
        Map<String, Object> missingUser =
                nativeBinding("role", "MEMBER", UUID.randomUUID().toString());
        assertAll(
                () -> assertRejectedNativeBinding(missingTarget),
                () -> assertRejectedNativeBinding(wrongType),
                () -> assertRejectedNativeBinding(missingUser));
    }

    private Map<String, Object> nativeBinding(String type, String target, String user) {
        return Map.of(
                "id",
                UUID.randomUUID().toString().replace("-", ""),
                "dimensionTypeId",
                type,
                "dimensionId",
                target,
                "dimensionName",
                "Native binding target",
                "userId",
                user,
                "userName",
                "Native binding user",
                "relation",
                "audit",
                "relationName",
                "Audit relation");
    }

    private void assertRejectedNativeBinding(Map<String, Object> binding) {
        try {
            call(HttpMethod.POST, "/dimension-user", admin, binding, 404);
            call(HttpMethod.GET, "/dimension-user/" + binding.get("id"), admin, null, 404);
        } finally {
            // 取证失败时也清理可能持久化的行，三个候选互相独立且不污染其他场景。
            JsonNode count =
                    call(
                            HttpMethod.POST,
                            "/dimension-user/_count",
                            admin,
                            Map.of(
                                    "terms",
                                    List.of(
                                            Map.of(
                                                    "column",
                                                    "id",
                                                    "termType",
                                                    "eq",
                                                    "value",
                                                    binding.get("id")))),
                            200);
            if (count.asInt() > 0)
                call(HttpMethod.DELETE, "/dimension-user/" + binding.get("id"), admin, null, 200);
        }
    }

    @Test
    void theNativeBindingServiceHasOneActualBeanAndItsManagedValidationIsActive() {
        Map<String, DefaultDimensionUserService> services =
                context.getBeansOfType(DefaultDimensionUserService.class);
        assertEquals(1, services.size());
        assertSame(
                context.getBean(DefaultDimensionUserService.class),
                services.values().iterator().next());
        String currentUser =
                call(HttpMethod.GET, "/user/detail", admin, null, 200).path("id").asText();
        assertFalse(currentUser.isBlank());
        // 行为验证保证单一实例实际执行 managed guard，不依赖代理类名或反射私有字段。
        assertRejectedNativeBinding(nativeBinding("org", "MEMBER", currentUser));
    }

    @Test
    void nativeOrganizationUnbindRollsBackBothMembershipsWhenPositionAfterDeleteFails() {
        String organization = UUID.randomUUID().toString().replace("-", "");
        String position = UUID.randomUUID().toString().replace("-", "");
        call(
                HttpMethod.POST,
                "/organization",
                admin,
                Map.of("id", organization, "name", "Rollback organization"),
                200);
        call(
                HttpMethod.POST,
                "/position/" + organization + "/_create",
                admin,
                Map.of("id", position, "name", "Rollback position", "roles", List.of()),
                200);
        String user =
                call(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "user",
                                        Map.of(
                                                "name",
                                                "Rollback binding user",
                                                "username",
                                                "rollback-" + UUID.randomUUID(),
                                                "password",
                                                PASSWORD),
                                        "roleIdList",
                                        List.of(),
                                        "orgIdList",
                                        List.of(),
                                        "positions",
                                        List.of()),
                                200)
                        .path("id")
                        .asText();
        List<String> users = List.of(user);
        call(HttpMethod.POST, "/organization/" + organization + "/users/_bind", admin, users, 200);
        call(
                HttpMethod.POST,
                "/organization/" + organization + "/position/" + position + "/users/_bind",
                admin,
                users,
                200);
        deletionFailure.arm(organization, position, user);
        try {
            call(
                    HttpMethod.POST,
                    "/dimension-user/user/org/" + organization + "/_unbind",
                    admin,
                    users,
                    500);
            assertTrue(
                    deletionFailure.observedDeletedRows.get(),
                    "Fault must run after both the org SQL and cleanup position SQL actually deleted their rows");
        } finally {
            deletionFailure.disable();
        }
        JsonNode rolledBack = call(HttpMethod.GET, "/user/detail/" + user, admin, null, 200);
        assertEquals(1, rolledBack.path("orgIdList").size());
        assertEquals(organization, rolledBack.path("orgIdList").get(0).asText());
        assertEquals(1, rolledBack.path("positions").size());
        assertEquals(position, rolledBack.path("positions").get(0).path("id").asText());
        call(
                HttpMethod.POST,
                "/dimension-user/user/org/" + organization + "/_unbind",
                admin,
                users,
                200);
        JsonNode unbound = call(HttpMethod.GET, "/user/detail/" + user, admin, null, 200);
        assertEquals(0, unbound.path("orgIdList").size());
        assertEquals(0, unbound.path("positions").size());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureConfiguration {
        @Bean
        PositionDeleteFailure positionDeleteFailure(DefaultDimensionUserService bindings) {
            return new PositionDeleteFailure(bindings);
        }
    }

    /** 仅测试组件：在真实职位绑定删除 SQL 后检查同一事务可见状态，再注入失败。 */
    static class PositionDeleteFailure {
        private final DefaultDimensionUserService bindings;
        private volatile FailureTarget target;
        final AtomicBoolean observedDeletedRows = new AtomicBoolean();

        PositionDeleteFailure(DefaultDimensionUserService bindings) {
            this.bindings = bindings;
        }

        record FailureTarget(String organization, String position, String user) {}

        void arm(String organization, String position, String user) {
            observedDeletedRows.set(false);
            target = new FailureTarget(organization, position, user);
        }

        void disable() {
            target = null;
        }

        @EventListener
        public void afterDelete(EntityDeletedEvent<DimensionUserEntity> event) {
            FailureTarget selected = target;
            if (selected == null
                    || event.getEntity().stream()
                            .noneMatch(
                                    row ->
                                            "position".equals(row.getDimensionTypeId())
                                                    && selected.position()
                                                            .equals(row.getDimensionId())
                                                    && selected.user().equals(row.getUserId())))
                return;
            event.async(
                    bindings.createQuery()
                            .where(DimensionUserEntity::getDimensionTypeId, "org")
                            .and(DimensionUserEntity::getDimensionId, selected.organization())
                            .and(DimensionUserEntity::getUserId, selected.user())
                            .count()
                            .flatMap(
                                    orgCount ->
                                            bindings.createQuery()
                                                    .where(
                                                            DimensionUserEntity::getDimensionTypeId,
                                                            "position")
                                                    .and(
                                                            DimensionUserEntity::getDimensionId,
                                                            selected.position())
                                                    .and(
                                                            DimensionUserEntity::getUserId,
                                                            selected.user())
                                                    .count()
                                                    .flatMap(
                                                            positionCount -> {
                                                                observedDeletedRows.set(
                                                                        orgCount.longValue() == 0
                                                                                && positionCount
                                                                                                .longValue()
                                                                                        == 0);
                                                                return Mono.error(
                                                                        new IllegalStateException(
                                                                                "Injected failure after position binding deletion"));
                                                            })));
        }
    }
}
