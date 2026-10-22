package com.gaiys.proto.system.service;

import com.gaiys.proto.system.configuration.builder.DatabaseMetadataDdl;
import com.gaiys.proto.system.configuration.customizer.SchemaTableMetadataCustomizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.metadata.parser.TableMetadataParser;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.hswebframework.web.crud.configuration.AutoDDLProcessor;
import org.hswebframework.web.crud.configuration.EasyormProperties;
import org.hswebframework.web.crud.configuration.EntityInfo;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Synchronizes comments for officially registered local mappings without changing table structure.
 *
 * <p>EasyORM creates missing tables and columns before this startup synchronization. Missing
 * mappings fail the transaction, while absent descriptions leave existing comments intact. Catalog
 * reads and comment writes share the named reactive transaction and configured schema.
 */
@Service
public class DatabaseDescriptionService {
    private final DatabaseClient database;
    private final EntityTableMetadataResolver resolver;
    private final ObjectProvider<AutoDDLProcessor> registrations;
    private final EasyormProperties properties;
    private final TransactionalOperator transactions;

    public DatabaseDescriptionService(
            DatabaseClient database,
            EntityTableMetadataResolver resolver,
            ObjectProvider<AutoDDLProcessor> registrations,
            EasyormProperties properties,
            @Qualifier(TransactionManagers.reactiveTransactionManager)
                    ReactiveTransactionManager manager) {
        this.database = database;
        this.resolver = resolver;
        this.registrations = registrations;
        this.properties = properties;
        this.transactions = TransactionalOperator.create(manager);
    }

    /** Returns the number of table/column descriptions changed in this transaction. */
    public Mono<Integer> synchronize() {
        return Mono.defer(
                        () -> {
                            String schema = properties.getDefaultSchema();
                            if (schema == null || schema.isBlank())
                                return Mono.error(
                                        new IllegalStateException(
                                                "Database comment schema is required"));
                            List<RDBTableMetadata> tables = localTables(schema);
                            if (tables.isEmpty()) return Mono.just(0);
                            Map<String, RDBTableMetadata> mappings = new TreeMap<>();
                            tables.forEach(table -> mappings.put(table.getName(), table));
                            var parser =
                                    tables.getFirst()
                                            .getSchema()
                                            .<TableMetadataParser>findFeatureNow(
                                                    TableMetadataParser.id);
                            // parseByNameReactive 使用 LIKE 并将请求名重新写入结果，会把下划线匹配的旁表
                            // 混入映射。仅启动同步时读取一次官方真实元数据，再按实际 schema/name 精确过滤。
                            return parser.parseAllReactive()
                                    .cast(RDBTableMetadata.class)
                                    .filter(
                                            table ->
                                                    table.getSchema() != null
                                                            && schema.equals(
                                                                    table.getSchema().getName())
                                                            && mappings.containsKey(
                                                                    table.getName()))
                                    .collectMap(RDBTableMetadata::getName)
                                    .flatMap(
                                            actual ->
                                                    Flux.fromIterable(tables)
                                                            .concatMap(
                                                                    table -> {
                                                                        var current =
                                                                                actual.get(
                                                                                        table
                                                                                                .getName());
                                                                        if (current == null)
                                                                            return Mono
                                                                                    .<Integer>error(
                                                                                            missingTable(
                                                                                                    schema,
                                                                                                    table
                                                                                                            .getName()));
                                                                        return synchronizeTable(
                                                                                table, current);
                                                                    })
                                                            .reduce(0, Integer::sum));
                        })
                .as(transactions::transactional);
    }

    private List<RDBTableMetadata> localTables(String schema) {
        // AutoDDLProcessor exposes the registrar's EntityInfo collection after schema
        // synchronization.
        // No duplicated class catalog or classpath scan is needed when a future entity is added.
        Map<String, Class<?>> entityTypes = new TreeMap<>();
        registrations.stream()
                .flatMap(processor -> processor.getEntities().stream())
                .filter(info -> SchemaTableMetadataCustomizer.isLocalEntity(info.getEntityType()))
                .filter(info -> SchemaTableMetadataCustomizer.isLocalEntity(info.getRealType()))
                .map(EntityInfo::getEntityType)
                .forEach(type -> entityTypes.putIfAbsent(type.getName(), type));
        Map<String, RDBTableMetadata> tables = new TreeMap<>();
        for (Class<?> type : entityTypes.values()) {
            RDBTableMetadata table = resolver.resolve(type);
            if (table.getSchema() == null || !schema.equals(table.getSchema().getName()))
                throw new IllegalStateException(
                        "Mapped table is outside the configured comment schema: " + type.getName());
            tables.putIfAbsent(table.getName(), table);
        }
        return List.copyOf(tables.values());
    }

    private Mono<Integer> synchronizeTable(RDBTableMetadata table, RDBTableMetadata current) {
        for (var column : table.getColumns()) {
            if (current.getColumn(column.getName()).isEmpty())
                return Mono.error(
                        new IllegalStateException(
                                "Mapped column is missing; check EasyORM schema synchronization: "
                                        + table.getSchema().getName()
                                        + "."
                                        + table.getName()
                                        + "."
                                        + column.getName()));
        }
        return currentTableComment(table, current)
                .flatMap(
                        comment -> {
                            List<CommentChange> changes = new ArrayList<>();
                            if (hasDescription(table.getComment())
                                    && !Objects.equals(table.getComment(), comment))
                                changes.add(new CommentChange(null, table.getComment()));
                            for (var column : table.getColumns()) {
                                if (hasDescription(column.getComment())
                                        && !Objects.equals(
                                                column.getComment(),
                                                current.getColumn(column.getName())
                                                        .orElseThrow()
                                                        .getComment()))
                                    changes.add(
                                            new CommentChange(
                                                    column.getName(), column.getComment()));
                            }
                            return Flux.fromIterable(changes)
                                    .concatMap(
                                            change ->
                                                    database.sql(
                                                                    DatabaseMetadataDdl.comment(
                                                                            table,
                                                                            change.column(),
                                                                            change.description()))
                                                            .fetch()
                                                            .rowsUpdated()
                                                            .thenReturn(1))
                                    .reduce(0, Integer::sum);
                        });
    }

    private Mono<String> currentTableComment(RDBTableMetadata table, RDBTableMetadata current) {
        var context = DatabaseMetadataDdl.context(table);
        if (context.dialect() != SQLDialect.POSTGRES)
            return Mono.justOrEmpty(current.getComment()).defaultIfEmpty("");
        // EasyORM 4.2.2 的 PG table-comment parser 仅按 relname 连接 pg_class。
        // 此兼容边界使用结构化 DSL 同时限定 namespace，避免其他 schema 的同名表污染描述与幂等计数。
        var namespace = DSL.table(DSL.name("pg_catalog", "pg_namespace")).as("n");
        var relation = DSL.table(DSL.name("pg_catalog", "pg_class")).as("c");
        var query =
                context.select(
                                DSL.function(
                                                DSL.name("pg_catalog", "obj_description"),
                                                String.class,
                                                DSL.field(DSL.name("c", "oid"), Long.class),
                                                DSL.inline("pg_class"))
                                        .as("description"))
                        .from(namespace)
                        .join(relation)
                        .on(
                                DSL.field(DSL.name("c", "relnamespace"), Long.class)
                                        .eq(DSL.field(DSL.name("n", "oid"), Long.class)))
                        .where(
                                DSL.field(DSL.name("n", "nspname"), String.class)
                                        .eq(DSL.param("schemaName", table.getSchema().getName())))
                        .and(
                                DSL.field(DSL.name("c", "relname"), String.class)
                                        .eq(DSL.param("tableName", table.getName())))
                        .and(
                                DSL.field(DSL.name("c", "relkind"), String.class)
                                        .in(DSL.inline("r"), DSL.inline("p")));
        return database.sql(query.getSQL(ParamType.NAMED))
                .bind("schemaName", table.getSchema().getName())
                .bind("tableName", table.getName())
                .map((row, metadata) -> new TableComment(row.get("description", String.class)))
                .one()
                .switchIfEmpty(
                        Mono.error(missingTable(table.getSchema().getName(), table.getName())))
                .map(value -> value.description() == null ? "" : value.description());
    }

    private static IllegalStateException missingTable(String schema, String table) {
        return new IllegalStateException(
                "Mapped table is missing; check EasyORM schema synchronization: "
                        + schema
                        + "."
                        + table);
    }

    private static boolean hasDescription(String description) {
        return description != null && !description.isBlank();
    }

    private record TableComment(String description) {}

    private record CommentChange(String column, String description) {}
}
