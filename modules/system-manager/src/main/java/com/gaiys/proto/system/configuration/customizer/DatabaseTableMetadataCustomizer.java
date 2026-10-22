package com.gaiys.proto.system.configuration.customizer;

import com.gaiys.proto.system.configuration.builder.AdditiveAlterTableSqlBuilder;
import com.gaiys.proto.system.configuration.builder.DescribedCreateTableSqlBuilder;
import com.gaiys.proto.system.configuration.builder.PartialIndexSqlBuilder;
import com.gaiys.proto.system.configuration.metadata.PartialIndexMetadata;
import com.gaiys.proto.system.entity.MenuEntity;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.hswebframework.ezorm.rdb.metadata.NativeSqlDefaultValue;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterTableSqlBuilder;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CreateTableSqlBuilder;
import org.hswebframework.ezorm.rdb.supports.json.UnsupportedJsonTermFragmentBuilder;
import org.hswebframework.ezorm.rdb.supports.postgres.PostgresqlJsonTermFragmentBuilder;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.hswebframework.web.dictionary.entity.DictionaryEntity;
import org.hswebframework.web.dictionary.entity.DictionaryItemEntity;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.PermissionEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.stereotype.Component;

/**
 * 通过官方持久化元数据扩展统一新库结构和旧库的安全增量同步。
 *
 * <p>依赖实体继续复用 hsweb；这里只补充本系统已使用的字段长度、唯一性和数据库默认值，不复制依赖实体。
 */
@Component
public class DatabaseTableMetadataCustomizer implements TableMetadataCustomizer {
    private static final PartialIndexSqlBuilder PARTIAL_INDEX = new PartialIndexSqlBuilder();

    @Override
    public void customTable(Class<?> entityType, RDBTableMetadata table) {
        // AutoDDLProcessor 在 InitializingBean 阶段执行，此时解析器已为表设置真实 schema。
        // 注册在 schema 而非父 database，覆盖 PostgreSQL 方言预置的同类 feature。
        var schema = table.getSchema();
        synchronized (schema) {
            var create = schema.findFeatureNow(CreateTableSqlBuilder.ID);
            if (!(create instanceof DescribedCreateTableSqlBuilder))
                schema.addFeature(new DescribedCreateTableSqlBuilder(create));
            var alter = schema.findFeatureNow(AlterTableSqlBuilder.ID);
            if (!(alter instanceof AdditiveAlterTableSqlBuilder))
                schema.addFeature(new AdditiveAlterTableSqlBuilder(alter));
        }
        table.getSchema().addFeature(PARTIAL_INDEX);
        if (DimensionEntity.class.isAssignableFrom(entityType)) {
            // 依赖将 properties 映射为 LONGVARCHAR + JsonCodec，而非原生 JSON 类型。
            // 表完成 schema 绑定后显式挂载官方条件，保留旧列与 codec；未知方言不得忽略过滤。
            table.getColumn("properties")
                    .orElseThrow()
                    .addFeature(
                            "PostgreSQL".equals(table.getDialect().getId())
                                    ? PostgresqlJsonTermFragmentBuilder.value
                                    : UnsupportedJsonTermFragmentBuilder.value);
        }
        if (MenuEntity.class.isAssignableFrom(entityType)) {
            table.getIndex("proto_menu_code_idx")
                    .ifPresent(
                            index -> {
                                String code = table.getColumn("code").orElseThrow().getName();
                                // 空串与 NULL 可重复，非空菜单编码仍由数据库保证并发写入唯一性。
                                table.getIndexes().remove(index);
                                table.addIndex(
                                        new PartialIndexMetadata(
                                                index,
                                                code,
                                                PartialIndexMetadata.Condition.NON_EMPTY_STRING));
                            });
        }
        if (DictionaryItemEntity.class.isAssignableFrom(entityType)) {
            addUniqueIndex(table, "s_dictionary_item_dict_id_value_key", "dict_id", "value");
            addUniqueIndex(table, "s_dictionary_item_dict_id_ordinal_key", "dict_id", "ordinal");
        }
    }

    @Override
    public void customColumn(
            Class<?> entityType,
            PropertyDescriptor property,
            Field field,
            Set<Annotation> annotations,
            RDBColumnMetadata column) {
        String name = property.getName();
        if (DimensionEntity.class.isAssignableFrom(entityType)) {
            if ("name".equals(name)) column.setLength(128);
            if ("describe".equals(name)) column.setLength(1024);
        }
        if (AuthorizationSettingEntity.class.isAssignableFrom(entityType)
                && ("permission".equals(name) || "dimensionTarget".equals(name))) {
            column.setLength(64);
        }
        if (("status".equals(name)
                        && (UserEntity.class.isAssignableFrom(entityType)
                                || PermissionEntity.class.isAssignableFrom(entityType)
                                || DictionaryEntity.class.isAssignableFrom(entityType)
                                || DictionaryItemEntity.class.isAssignableFrom(entityType)))
                || (AuthorizationSettingEntity.class.isAssignableFrom(entityType)
                        && "state".equals(name))) {
            column.setDefaultValue(NativeSqlDefaultValue.of("1"));
        }
        if (MenuEntity.class.isAssignableFrom(entityType)) {
            // @DefaultValue 仅生成运行期默认值；NativeSqlDefaultValue 才会写入物理 DDL。
            if ("owner".equals(name)) column.setDefaultValue(NativeSqlDefaultValue.of("'iot'"));
            if ("accessSupport".equals(name))
                column.setDefaultValue(NativeSqlDefaultValue.of("'unsupported'"));
        }
    }

    private static void addUniqueIndex(RDBTableMetadata table, String name, String... columns) {
        RDBIndexMetadata index = new RDBIndexMetadata(name);
        index.setUnique(true);
        index.setColumns(
                List.of(columns).stream()
                        .map(
                                column -> {
                                    RDBIndexMetadata.IndexColumn mapping =
                                            new RDBIndexMetadata.IndexColumn();
                                    mapping.setColumn(column);
                                    mapping.setSort(RDBIndexMetadata.IndexSort.asc);
                                    return mapping;
                                })
                        .toList());
        table.addIndex(index);
    }
}
