package com.gaiys.proto.system.configuration.builder;

import java.util.ArrayList;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterRequest;
import org.hswebframework.ezorm.rdb.supports.postgres.PostgresqlAlterTableSqlBuilder;

/**
 * 通过 EasyORM 官方 DDL 扩展仅添加缺失列和索引，保留已有数据库结构。
 *
 * <p>旧库可能含有比依赖实体更宽的字段和自定义列。同名索引也可能带有部分条件，启动同步不得删除或重建这些对象。
 */
public class AdditivePostgresqlAlterTableSqlBuilder extends PostgresqlAlterTableSqlBuilder {
    @Override
    public SqlRequest build(AlterRequest parameter) {
        RDBTableMetadata additions = parameter.getNewTable().clone();
        RDBTableMetadata existing = parameter.getOldTable();
        // EasyORM 4.2.1 的索引处理不读取 allowIndexAlter，需在委托前排除所有已存在的同名索引。
        additions.setIndexes(
                new ArrayList<>(
                        additions.getIndexes().stream()
                                .filter(index -> existing.getIndex(index.getName()).isEmpty())
                                .toList()));
        AlterRequest additive =
                AlterRequest.builder()
                        .newTable(additions)
                        .oldTable(existing)
                        .allowDrop(false)
                        .allowAlter(false)
                        .allowIndexAlter(false)
                        .build();
        return super.build(additive);
    }
}
