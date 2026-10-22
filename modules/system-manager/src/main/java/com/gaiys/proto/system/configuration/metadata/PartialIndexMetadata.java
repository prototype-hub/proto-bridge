package com.gaiys.proto.system.configuration.metadata;

import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;

/**
 * 启动解析映射时声明的部分索引元数据，仅携带结构化列条件，不接受 SQL 谓词文本。
 *
 * <p>由 schema 注册的构建扩展消费；列和唯一性沿用原映射，不改变运行期数据查询条件。
 */
public class PartialIndexMetadata extends RDBIndexMetadata {
    private final String conditionColumn;
    private final Condition condition;

    /** 仅开放产品当前需要的固定条件，由构建器负责方言渲染。 */
    public enum Condition {
        NON_EMPTY_STRING
    }

    /** 保留官方索引的名称、列顺序和唯一性，并增加固定的数据库谓词。 */
    public PartialIndexMetadata(
            RDBIndexMetadata source, String conditionColumn, Condition condition) {
        setName(source.getName());
        setTableName(source.getTableName());
        setAlias(source.getAlias());
        setComment(source.getComment());
        setUnique(source.isUnique());
        setPrimaryKey(source.isPrimaryKey());
        setColumns(source.getColumns().stream().map(IndexColumn::clone).toList());
        this.conditionColumn = conditionColumn;
        this.condition = condition;
    }

    public String getConditionColumn() {
        return conditionColumn;
    }

    public Condition getCondition() {
        return condition;
    }
}
