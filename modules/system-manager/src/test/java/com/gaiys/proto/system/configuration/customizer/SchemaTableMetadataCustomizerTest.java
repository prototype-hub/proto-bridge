package com.gaiys.proto.system.configuration.customizer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Set;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.jpa.JpaEntityTableMetadataParser;
import org.hswebframework.ezorm.rdb.mapping.jpa.JpaEntityTableMetadataParserProcessor;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.crud.configuration.EasyormProperties;
import org.junit.jupiter.api.Test;

class SchemaTableMetadataCustomizerTest {
    @Test
    void
            officialParserControlsColumnsWhileSchemaAddsDescriptionsIncludingInheritedAndHiddenFields() {
        var customizer = new SchemaTableMetadataCustomizer();
        var properties = new EasyormProperties();
        properties.setDialect("postgres");
        properties.setDefaultSchema("public");
        var parser =
                new JpaEntityTableMetadataParser() {
                    @Override
                    protected JpaEntityTableMetadataParserProcessor createProcessor(
                            RDBTableMetadata table, Class<?> type) {
                        return new JpaEntityTableMetadataParserProcessor(table, type) {
                            @Override
                            protected void customColumn(
                                    PropertyDescriptor property,
                                    Field field,
                                    RDBColumnMetadata column,
                                    Set<Annotation> annotations) {
                                customizer.customColumn(type, property, field, annotations, column);
                            }
                        };
                    }
                };
        parser.setDatabaseMetadata(properties.createDatabaseMetadata());
        var table = parser.parseTableMetadata(TreeFixture.class).orElseThrow();
        customizer.customTable(TreeFixture.class, table);
        assertEquals("树结构描述", table.getComment());
        assertEquals("隐藏的持久字段描述", table.getColumn("hidden_value").orElseThrow().getComment());
        assertEquals("id", table.getColumn("id").orElseThrow().getComment());
        for (String name : Set.of("parent_id", "path", "_level", "sort_index"))
            assertFalse(table.getColumn(name).orElseThrow().getComment().isBlank(), name);
        assertTrue(table.getColumn("temporary").isEmpty());
        assertTrue(table.getColumn("children").isEmpty());
    }

    @Test
    void blankDescriptionsAndForeignMappingsDoNotEraseExistingMetadata() throws Exception {
        var customizer = new SchemaTableMetadataCustomizer();
        var table = new RDBTableMetadata("existing");
        table.setComment("existing table description");
        customizer.customTable(BlankFixture.class, table);
        assertEquals("existing table description", table.getComment());
        var column = new RDBColumnMetadata();
        column.setComment("existing column description");
        var property = new PropertyDescriptor("blank", BlankFixture.class);
        var field = BlankFixture.class.getDeclaredField("blank");
        customizer.customColumn(BlankFixture.class, property, field, Set.of(), column);
        assertEquals("existing column description", column.getComment());
        customizer.customColumn(
                String.class,
                property,
                TreeFixture.class.getDeclaredField("hidden"),
                Set.of(),
                column);
        assertEquals("existing column description", column.getComment());
    }

    @Getter
    @Setter
    @Table(name = "tree_fixture")
    @Schema(description = "树结构描述")
    public static class TreeFixture extends GenericTreeSortSupportEntity<String> {
        @Column(name = "hidden_value")
        @JsonIgnore
        @Schema(description = "隐藏的持久字段描述")
        private String hidden;

        @Transient
        @Schema(description = "仅用于显示")
        private String temporary;

        @Transient private java.util.List<TreeFixture> children;
    }

    @Getter
    @Setter
    @Schema(description = " ")
    public static class BlankFixture {
        @Schema(description = "")
        private String blank;
    }
}
