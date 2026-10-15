package com.gaiys.proto.system.configuration.builder;

import com.gaiys.proto.system.configuration.metadata.PartialIndexMetadata;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CommonCreateIndexSqlBuilder;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CreateIndexParameter;

/** 保留官方建索引逻辑，仅为明确声明了固定条件的元数据补充部分索引谓词。 */
public class PartialIndexSqlBuilder extends CommonCreateIndexSqlBuilder {
    @Override
    public SqlRequest build(CreateIndexParameter parameter) {
        SqlRequest statement = super.build(parameter);
        if (!(parameter.getIndex() instanceof PartialIndexMetadata partial)) return statement;
        // 谓词只来自启动时的可信映射，禁止以请求参数或配置文本作为 SQL 条件。
        return SqlRequests.of(
                statement.getSql() + " where " + partial.getPredicate(), statement.getParameters());
    }
}
