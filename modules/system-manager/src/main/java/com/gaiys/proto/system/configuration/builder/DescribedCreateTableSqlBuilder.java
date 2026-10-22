package com.gaiys.proto.system.configuration.builder;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CreateTableSqlBuilder;

/**
 * 启动建表时为官方结构 DDL 追加安全描述，冷启动同样处理描述中的引号和反斜线。
 *
 * <p>按 schema 包装已有构建器，仅生成请求；数据库执行和失败传播仍由 EasyORM 负责。
 */
@RequiredArgsConstructor
public class DescribedCreateTableSqlBuilder implements CreateTableSqlBuilder {
    private final CreateTableSqlBuilder delegate;

    /** 启动同步调用：克隆映射后委托结构生成并补描述，错误向上传播，不修改注册映射或降级执行。 */
    @Override
    public SqlRequest build(RDBTableMetadata table) {
        return DatabaseMetadataDdl.withComments(
                delegate.build(DatabaseMetadataDdl.withoutComments(table)), table, null);
    }
}
