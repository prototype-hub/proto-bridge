package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.system.configuration.initializer.AdministratorInitializer;
import com.gaiys.proto.system.configuration.initializer.MenuStartupInitializer;
import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.SystemBootstrapService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.configuration.AutoDDLProcessor;
import org.hswebframework.web.crud.configuration.EasyormProperties;
import org.hswebframework.web.crud.configuration.EntityInfo;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 使用独立 PostgreSQL 验证无运行 SQL 的冷启动、安全增量结构和初始化保留边界。 */
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
class EasyormSchemaIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
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
        registry.add("proto.admin.password", () -> UUID.randomUUID().toString());
    }

    @Autowired ObjectProvider<AutoDDLProcessor> registrations;
    @Autowired EntityTableMetadataResolver resolver;
    @Autowired EasyormProperties properties;
    @Autowired Environment environment;
    @Autowired SystemBootstrapService bootstrap;
    @Autowired AdministratorInitializer administrator;
    @Autowired MenuStartupInitializer menus;
    @Autowired ApplicationContext context;

    @BeforeEach
    void removeOnlyPrivateSchemaFixture() throws Exception {
        execute("DROP TABLE IF EXISTS public.easyorm_addition_fixture");
    }

    @Test
    void independentAdministratorInitializersCreateAndBindTheMissingAccountExactlyOnce()
            throws Exception {
        var user = scalar("SELECT id FROM s_user WHERE username='admin'");
        execute("DELETE FROM s_dimension_user WHERE user_id='" + user + "'");
        execute("DELETE FROM s_user WHERE id='" + user + "'");
        var independent =
                context.getAutowireCapableBeanFactory().createBean(AdministratorInitializer.class);
        Flux.merge(
                        Mono.fromRunnable(
                                        () -> administrator.run(new DefaultApplicationArguments()))
                                .subscribeOn(Schedulers.boundedElastic()),
                        Mono.fromRunnable(() -> independent.run(new DefaultApplicationArguments()))
                                .subscribeOn(Schedulers.boundedElastic()))
                .then()
                .block(TIMEOUT);
        assertEquals("1", scalar("SELECT count(*)::text FROM s_user WHERE username='admin'"));
        assertEquals(
                "1",
                scalar(
                        "SELECT count(*)::text FROM s_dimension_user d JOIN s_user u ON u.id=d.user_id"
                                + " WHERE u.username='admin' AND d.dimension_type_id='role' AND d.dimension_id='ADMIN'"));
        String unchanged =
                scalar("SELECT row_to_json(u)::text FROM s_user u WHERE username='admin'");
        administrator.run(new DefaultApplicationArguments());
        assertEquals(
                unchanged,
                scalar("SELECT row_to_json(u)::text FROM s_user u WHERE username='admin'"));
    }

    @Test
    void coldStartCreatesEveryRegisteredEntityAndItsColumnsWithoutFlyway() throws Exception {
        assertTrue(properties.isAutoDdl());
        assertEquals("false", environment.getProperty("spring.flyway.enabled"));
        assertNull(getClass().getClassLoader().getResource("db/migration/V1__system_schema.sql"));
        assertNull(getClass().getClassLoader().getResource("db/migration/V2__protohub_schema.sql"));
        Set<Class<?>> types = new HashSet<>();
        registrations.stream()
                .flatMap(processor -> processor.getEntities().stream())
                .map(EntityInfo::getEntityType)
                .forEach(types::add);
        Set<String> expected = new HashSet<>();
        for (Class<?> type : types) {
            var mapping = resolver.resolve(type);
            expected.add(mapping.getName());
            assertEquals("public", mapping.getSchema().getName());
            Set<String> columns =
                    strings(
                            "SELECT column_name FROM information_schema.columns WHERE"
                                    + " table_schema='public' AND table_name='"
                                    + mapping.getName()
                                    + "'");
            assertFalse(columns.isEmpty(), mapping.getName());
            for (var column : mapping.getColumns())
                assertTrue(
                        columns.contains(column.getName()),
                        mapping.getName() + "." + column.getName());
        }
        assertTrue(expected.size() >= 20, expected.toString());
        assertEquals(
                expected,
                strings(
                        "SELECT table_name FROM information_schema.tables WHERE"
                                + " table_schema='public' AND table_type='BASE TABLE'"));
        assertFalse(expected.contains("flyway_schema_history"));
        assertEquals(
                "2000",
                scalar(
                        "SELECT character_maximum_length::text FROM information_schema.columns"
                                + " WHERE table_schema='public' AND table_name='p_share' AND"
                                + " column_name='description'"));
        assertFalse(
                scalar(
                                "SELECT col_description('public.p_share'::regclass, attnum) FROM"
                                        + " pg_attribute WHERE attrelid='public.p_share'::regclass AND"
                                        + " attname='description'")
                        .isBlank());
        assertEquals(
                "128",
                scalar(
                        "SELECT character_maximum_length::text FROM information_schema.columns"
                                + " WHERE table_schema='public' AND table_name='s_dimension' AND"
                                + " column_name='name'"));
        assertEquals(
                "64",
                scalar(
                        "SELECT character_maximum_length::text FROM information_schema.columns"
                                + " WHERE table_schema='public' AND table_name='s_autz_setting_info'"
                                + " AND column_name='dimension_target'"));
        assertEquals("14", scalar("SELECT count(*)::text FROM s_menu"));
        for (String table :
                List.of(
                        "p_project",
                        "p_version",
                        "p_revision",
                        "p_share",
                        "p_file_cleanup",
                        "p_creation_receipt",
                        "s_organization",
                        "s_position",
                        "s_dictionary",
                        "s_dictionary_item"))
            assertEquals("0", scalar("SELECT count(*)::text FROM " + table), table);
        assertEquals("1", scalar("SELECT count(*)::text FROM p_installation"));
        for (String table : List.of("p_installation", "p_creation_receipt"))
            assertFalse(
                    scalar("SELECT obj_description('public." + table + "'::regclass)").isBlank());
    }

    @Test
    void freshAndAddedMenuCodeIndexesKeepPartialUniquenessAndDatabaseDefaults() throws Exception {
        assertPartialMenuIndex();
        assertMenuCodes("fresh-");
        execute("DROP INDEX public.proto_menu_code_idx");
        synchronize(MenuEntity.class);
        assertPartialMenuIndex();
        assertMenuCodes("added-");
        assertEquals(
                "2",
                scalar(
                        "SELECT count(*)::text FROM pg_indexes WHERE schemaname='public' AND"
                                + " tablename='s_dictionary_item' AND indexname IN"
                                + " ('s_dictionary_item_dict_id_value_key','s_dictionary_item_dict_id_ordinal_key')"));
        assertEquals(
                "2",
                scalar(
                        "SELECT count(*)::text FROM pg_index i JOIN pg_class c ON"
                                + " c.oid=i.indexrelid WHERE c.relname IN"
                                + " ('s_dictionary_item_dict_id_value_key','s_dictionary_item_dict_id_ordinal_key')"
                                + " AND i.indisunique"));
    }

    @Test
    void repeatedAutomaticDdlAddsNullableColumnAndIndexWithoutChangingExistingDataOrTypes()
            throws Exception {
        execute(
                "CREATE TABLE public.easyorm_addition_fixture (id varchar(64) PRIMARY KEY, title"
                        + " varchar(512) NOT NULL, legacy_number bigint, extra_value text)");
        execute("CREATE INDEX addition_existing_idx ON public.easyorm_addition_fixture(title)");
        execute(
                "INSERT INTO public.easyorm_addition_fixture VALUES"
                        + " ('retained',repeat('x',400),123,'keep extra')");
        String tableIdentity =
                scalar("SELECT 'public.easyorm_addition_fixture'::regclass::oid::text");
        String indexDefinition =
                scalar(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND"
                                + " indexname='addition_existing_idx'");
        synchronize(AdditionFixture.class);
        synchronize(AdditionFixture.class);
        assertEquals(
                tableIdentity,
                scalar("SELECT 'public.easyorm_addition_fixture'::regclass::oid::text"));
        assertEquals(
                "512",
                scalar(
                        "SELECT character_maximum_length::text FROM information_schema.columns"
                                + " WHERE table_schema='public' AND"
                                + " table_name='easyorm_addition_fixture' AND column_name='title'"));
        assertEquals(
                "NO",
                scalar(
                        "SELECT is_nullable FROM information_schema.columns WHERE"
                                + " table_schema='public' AND table_name='easyorm_addition_fixture' AND"
                                + " column_name='title'"));
        assertEquals(
                "bigint",
                scalar(
                        "SELECT data_type FROM information_schema.columns WHERE"
                                + " table_schema='public' AND table_name='easyorm_addition_fixture' AND"
                                + " column_name='legacy_number'"));
        assertEquals(
                "YES",
                scalar(
                        "SELECT is_nullable FROM information_schema.columns WHERE"
                                + " table_schema='public' AND table_name='easyorm_addition_fixture' AND"
                                + " column_name='future_value'"));
        assertEquals(
                "400|123|keep extra|true",
                scalar(
                        "SELECT"
                                + " length(title)||'|'||legacy_number||'|'||extra_value||'|'||(future_value"
                                + " IS NULL)::text FROM public.easyorm_addition_fixture WHERE"
                                + " id='retained'"));
        assertEquals(
                indexDefinition,
                scalar(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND"
                                + " indexname='addition_existing_idx'"));
        assertEquals(
                "1",
                scalar(
                        "SELECT count(*)::text FROM pg_indexes WHERE schemaname='public' AND"
                                + " indexname='addition_future_idx'"));
    }

    @Test
    void repeatedInitializationPreservesCredentialsCustomMenusDisabledRolesAndRevokedGrants()
            throws Exception {
        String user = scalar("SELECT row_to_json(u)::text FROM s_user u WHERE username='admin'");
        String role = scalar("SELECT properties FROM s_dimension WHERE id='MEMBER'");
        String revoked =
                scalar(
                        "SELECT row_to_json(s)::text FROM s_autz_setting_info s WHERE"
                                + " dimension_type='role' AND dimension_target='MEMBER' AND"
                                + " permission='prototype-hub-share'");
        String menu = scalar("SELECT name FROM s_menu WHERE id='system-basis'");
        execute("UPDATE s_menu SET name='用户定制菜单' WHERE id='system-basis'");
        execute(
                "UPDATE s_dimension SET"
                        + " properties='{\"state\":\"disabled\",\"groupId\":\"default\",\"custom\":true}'"
                        + " WHERE id='MEMBER'");
        execute(
                "DELETE FROM s_autz_setting_info WHERE dimension_type='role' AND"
                        + " dimension_target='MEMBER' AND permission='prototype-hub-share'");
        Set<String> grants =
                strings(
                        "SELECT row_to_json(s)::text FROM s_autz_setting_info s WHERE"
                                + " dimension_type='role' AND dimension_target='MEMBER'");
        try {
            bootstrap.initialize().block(TIMEOUT);
            administrator.run(new DefaultApplicationArguments());
            menus.run(new DefaultApplicationArguments());
            bootstrap.initialize().block(TIMEOUT);
            assertEquals(
                    user,
                    scalar("SELECT row_to_json(u)::text FROM s_user u WHERE username='admin'"));
            assertEquals("用户定制菜单", scalar("SELECT name FROM s_menu WHERE id='system-basis'"));
            assertEquals(
                    "disabled",
                    scalar(
                            "SELECT properties::jsonb->>'state' FROM s_dimension WHERE"
                                    + " id='MEMBER'"));
            assertEquals(
                    grants,
                    strings(
                            "SELECT row_to_json(s)::text FROM s_autz_setting_info s WHERE"
                                    + " dimension_type='role' AND dimension_target='MEMBER'"));
            assertEquals(
                    "0",
                    scalar(
                            "SELECT count(*)::text FROM s_autz_setting_info WHERE"
                                    + " dimension_target='MEMBER' AND"
                                    + " permission='prototype-hub-share'"));
            assertEquals("14", scalar("SELECT count(*)::text FROM s_menu"));
            assertEquals(
                    "0",
                    scalar(
                            "SELECT count(*)::text FROM s_autz_setting_info WHERE"
                                    + " dimension_target='MEMBER' AND permission IN"
                                    + " ('menu','permission','dictionary','dimension')"));
            assertEquals(
                    "1",
                    scalar(
                            "SELECT count(*)::text FROM s_autz_setting_info WHERE"
                                    + " dimension_target='ADMIN' AND permission='menu' AND state=1 AND"
                                    + " actions::jsonb ? 'save'"));
        } finally {
            try (var connection = connection();
                    var restoreRole =
                            connection.prepareStatement(
                                    "UPDATE s_dimension SET properties=? WHERE id='MEMBER'");
                    var restoreMenu =
                            connection.prepareStatement(
                                    "UPDATE s_menu SET name=? WHERE id='system-basis'");
                    var restoreGrant =
                            connection.prepareStatement(
                                    "INSERT INTO s_autz_setting_info SELECT * FROM"
                                            + " json_populate_record(NULL::s_autz_setting_info,"
                                            + " ?::json)")) {
                restoreRole.setString(1, role);
                restoreRole.executeUpdate();
                restoreMenu.setString(1, menu);
                restoreMenu.executeUpdate();
                restoreGrant.setString(1, revoked);
                restoreGrant.executeUpdate();
            }
        }
    }

    private void assertPartialMenuIndex() throws Exception {
        String definition =
                scalar(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND"
                                + " indexname='proto_menu_code_idx'");
        assertTrue(definition.contains("UNIQUE"), definition);
        assertTrue(definition.contains("WHERE"), definition);
        assertTrue(definition.contains("IS NOT NULL"), definition);
    }

    private void assertMenuCodes(String prefix) throws Exception {
        try {
            execute(
                    "INSERT INTO s_menu(id,name,enabled,code) VALUES ('"
                            + prefix
                            + "blank1','空一',true,''),('"
                            + prefix
                            + "blank2','空二',true,''),('"
                            + prefix
                            + "null1','空三',true,NULL),('"
                            + prefix
                            + "null2','空四',true,NULL),('"
                            + prefix
                            + "code','唯一',true,'"
                            + prefix
                            + "unique')");
            assertEquals(
                    "iot|unsupported",
                    scalar(
                            "SELECT owner||'|'||access_support FROM s_menu WHERE id='"
                                    + prefix
                                    + "code'"));
            SQLException conflict =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    execute(
                                            "INSERT INTO s_menu(id,name,enabled,code) VALUES ('"
                                                    + prefix
                                                    + "conflict','冲突',true,'"
                                                    + prefix
                                                    + "unique')"));
            assertEquals("23505", conflict.getSQLState());
        } finally {
            execute("DELETE FROM s_menu WHERE id LIKE '" + prefix + "%'");
        }
    }

    private void synchronize(Class<?> type) {
        AutoDDLProcessor existing = registrations.orderedStream().findFirst().orElseThrow();
        var schema = existing.getOperator().getMetadata().getSchema("public").orElseThrow();
        String tableName = type.getAnnotation(Table.class).name();
        var original = schema.getTable(tableName, false);
        try {
            // 只刷新本次测试的物理表，避免覆盖其他运行中仓库的实体事件映射。
            schema.removeTableOrView(tableName);
            schema.getTableReactive(tableName, true).block(TIMEOUT);
            var processor = new AutoDDLProcessor();
            processor.setEntities(Set.of(new EntityInfo(type, type, String.class, true, false)));
            processor.setOperator(existing.getOperator());
            processor.setProperties(properties);
            processor.setResolver(resolver);
            processor.setEntityFactory(existing.getEntityFactory());
            processor.setEventPublisher(existing.getEventPublisher());
            processor.setReactive(true);
            processor.afterPropertiesSet();
        } finally {
            // 物理 DDL 保持生效，运行中的仓库继续使用原有实体映射。
            schema.removeTableOrView(tableName);
            original.ifPresent(schema::addTable);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    private void execute(String statement) throws SQLException {
        try (var connection = connection();
                var sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    private String scalar(String statement) throws SQLException {
        try (var connection = connection();
                var sql = connection.createStatement();
                var rows = sql.executeQuery(statement)) {
            assertTrue(rows.next(), statement);
            String result = rows.getString(1);
            assertFalse(rows.next(), statement);
            return result;
        }
    }

    private Set<String> strings(String statement) throws SQLException {
        try (var connection = connection();
                var sql = connection.createStatement();
                var rows = sql.executeQuery(statement)) {
            Set<String> result = new HashSet<>();
            while (rows.next()) result.add(rows.getString(1));
            return result;
        }
    }

    @Getter
    @Setter
    @Table(
            name = "easyorm_addition_fixture",
            indexes = {
                @Index(name = "addition_existing_idx", columnList = "future_value"),
                @Index(name = "addition_future_idx", columnList = "future_value")
            })
    @Schema(description = "安全增量测试表")
    public static class AdditionFixture extends GenericEntity<String> {
        @Column(length = 32)
        @Schema(description = "映射缩短不修改已有列")
        private String title;

        @Column(name = "legacy_number", length = 64)
        @Schema(description = "映射类型变化不转换旧数据")
        private String legacyNumber;

        @Column(name = "future_value", length = 128)
        @Schema(description = "自动添加的可空字段")
        private String futureValue;
    }
}
