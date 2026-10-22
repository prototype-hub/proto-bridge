package com.gaiys.proto.system.configuration.builder;

import com.gaiys.proto.system.configuration.metadata.PartialIndexMetadata;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CommonCreateIndexSqlBuilder;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CreateIndexParameter;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

/**
 * 启动同步的索引构建扩展：普通索引委托 EasyORM，部分索引使用结构化条件保持数据库唯一性。
 *
 * <p>仅渲染请求，不执行数据库操作；尚未支持部分索引的方言明确失败，禁止退化为其他唯一性规则。
 */
public class PartialIndexSqlBuilder extends CommonCreateIndexSqlBuilder {
    /** 启动建表或增量补索引时调用；只接受结构化元数据，渲染失败向上终止同步。 */
    @Override
    public SqlRequest build(CreateIndexParameter parameter) {
        if (!(parameter.getIndex() instanceof PartialIndexMetadata partial))
            return super.build(parameter);
        var context = DatabaseMetadataDdl.context(parameter.getTable());
        if (context.dialect() != SQLDialect.POSTGRES)
            throw new UnsupportedOperationException(
                    "Partial unique indexes require PostgreSQL: " + context.dialect());
        String column =
                parameter
                        .getTable()
                        .getColumn(partial.getConditionColumn())
                        .orElseThrow()
                        .getName();
        Field<String> value = DSL.field(DSL.name(column), String.class);
        Condition condition =
                switch (partial.getCondition()) {
                    case NON_EMPTY_STRING -> value.isNotNull().and(value.ne(""));
                };
        var columns =
                partial.getColumns().stream()
                        .sorted()
                        .map(
                                mapping -> {
                                    var field = DSL.field(DSL.name(mapping.getColumn()));
                                    return mapping.getSort() == RDBIndexMetadata.IndexSort.desc
                                            ? field.desc()
                                            : field.asc();
                                })
                        .toArray(OrderField[]::new);
        var create =
                partial.isUnique()
                        ? context.createUniqueIndex(DSL.name(partial.getName()))
                        : context.createIndex(DSL.name(partial.getName()));
        return SqlRequests.of(
                create.on(DSL.table(DatabaseMetadataDdl.tableName(parameter.getTable())), columns)
                        .where(condition)
                        .getSQL(ParamType.INLINED));
    }
}
