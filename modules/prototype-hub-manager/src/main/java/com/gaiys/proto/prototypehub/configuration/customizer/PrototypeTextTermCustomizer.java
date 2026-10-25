package com.gaiys.proto.prototypehub.configuration.customizer;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Set;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.stereotype.Component;

/**
 * 为原型所有者、名称及分派候选姓名列注册只读查询条件，由启动时的 EasyORM 元数据解析调用。
 *
 * <p>仅挂载列级 feature，不改结构或数据，不向其他列开放原型文本条件。
 *
 * @since 1.0.0
 * @see PrototypeTextTermFragmentBuilder
 */
@Component
public class PrototypeTextTermCustomizer implements TableMetadataCustomizer {
    /** 列结构沿用实体映射；条件在表完成 schema 绑定后统一注册。 */
    @Override
    public void customColumn(
            Class<?> entityType,
            PropertyDescriptor property,
            Field field,
            Set<Annotation> annotations,
            RDBColumnMetadata column) {}

    /** 表完成 schema 绑定后补充列条件，重复注册按 feature 标识替换；缺失映射直接中止启动。 */
    @Override
    public void customTable(Class<?> entityType, RDBTableMetadata table) {
        if (PrototypeProjectEntity.class.isAssignableFrom(entityType)) {
            table.getColumn("ownerId")
                    .orElseThrow()
                    .addFeature(PrototypeTextTermFragmentBuilder.OWNER_PRESENT);
            table.getColumn("name")
                    .orElseThrow()
                    .addFeature(PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS);
        }
        if (PrototypeVersionEntity.class.isAssignableFrom(entityType)) {
            table.getColumn("versionName")
                    .orElseThrow()
                    .addFeature(PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS);
        }
        if (UserEntity.class.isAssignableFrom(entityType)) {
            table.getColumn("name")
                    .orElseThrow()
                    .addFeature(PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS);
        }
    }
}
