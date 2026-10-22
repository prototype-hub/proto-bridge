package com.gaiys.proto.system.configuration.builder;

import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterRequest;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterTableSqlBuilder;

/**
 * 启动元数据同步时委托官方方言，仅添加缺失列和索引，保留旧库更宽的字段、自定义列与同名索引。
 *
 * <p>每个 schema 包装其已有构建器；仅生成 DDL 请求，不持有连接或自行执行数据库操作。
 */
@RequiredArgsConstructor
public class AdditiveAlterTableSqlBuilder implements AlterTableSqlBuilder {
    private final AlterTableSqlBuilder delegate;

    /** 启动同步调用：增量结构委托官方构建器，再补安全描述；构建或执行失败均向上终止同步。 */
    @Override
    public SqlRequest build(AlterRequest parameter) {
        RDBTableMetadata additions = DatabaseMetadataDdl.withoutComments(parameter.getNewTable());
        RDBTableMetadata existing = parameter.getOldTable();
        // EasyORM 4.2.2 不读取 allowIndexAlter；委托前排除同名索引，避免重建旧库的部分索引。
        additions.setIndexes(
                new ArrayList<>(
                        additions.getIndexes().stream()
                                .filter(index -> existing.getIndex(index.getName()).isEmpty())
                                .toList()));
        AlterRequest additive =
                AlterRequest.builder()
                        .newTable(additions)
                        .oldTable(DatabaseMetadataDdl.withoutComments(existing))
                        .allowDrop(false)
                        .allowAlter(false)
                        .allowIndexAlter(false)
                        .build();
        return DatabaseMetadataDdl.withComments(
                delegate.build(additive), parameter.getNewTable(), existing);
    }
}
