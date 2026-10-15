package com.gaiys.proto;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.hswebframework.web.authorization.basic.define.DefaultBasicAuthorizeDefinition;
import org.hswebframework.web.authorization.define.AuthorizeDefinitionInitializedEvent;
import org.hswebframework.web.authorization.define.ResourceDefinition;
import org.hswebframework.web.i18n.LocaleUtils;
import org.hswebframework.web.system.authorization.api.entity.ActionEntity;
import org.hswebframework.web.system.authorization.api.entity.PermissionEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultPermissionService;
import org.hswebframework.web.system.authorization.defaults.service.PermissionSynchronization;
import org.hswebframework.web.system.authorization.defaults.webflux.WebFluxDimensionTypeController;
import org.hswebframework.web.system.authorization.defaults.webflux.WebFluxDimensionUserController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/** 验证真实 hsweb 权限同步与 PostgreSQL 增量迁移的中文名称和保留边界。 */
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
@Import(PermissionChineseNamesIntegrationTest.CodeAddedPermissionController.class)
class PermissionChineseNamesIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final Set<String> BUILTIN_PERMISSIONS =
            Set.of(
                    "prototype-hub-project",
                    "prototype-hub-version",
                    "prototype-hub-share",
                    "menu",
                    "user",
                    "dimension",
                    "permission",
                    "autz-setting",
                    "user-token",
                    "role",
                    "organization",
                    "position",
                    "dictionary",
                    "system-config",
                    "file",
                    "system-logger",
                    "access-logger");

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
    }

    @Autowired DefaultPermissionService permissions;
    @Autowired PermissionSynchronization synchronization;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired MessageSource messages;

    @Test
    void
            startupPersistsChineseNamesForEveryBuiltinPermissionAndActionIncludingDependencyControllers() {
        // hsweb 5.0.1 的维度类型和维度用户接口共用 dimension 资源；历史 SQL 的目录项另测。
        assertEquals(
                "dimension",
                WebFluxDimensionTypeController.class.getAnnotation(Resource.class).id());
        assertEquals(
                "dimension",
                WebFluxDimensionUserController.class.getAnnotation(Resource.class).id());
        awaitStartupSynchronization();
        List<PermissionEntity> records =
                permissions.createQuery().fetch().collectList().block(TIMEOUT);
        assertNotNull(records);
        Map<String, PermissionEntity> byId =
                records.stream()
                        .collect(Collectors.toMap(PermissionEntity::getId, Function.identity()));
        assertTrue(byId.keySet().containsAll(BUILTIN_PERMISSIONS));
        assertEquals("数据字典", byId.get("dictionary").getName());
        assertEquals("代码新增权限", byId.get("code-added-permission").getName());
        // Inspect the entire synchronized set so future native controller resources cannot be
        // missed.
        for (PermissionEntity permission : records) {
            assertTrue(
                    containsChinese(permission.getName()),
                    permission.getId() + ": " + permission.getName());
            assertNotNull(permission.getActions(), permission.getId());
            for (ActionEntity action : permission.getActions()) {
                assertTrue(
                        containsChinese(action.getName()),
                        permission.getId() + "/" + action.getAction() + ": " + action.getName());
            }
        }
        assertEquals(
                "代码新增权限",
                messages.getMessage(
                        "hswebframework.web.system.permission.code-added-permission",
                        null,
                        Locale.CHINESE));
        assertEquals(
                "Code added permission",
                messages.getMessage(
                        "hswebframework.web.system.permission.code-added-permission",
                        null,
                        Locale.ENGLISH));
    }

    @Test
    void realSynchronizationUsesChineseUnderEnglishLocaleAndPreservesExistingCustomNamesAndGrants()
            throws Exception {
        awaitStartupSynchronization();
        database.sql(
                        "INSERT INTO s_permission(id,name,status,actions,properties) VALUES ('runtime-existing-permission','自定义权限名称',0,'[{\"action\":\"review\",\"name\":\"自定义审核名称\",\"describe\":\"原有描述\"}]','{\"custom\":\"keep\"}')")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql(
                        "INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('runtime-custom-grant','runtime-existing-permission','role','ADMIN',0,'[\"review\"]',17,true)")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        String grantBefore = grantSnapshot();
        var definition = new DefaultBasicAuthorizeDefinition();
        definition
                .getResources()
                .addResource(
                        ResourceDefinition.of("runtime-chinese-permission", "Runtime permission")
                                .addAction("review", "Review")
                                .addAction("save", "save"),
                        true);
        definition
                .getResources()
                .addResource(
                        ResourceDefinition.of(
                                        "runtime-existing-permission",
                                        "Default existing permission")
                                .addAction("review", "Review")
                                .addAction("save", "save"),
                        true);
        synchronization.handleResourceParseEvent(
                new AuthorizeDefinitionInitializedEvent(List.of(definition)));
        var previous = LocaleContextHolder.getLocaleContext();
        try {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertEquals(Locale.ENGLISH, LocaleUtils.current());
            synchronization.run();
            await().atMost(TIMEOUT)
                    .untilAsserted(
                            () -> {
                                PermissionEntity created =
                                        permissions
                                                .findById("runtime-chinese-permission")
                                                .block(TIMEOUT);
                                assertNotNull(created);
                                assertEquals("运行期新增权限", created.getName());
                                assertEquals("审核", action(created, "review").getName());
                                assertEquals("保存", action(created, "save").getName());
                                PermissionEntity existing =
                                        permissions
                                                .findById("runtime-existing-permission")
                                                .block(TIMEOUT);
                                assertNotNull(existing);
                                assertEquals("自定义权限名称", existing.getName());
                                assertEquals((byte) 0, existing.getStatus());
                                assertEquals("自定义审核名称", action(existing, "review").getName());
                                assertEquals("原有描述", action(existing, "review").getDescribe());
                                assertEquals("保存", action(existing, "save").getName());
                                assertEquals(Map.of("custom", "keep"), existing.getProperties());
                            });
            assertEquals(Locale.ENGLISH, LocaleUtils.current());
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
        assertEquals(grantBefore, grantSnapshot());
    }

    @Test
    void v16CorrectsOnlyBuiltinDefaultNamesAndPreservesCustomLabelsActionPayloadsAndAuthorizations()
            throws Exception {
        String schema = "permission_names_" + UUID.randomUUID().toString().replace("-", "");
        migrations(schema, "15").migrate();
        try (var connection =
                DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            connection.setSchema(schema);
            try (var sql = connection.createStatement()) {
                sql.execute(
                        "UPDATE s_permission SET name='自定义用户权限',status=0,actions='[{\"action\":\"query\",\"name\":\"query\",\"extra\":\"keep\"},{\"action\":\"save\",\"name\":\"自定义保存\",\"describe\":\"原有描述\",\"i18nMessages\":{\"name\":{\"zh\":\"自定义保存\"}}},{\"action\":\"custom\",\"name\":\"custom\",\"custom\":true}]' WHERE id='user'");
                sql.execute(
                        "UPDATE s_permission SET name='hswebframework.web.system.permission.protohub-project',properties='{\"custom\":\"keep\"}' WHERE id='protohub-project'");
                sql.execute(
                        "UPDATE s_permission SET actions='[{\"action\":\"query\",\"name\":\"hswebframework.web.system.action.query\"},{\"action\":\"save\",\"name\":\"品牌素材保存\"}]',name='品牌素材上传' WHERE id='file'");
                sql.execute(
                        "INSERT INTO s_permission(id,name,status,actions) VALUES ('custom-permission','custom-permission',0,'[{\"action\":\"query\",\"name\":\"query\"}]')");
                String grantsBefore =
                        snapshot(
                                sql,
                                "SELECT jsonb_agg(to_jsonb(grants) ORDER BY id)::text FROM s_autz_setting_info grants");
                String metadataBefore =
                        snapshot(
                                sql,
                                "SELECT jsonb_agg(to_jsonb(permission) - 'name' - 'actions' ORDER BY id)::text FROM s_permission permission");
                JsonNode userActionsBefore =
                        mapper.readTree(
                                snapshot(sql, "SELECT actions FROM s_permission WHERE id='user'"));

                migrations(schema, "16").migrate();
                assertEquals(
                        grantsBefore,
                        snapshot(
                                sql,
                                "SELECT jsonb_agg(to_jsonb(grants) ORDER BY id)::text FROM s_autz_setting_info grants"));
                assertEquals(
                        metadataBefore,
                        snapshot(
                                sql,
                                "SELECT jsonb_agg(to_jsonb(permission) - 'name' - 'actions' ORDER BY id)::text FROM s_permission permission"));
                assertEquals(
                        "原型项目管理",
                        snapshot(sql, "SELECT name FROM s_permission WHERE id='protohub-project'"));
                assertEquals(
                        "系统日志",
                        snapshot(sql, "SELECT name FROM s_permission WHERE id='system-logger'"));
                assertEquals(
                        "访问日志",
                        snapshot(sql, "SELECT name FROM s_permission WHERE id='access-logger'"));
                assertEquals(
                        "自定义用户权限", snapshot(sql, "SELECT name FROM s_permission WHERE id='user'"));
                assertEquals(
                        "品牌素材上传", snapshot(sql, "SELECT name FROM s_permission WHERE id='file'"));
                ((ObjectNode) userActionsBefore.get(0)).put("name", "查询");
                assertEquals(
                        userActionsBefore,
                        mapper.readTree(
                                snapshot(sql, "SELECT actions FROM s_permission WHERE id='user'")));
                JsonNode fileActions =
                        mapper.readTree(
                                snapshot(sql, "SELECT actions FROM s_permission WHERE id='file'"));
                assertEquals("查询", fileActions.get(0).path("name").asText());
                assertEquals("品牌素材保存", fileActions.get(1).path("name").asText());
                assertEquals(
                        "custom-permission",
                        snapshot(
                                sql, "SELECT name FROM s_permission WHERE id='custom-permission'"));
                assertEquals(
                        "query",
                        mapper.readTree(
                                        snapshot(
                                                sql,
                                                "SELECT actions FROM s_permission WHERE id='custom-permission'"))
                                .get(0)
                                .path("name")
                                .asText());
                try (var result =
                        sql.executeQuery(
                                "SELECT name,actions FROM s_permission WHERE id <> 'custom-permission'")) {
                    while (result.next()) {
                        assertTrue(containsChinese(result.getString(1)), result.getString(1));
                        for (JsonNode action : mapper.readTree(result.getString(2))) {
                            if (!action.path("action").asText().equals("custom")) {
                                assertTrue(
                                        containsChinese(action.path("name").asText()),
                                        action.toString());
                            }
                        }
                    }
                }
            }
        }
    }

    private void awaitStartupSynchronization() {
        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> {
                            var permission =
                                    permissions.findById("code-added-permission").block(TIMEOUT);
                            assertNotNull(permission);
                            assertEquals("代码新增权限", permission.getName());
                            assertEquals("审核", action(permission, "review").getName());
                        });
    }

    private String grantSnapshot() {
        return database.sql(
                        "SELECT row_to_json(grants)::text AS snapshot FROM s_autz_setting_info grants WHERE id='runtime-custom-grant'")
                .map((row, metadata) -> row.get("snapshot", String.class))
                .one()
                .block(TIMEOUT);
    }

    private static ActionEntity action(PermissionEntity permission, String id) {
        return permission.getActions().stream()
                .filter(action -> action.getAction().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private Flyway migrations(String schema, String target) {
        return Flyway.configure()
                .locations("classpath:test-db/migration")
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load();
    }

    private static String snapshot(java.sql.Statement sql, String query)
            throws java.sql.SQLException {
        try (var result = sql.executeQuery(query)) {
            assertTrue(result.next());
            String value = result.getString(1);
            assertFalse(result.next());
            return value;
        }
    }

    private static boolean containsChinese(String value) {
        return value != null
                && value.codePoints()
                        .anyMatch(
                                codePoint ->
                                        Character.UnicodeScript.of(codePoint)
                                                == Character.UnicodeScript.HAN);
    }

    @TestComponent
    @RestController
    @RequestMapping("/__test/chinese-permission")
    @Authorize
    @Resource(id = "code-added-permission", name = "Code added permission")
    public static class CodeAddedPermissionController {
        @GetMapping
        @ResourceAction(id = "review", name = "Review")
        public Mono<Void> review() {
            return Mono.empty();
        }
    }
}
