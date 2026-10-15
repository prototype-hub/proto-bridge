package com.gaiys.proto.system.service;

import com.gaiys.proto.system.configuration.customizer.SchemaTableMetadataCustomizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.hswebframework.web.crud.configuration.AutoDDLProcessor;
import org.hswebframework.web.crud.configuration.EasyormProperties;
import org.hswebframework.web.crud.configuration.EntityInfo;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
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
                            return Flux.fromIterable(localTables(schema))
                                    .concatMap(table -> synchronizeTable(schema, table))
                                    .reduce(0, Integer::sum);
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

    private Mono<Integer> synchronizeTable(String schema, RDBTableMetadata table) {
        return database.sql(
                        """
                        SELECT a.attname AS column_name,
                               pg_catalog.obj_description(c.oid, 'pg_class') AS table_comment,
                               pg_catalog.col_description(c.oid, a.attnum) AS column_comment
                        FROM pg_catalog.pg_namespace n
                        JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid
                        LEFT JOIN pg_catalog.pg_attribute a
                          ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                        WHERE n.nspname = :schemaName AND c.relname = :tableName
                          AND c.relkind IN ('r', 'p')
                        """)
                .bind("schemaName", schema)
                .bind("tableName", table.getName())
                .map(
                        (row, metadata) ->
                                new CatalogColumn(
                                        row.get("column_name", String.class),
                                        row.get("table_comment", String.class),
                                        row.get("column_comment", String.class)))
                .all()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.isEmpty())
                                return Mono.error(
                                        new IllegalStateException(
                                                "Mapped table is missing; check EasyORM schema synchronization: "
                                                        + schema
                                                        + "."
                                                        + table.getName()));
                            Map<String, String> columns = new LinkedHashMap<>();
                            rows.forEach(
                                    row -> {
                                        if (row.name() != null)
                                            columns.put(row.name(), row.comment());
                                    });
                            for (var column : table.getColumns()) {
                                if (!columns.containsKey(column.getName()))
                                    return Mono.error(
                                            new IllegalStateException(
                                                    "Mapped column is missing; check EasyORM schema synchronization: "
                                                            + schema
                                                            + "."
                                                            + table.getName()
                                                            + "."
                                                            + column.getName()));
                            }
                            List<CommentChange> changes = new ArrayList<>();
                            if (hasDescription(table.getComment())
                                    && !Objects.equals(
                                            table.getComment(), rows.getFirst().tableComment()))
                                changes.add(new CommentChange(null, table.getComment()));
                            for (var column : table.getColumns()) {
                                if (hasDescription(column.getComment())
                                        && !Objects.equals(
                                                column.getComment(), columns.get(column.getName())))
                                    changes.add(
                                            new CommentChange(
                                                    column.getName(), column.getComment()));
                            }
                            return Flux.fromIterable(changes)
                                    .concatMap(
                                            change -> writeComment(schema, table.getName(), change))
                                    .reduce(0, Integer::sum);
                        });
    }

    private Mono<Integer> writeComment(String schema, String table, CommentChange change) {
        // PostgreSQL formats identifiers and literal text itself. This handles quotes, backslashes
        // and non-ASCII descriptions without depending on standard_conforming_strings/search_path.
        String format =
                change.column() == null
                        ? "SELECT pg_catalog.format('COMMENT ON TABLE %I.%I IS %L', CAST(:schemaName AS text), CAST(:tableName AS text), CAST(:description AS text)) AS statement"
                        : "SELECT pg_catalog.format('COMMENT ON COLUMN %I.%I.%I IS %L', CAST(:schemaName AS text), CAST(:tableName AS text), CAST(:columnName AS text), CAST(:description AS text)) AS statement";
        var query =
                database.sql(format)
                        .bind("schemaName", schema)
                        .bind("tableName", table)
                        .bind("description", change.description());
        if (change.column() != null) query = query.bind("columnName", change.column());
        return query.map((row, metadata) -> row.get("statement", String.class))
                .one()
                .flatMap(statement -> database.sql(statement).fetch().rowsUpdated())
                .thenReturn(1);
    }

    private static boolean hasDescription(String description) {
        return description != null && !description.isBlank();
    }

    private record CatalogColumn(String name, String tableComment, String comment) {}

    private record CommentChange(String column, String description) {}
}
