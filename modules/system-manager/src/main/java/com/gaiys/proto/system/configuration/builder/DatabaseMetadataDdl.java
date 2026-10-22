package com.gaiys.proto.system.configuration.builder;

import java.util.Objects;
import org.hswebframework.ezorm.rdb.executor.BatchSqlRequest;
import org.hswebframework.ezorm.rdb.executor.DefaultBatchSqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.jooq.DSLContext;
import org.jooq.Name;
import org.jooq.Param;
import org.jooq.SQLDialect;
import org.jooq.VisitContext;
import org.jooq.VisitListener;
import org.jooq.conf.BackslashEscaping;
import org.jooq.conf.ParamType;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

/**
 * 启动元数据同步使用的无状态静态描述渲染边界，结构与类型仍由 EasyORM 官方方言构建器处理。
 *
 * <p>只根据映射生成结构化 DDL，不获取连接、不执行 SQL、不修改连接设置。未适配的方言明确失败， 不通过默认方言或省略描述继续启动；执行与事务生命周期由调用方负责。
 */
public final class DatabaseMetadataDdl {
    private DatabaseMetadataDdl() {}

    /** 根据真实映射方言选择 OSS 渲染器；尚未适配的方言明确失败，禁止降级丢失描述。 */
    public static DSLContext context(RDBTableMetadata table) {
        String dialect = table.getDialect().getId();
        SQLDialect supported =
                switch (dialect) {
                    case "PostgreSQL" -> SQLDialect.POSTGRES;
                    case "h2database" -> SQLDialect.H2;
                    default ->
                            throw new UnsupportedOperationException(
                                    "Safe table/column descriptions are not supported for dialect: "
                                            + dialect);
                };
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(supported);
        if (supported == SQLDialect.POSTGRES) {
            configuration.set(new Settings().withBackslashEscaping(BackslashEscaping.ON));
            configuration.set(new PostgresqlStringLiteralListener());
        }
        return DSL.using(configuration);
    }

    public static Name tableName(RDBTableMetadata table) {
        return DSL.name(table.getSchema().getName(), table.getName());
    }

    /** 标识符和描述只通过结构化 API 传递；column 为 null 表示表描述。 */
    public static String comment(RDBTableMetadata table, String column, String description) {
        DSLContext context = context(table);
        return (column == null
                        ? context.commentOnTable(DSL.table(tableName(table)))
                        : context.commentOnColumn(
                                DSL.name(table.getSchema().getName(), table.getName(), column)))
                .is(description)
                .getSQL(ParamType.INLINED);
    }

    /** 克隆映射后再剥离注释，避免官方构建器对描述文字直接加引号且不转义。 */
    static RDBTableMetadata withoutComments(RDBTableMetadata table) {
        RDBTableMetadata structure = table.clone();
        structure.setComment(null);
        structure.getColumns().forEach(column -> column.setComment(null));
        return structure;
    }

    static SqlRequest withComments(
            SqlRequest structure, RDBTableMetadata table, RDBTableMetadata existing) {
        DefaultBatchSqlRequest statements = new DefaultBatchSqlRequest();
        append(statements, structure);
        if (hasDescription(table.getComment())
                && (existing == null
                        || !Objects.equals(table.getComment(), existing.getComment()))) {
            statements.addBatch(SqlRequests.of(comment(table, null, table.getComment())));
        }
        table.getColumns()
                .forEach(
                        column -> {
                            String current =
                                    existing == null
                                            ? null
                                            : existing.getColumn(column.getName())
                                                    .map(value -> value.getComment())
                                                    .orElse(null);
                            if (hasDescription(column.getComment())
                                    && !Objects.equals(column.getComment(), current))
                                statements.addBatch(
                                        SqlRequests.of(
                                                comment(
                                                        table,
                                                        column.getName(),
                                                        column.getComment())));
                        });
        return statements;
    }

    private static void append(DefaultBatchSqlRequest target, SqlRequest statement) {
        if (statement.getSql() != null && !statement.getSql().isBlank())
            target.addBatch(SqlRequests.of(statement.getSql(), statement.getParameters()));
        if (statement instanceof BatchSqlRequest batch)
            batch.getBatch().forEach(child -> append(target, child));
    }

    private static boolean hasDescription(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * jOOQ 3.19 的 PostgreSQL inline 字符串未输出 E 前缀。仅补固定 token，值的全部转义仍交给官方 renderer。 使用 escape literal
     * 后，注释不再依赖连接的 standard_conforming_strings，也不修改连接池会话设置。
     */
    private static final class PostgresqlStringLiteralListener implements VisitListener {
        @Override
        public void visitStart(VisitContext visit) {
            if (visit.queryPart() instanceof Param<?> parameter
                    && parameter.getValue() instanceof String
                    && (parameter.isInline() || visit.context().paramType() == ParamType.INLINED)) {
                visit.context().sql('E');
            }
        }
    }
}
