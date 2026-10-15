package com.gaiys.proto.system.configuration.metadata;

import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;

/** 扩展 EasyORM 索引元数据，携带由可信实体映射声明的 PostgreSQL 部分索引条件。 */
public class PartialIndexMetadata extends RDBIndexMetadata {
    private final String predicate;

    /** 保留官方索引的名称、列顺序和唯一性，并增加固定的数据库谓词。 */
    public PartialIndexMetadata(RDBIndexMetadata source, String predicate) {
        setName(source.getName());
        setTableName(source.getTableName());
        setAlias(source.getAlias());
        setComment(source.getComment());
        setUnique(source.isUnique());
        setPrimaryKey(source.isPrimaryKey());
        setColumns(source.getColumns().stream().map(IndexColumn::clone).toList());
        this.predicate = predicate;
    }

    public String getPredicate() {
        return predicate;
    }
}
