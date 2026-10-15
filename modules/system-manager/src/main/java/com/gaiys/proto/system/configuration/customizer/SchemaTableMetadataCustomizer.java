package com.gaiys.proto.system.configuration.customizer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Set;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/** Supplies database descriptions through hsweb's existing persistent-metadata extension. */
@Component
public class SchemaTableMetadataCustomizer implements TableMetadataCustomizer {
    public static boolean isLocalEntity(Class<?> type) {
        return type != null && type.getName().startsWith("com.gaiys.proto.");
    }

    @Override
    public void customTable(Class<?> entityType, RDBTableMetadata table) {
        if (!isLocalEntity(entityType)) return;
        Schema schema = AnnotatedElementUtils.findMergedAnnotation(entityType, Schema.class);
        if (hasDescription(schema)) table.setComment(schema.description());
    }

    @Override
    public void customColumn(
            Class<?> entityType,
            PropertyDescriptor property,
            Field field,
            Set<Annotation> annotations,
            RDBColumnMetadata column) {
        if (!isLocalEntity(entityType)) return;
        // hsweb has already selected the persistent property and its actual column name.
        // Inherited fields are included; JSON visibility does not affect persistence.
        Schema schema = field == null ? null : field.getAnnotation(Schema.class);
        if (!hasDescription(schema) && property.getReadMethod() != null)
            schema = property.getReadMethod().getAnnotation(Schema.class);
        if (!hasDescription(schema))
            schema =
                    annotations.stream()
                            .filter(Schema.class::isInstance)
                            .map(Schema.class::cast)
                            .filter(SchemaTableMetadataCustomizer::hasDescription)
                            .findFirst()
                            .orElse(null);
        if (hasDescription(schema)) column.setComment(schema.description());
    }

    private static boolean hasDescription(Schema schema) {
        return schema != null && !schema.description().isBlank();
    }
}
