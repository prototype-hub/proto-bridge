package com.gaiys.proto.system.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gaiys.proto.ProtoBridgeApplication;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.system.configuration.initializer.DatabaseDescriptionInitializer;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.util.*;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.metadata.parser.TableMetadataParser;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterRequest;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterTableSqlBuilder;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.hswebframework.web.crud.configuration.AutoDDLProcessor;
import org.hswebframework.web.crud.configuration.EasyormProperties;
import org.hswebframework.web.crud.configuration.EntityInfo;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.ReactiveTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class DatabaseDescriptionIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SCHEMA = "description_test";
    private static final String DESCRIPTION = "中文 O'Reilly \\ resource";

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        // EasyORM manages tables; the target database/schema must already exist.
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword());
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        }
        registry.add("DB_SCHEMA", () -> SCHEMA);
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

    @Autowired DatabaseDescriptionService descriptions;
    @Autowired DatabaseDescriptionInitializer startup;
    @Autowired DatabaseClient database;
    @Autowired EntityTableMetadataResolver resolver;
    @Autowired ObjectProvider<AutoDDLProcessor> registrations;
    @Autowired EasyormProperties properties;
    @Autowired DatabaseOperator operator;

    @Autowired
    @Qualifier(TransactionManagers.reactiveTransactionManager)
    ReactiveTransactionManager manager;

    @BeforeEach
    void removeOnlyTestFixturesFromTheIsolatedDatabase() {
        sql(
                "DROP TABLE IF EXISTS description_test.a_comment_fixture,"
                        + " description_test.z_comment_fixture, description_test.z_write_failure,"
                        + " description_test.cold_comment_fixture,"
                        + " description_test.\"future \"\"table\","
                        + " description_test.\"aXcommentXfixture\", public.s_menu CASCADE");
    }

    @Test
    void startupAddsDescriptionsToEveryLocalMappedTableAndPersistentFieldInTheConfiguredSchema() {
        Set<Class<?>> localTypes = new HashSet<>();
        registrations.stream()
                .flatMap(processor -> processor.getEntities().stream())
                .map(EntityInfo::getEntityType)
                .filter(type -> type.getName().startsWith("com.gaiys.proto."))
                .forEach(localTypes::add);
        assertEquals(26, localTypes.size());
        Set<String> tables = new HashSet<>();
        int describedColumns = 0;
        for (Class<?> type : localTypes) {
            var mapping = resolver.resolve(type);
            assertEquals(SCHEMA, mapping.getSchema().getName());
            tables.add(mapping.getName());
            assertEquals(
                    type.getAnnotation(Schema.class).description(),
                    comment(mapping.getName(), null),
                    type.getSimpleName());
            for (var column : mapping.getColumns()) {
                assertNotNull(column.getComment(), type.getSimpleName() + "." + column.getName());
                assertFalse(column.getComment().isBlank());
                assertEquals(
                        column.getComment(),
                        comment(mapping.getName(), column.getName()),
                        type.getSimpleName() + "." + column.getName());
                describedColumns++;
            }
        }
        assertEquals(
                Set.of(
                        "s_system_config",
                        "s_menu",
                        "s_role_group",
                        "s_role_menu_grant",
                        "s_organization",
                        "s_position",
                        "s_user_detail",
                        "s_user_setting",
                        "p_project",
                        "p_version",
                        "p_revision",
                        "p_share",
                        "p_installation",
                        "p_creation_receipt",
                        "p_file_cleanup",
                        "p_project_member",
                        "p_preview_session",
                        "p_review_guest",
                        "p_review_challenge",
                        "p_review_session",
                        "p_review_rate",
                        "p_review_unverified_guest",
                        "p_review_invitation",
                        "p_review_thread",
                        "p_review_message",
                        "p_review_event"),
                tables);
        // 包含两类访客、邀请及页面线程/消息/outbox；历史分享的评审开关仍默认关闭。
        assertEquals(259, describedColumns);
        assertTrue(columnNames("p_review_guest").containsAll(Set.of("enabled", "config_version")));
        for (String name :
                Set.of(
                        "retention_deleted_time",
                        "retention_version_id",
                        "retention_project_time",
                        "retention_project_id")) {
            assertTrue(columnNames("p_installation").contains(name), name);
            assertFalse(comment("p_installation", name).isBlank(), name);
        }
        assertEquals("id", comment("s_user_detail", "id"));
        for (String name : Set.of("parent_id", "path", "_level", "sort_index"))
            assertFalse(comment("s_menu", name).isBlank(), name);
        for (String name :
                Set.of(
                        "access_key",
                        "password_hash",
                        "description",
                        "revision_mode",
                        "revoked",
                        "config_version",
                        "review_allow_comment"))
            assertFalse(comment("p_share", name).isBlank(), name);
        assertFalse(columnNames("s_menu").contains("children"));
        assertFalse(columnNames("s_menu").contains("granted"));
        assertTrue(columnNames("s_user_setting").contains("setting_key"));
        assertEquals(0, descriptions.synchronize().block(TIMEOUT));
        assertTrue(properties.isAutoDdl());
    }

    @Test
    void repeatedStartupRepairsChangedDescriptionsWithoutChangingRowsColumnsOrTableIdentity() {
        String before =
                database.sql(
                                "SELECT c.oid::text AS identity FROM pg_catalog.pg_class c JOIN"
                                        + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE"
                                        + " n.nspname=:schema AND c.relname='s_menu'")
                        .bind("schema", SCHEMA)
                        .map((row, metadata) -> row.get("identity", String.class))
                        .one()
                        .block(TIMEOUT);
        var columns = columnNames("s_menu");
        Long rows =
                database.sql("SELECT count(*) AS total FROM description_test.s_menu")
                        .map((row, metadata) -> row.get("total", Long.class))
                        .one()
                        .block(TIMEOUT);
        sql("COMMENT ON TABLE description_test.s_menu IS 'outdated table'");
        sql("COMMENT ON COLUMN description_test.s_menu.name IS 'outdated column'");
        assertEquals(2, descriptions.synchronize().block(TIMEOUT));
        startup.run(new DefaultApplicationArguments());
        assertEquals(0, descriptions.synchronize().block(TIMEOUT));
        assertEquals(columns, columnNames("s_menu"));
        assertEquals(
                rows,
                database.sql("SELECT count(*) AS total FROM description_test.s_menu")
                        .map((row, metadata) -> row.get("total", Long.class))
                        .one()
                        .block(TIMEOUT));
        assertEquals(
                before,
                database.sql(
                                "SELECT c.oid::text AS identity FROM pg_catalog.pg_class c JOIN"
                                        + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE"
                                        + " n.nspname=:schema AND c.relname='s_menu'")
                        .bind("schema", SCHEMA)
                        .map((row, metadata) -> row.get("identity", String.class))
                        .one()
                        .block(TIMEOUT));
    }

    @Test
    void
            futureRegisteredEntityUsesOfficialMappingAndEscapesIdentifiersAndTextWithoutAStaticCatalog() {
        sql(
                "CREATE TABLE description_test.\"future \"\"table\" (id varchar(64), \"quoted"
                        + " \"\"value\" varchar(64), silent varchar(64))");
        sql("COMMENT ON COLUMN description_test.\"future \"\"table\".silent IS 'keep existing'");
        var service = fixtureService(properties, FutureFixture.class);
        assertEquals(3, service.synchronize().block(TIMEOUT));
        assertEquals(DESCRIPTION, comment("future \"table", null));
        assertEquals(DESCRIPTION, comment("future \"table", "quoted \"value"));
        assertEquals("id", comment("future \"table", "id"));
        assertEquals("keep existing", comment("future \"table", "silent"));
        assertEquals(Set.of("id", "quoted \"value", "silent"), columnNames("future \"table"));
        assertEquals(0, service.synchronize().block(TIMEOUT));
    }

    @Test
    void coldEasyormCreationAndRepeatedAlterEscapeDescriptionsThroughTheRegisteredDdlBuilder() {
        var mapping = resolver.resolve(ColdFixture.class);
        operator.ddl().createOrAlter(mapping).allowAlter(false).commit().reactive().block(TIMEOUT);
        assertEquals(DESCRIPTION, comment("cold_comment_fixture", null));
        assertEquals(DESCRIPTION, comment("cold_comment_fixture", "value"));
        assertEquals(Set.of("id", "value"), columnNames("cold_comment_fixture"));
        sql("COMMENT ON COLUMN description_test.cold_comment_fixture.value IS 'old'");
        // The normal operator caches its known table. An external database alteration needs live
        // metadata, just as startup description synchronization does.
        var actual =
                mapping.getSchema()
                        .<TableMetadataParser>findFeatureNow(TableMetadataParser.id)
                        .parseAllReactive()
                        .cast(RDBTableMetadata.class)
                        .filter(
                                table ->
                                        SCHEMA.equals(table.getSchema().getName())
                                                && mapping.getName().equals(table.getName()))
                        .single()
                        .block(TIMEOUT);
        var alteration =
                mapping.getSchema()
                        .findFeatureNow(AlterTableSqlBuilder.ID)
                        .build(
                                AlterRequest.builder()
                                        .oldTable(actual)
                                        .newTable(mapping)
                                        .allowDrop(false)
                                        .allowAlter(false)
                                        .allowIndexAlter(false)
                                        .build());
        operator.sql().reactive().execute(alteration).block(TIMEOUT);
        assertEquals(DESCRIPTION, comment("cold_comment_fixture", "value"));
        assertEquals(0, fixtureService(properties, ColdFixture.class).synchronize().block(TIMEOUT));
    }

    @Test
    void underscoreInMappedNameNeverMatchesAnUnrelatedTableDuringDescriptionSynchronization() {
        sql(
                "CREATE TABLE description_test.\"aXcommentXfixture\" (id varchar(64), value varchar(64))");
        sql("COMMENT ON TABLE description_test.\"aXcommentXfixture\" IS 'unrelated table'");
        var service = fixtureService(properties, GoodFixture.class);
        var missing =
                assertThrows(
                        IllegalStateException.class, () -> service.synchronize().block(TIMEOUT));
        assertTrue(missing.getMessage().contains("Mapped table is missing"));
        assertEquals("unrelated table", comment("aXcommentXfixture", null));
        sql("CREATE TABLE description_test.a_comment_fixture (id varchar(64), value varchar(64))");
        assertEquals(3, service.synchronize().block(TIMEOUT));
        assertEquals("new table description", comment("a_comment_fixture", null));
        assertEquals("unrelated table", comment("aXcommentXfixture", null));
        assertEquals(0, service.synchronize().block(TIMEOUT));
    }

    @Test
    void missingPersistentTableAndColumnRollbackEarlierCommentsAndNeverCreateOrAlterStructure() {
        sql("CREATE TABLE description_test.a_comment_fixture (id varchar(64), value varchar(64))");
        sql("COMMENT ON TABLE description_test.a_comment_fixture IS 'before'");
        var service = fixtureService(properties, GoodFixture.class, MissingFixture.class);
        var missingTable =
                assertThrows(
                        IllegalStateException.class, () -> service.synchronize().block(TIMEOUT));
        assertTrue(missingTable.getMessage().contains("Mapped table is missing"));
        assertEquals("before", comment("a_comment_fixture", null));
        assertNull(comment("a_comment_fixture", "id"));
        assertEquals(
                0L,
                database.sql(
                                "SELECT count(*) AS total FROM pg_catalog.pg_class c JOIN"
                                        + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE"
                                        + " n.nspname=:schema AND c.relname='z_comment_fixture'")
                        .bind("schema", SCHEMA)
                        .map((row, metadata) -> row.get("total", Long.class))
                        .one()
                        .block(TIMEOUT));
        sql("CREATE TABLE description_test.z_comment_fixture (id varchar(64))");
        var missingColumn =
                assertThrows(
                        IllegalStateException.class, () -> service.synchronize().block(TIMEOUT));
        assertTrue(missingColumn.getMessage().contains("Mapped column is missing"));
        assertEquals("before", comment("a_comment_fixture", null));
        assertEquals(Set.of("id"), columnNames("z_comment_fixture"));
        assertNull(comment("a_comment_fixture", "id"));
    }

    @Test
    void databaseCommentFailureRollsBackAllPriorWrites() {
        sql("CREATE TABLE description_test.a_comment_fixture (id varchar(64), value varchar(64))");
        sql("CREATE TABLE description_test.z_write_failure (id varchar(64))");
        sql("COMMENT ON TABLE description_test.a_comment_fixture IS 'before'");
        assertThrows(
                RuntimeException.class,
                () ->
                        fixtureService(properties, GoodFixture.class, WriteFailureFixture.class)
                                .synchronize()
                                .block(TIMEOUT));
        assertEquals("before", comment("a_comment_fixture", null));
        assertNull(comment("a_comment_fixture", "id"));
        assertNull(comment("z_write_failure", null));
    }

    @Test
    void configuredSchemaIsEnforcedAndSameNamedPublicTableIsUntouched() {
        sql("CREATE TABLE public.s_menu (id varchar(64))");
        sql("COMMENT ON TABLE public.s_menu IS 'public stays unchanged'");
        assertEquals(0, descriptions.synchronize().block(TIMEOUT));
        assertEquals(
                "public stays unchanged",
                database.sql(
                                "SELECT pg_catalog.obj_description(c.oid, 'pg_class') AS"
                                        + " description FROM pg_catalog.pg_class c JOIN"
                                        + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE"
                                        + " n.nspname='public' AND c.relname='s_menu'")
                        .map((row, metadata) -> row.get("description", String.class))
                        .one()
                        .block(TIMEOUT));
        var wrong = new EasyormProperties();
        wrong.setDefaultSchema("public");
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixtureService(wrong, GoodFixture.class)
                                        .synchronize()
                                        .block(TIMEOUT));
        assertTrue(failure.getMessage().contains("outside the configured comment schema"));
    }

    @Test
    void openApiModelsUseSchemaDescriptionsAndKeepSensitivePersistentFieldsHidden()
            throws Exception {
        io.swagger.v3.oas.models.media.Schema<?> model =
                ModelConverters.getInstance()
                        .resolveAsResolvedSchema(new AnnotatedType(PrototypeShareEntity.class))
                        .schema;
        assertEquals(
                PrototypeShareEntity.class.getAnnotation(Schema.class).description(),
                model.getDescription());
        assertEquals(
                PrototypeShareEntity.class
                        .getDeclaredField("projectId")
                        .getAnnotation(Schema.class)
                        .description(),
                model.getProperties().get("projectId").getDescription());
        assertEquals(
                PrototypeShareEntity.class
                        .getDeclaredField("description")
                        .getAnnotation(Schema.class)
                        .description(),
                model.getProperties().get("description").getDescription());
        assertFalse(model.getProperties().containsKey("accessKey"));
        assertFalse(model.getProperties().containsKey("passwordHash"));
        assertFalse(comment("p_share", "access_key").isBlank());
        assertFalse(comment("p_share", "password_hash").isBlank());
    }

    private DatabaseDescriptionService fixtureService(
            EasyormProperties configuration, Class<?>... types) {
        var processor = new AutoDDLProcessor();
        var entities = new HashSet<EntityInfo>();
        for (Class<?> type : types)
            entities.add(new EntityInfo(type, type, String.class, true, false));
        processor.setEntities(entities);
        var beans = new StaticListableBeanFactory();
        beans.addBean("officialRegistration", processor);
        return new DatabaseDescriptionService(
                database,
                resolver,
                beans.getBeanProvider(AutoDDLProcessor.class),
                configuration,
                manager);
    }

    private String comment(String table, String column) {
        String query =
                column == null
                        ? "SELECT pg_catalog.obj_description(c.oid, 'pg_class') AS description FROM"
                                + " pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON"
                                + " n.oid=c.relnamespace WHERE n.nspname=:schema AND c.relname=:table"
                        : "SELECT pg_catalog.col_description(c.oid, a.attnum) AS description FROM"
                                + " pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON"
                                + " n.oid=c.relnamespace JOIN pg_catalog.pg_attribute a ON"
                                + " a.attrelid=c.oid WHERE n.nspname=:schema AND c.relname=:table AND"
                                + " a.attname=:column AND NOT a.attisdropped";
        var statement = database.sql(query).bind("schema", SCHEMA).bind("table", table);
        if (column != null) statement = statement.bind("column", column);
        // Mapping an Optional preserves SQL NULL without returning null to Reactor.
        return statement
                .map((row, metadata) -> Optional.ofNullable(row.get("description", String.class)))
                .one()
                .block(TIMEOUT)
                .orElse(null);
    }

    private Set<String> columnNames(String table) {
        return database.sql(
                        "SELECT a.attname AS name FROM pg_catalog.pg_class c JOIN"
                                + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN"
                                + " pg_catalog.pg_attribute a ON a.attrelid=c.oid WHERE"
                                + " n.nspname=:schema AND c.relname=:table AND a.attnum>0 AND NOT"
                                + " a.attisdropped")
                .bind("schema", SCHEMA)
                .bind("table", table)
                .map((row, metadata) -> row.get("name", String.class))
                .all()
                .collect(HashSet<String>::new, Set::add)
                .block(TIMEOUT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(TIMEOUT);
    }

    @Getter
    @Setter
    @Table(name = "future \"table")
    @Schema(description = DESCRIPTION)
    public static class FutureFixture extends GenericEntity<String> {
        @Column(name = "quoted \"value")
        @JsonIgnore
        @Schema(description = DESCRIPTION)
        private String hidden;

        @Column
        @Schema(description = "")
        private String silent;

        @Transient
        @Schema(description = "仅用于显示")
        private String temporary;
    }

    @Getter
    @Setter
    @Table(name = "cold_comment_fixture")
    @Schema(description = DESCRIPTION)
    public static class ColdFixture extends GenericEntity<String> {
        @Column
        @Schema(description = DESCRIPTION)
        private String value;
    }

    @Getter
    @Setter
    @Table(name = "a_comment_fixture")
    @Schema(description = "new table description")
    public static class GoodFixture extends GenericEntity<String> {
        @Column
        @Schema(description = "new value description")
        private String value;
    }

    @Getter
    @Setter
    @Table(name = "z_comment_fixture")
    @Schema(description = "required table description")
    public static class MissingFixture extends GenericEntity<String> {
        @Column
        @Schema(description = "required column description")
        private String missing;
    }

    @Table(name = "z_write_failure")
    @Schema(description = "invalid\0description")
    public static class WriteFailureFixture extends GenericEntity<String> {}
}
