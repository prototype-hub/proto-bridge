package com.gaiys.proto.prototypehub.configuration.customizer;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import java.util.List;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.query.QueryTermsFragmentBuilder;
import org.hswebframework.ezorm.rdb.operator.dml.query.QueryOperatorParameter;
import org.junit.jupiter.api.Test;

class PrototypeMaterialTextQueryTest {
    @Test
    void bodyAndMetadataLiteralConditionsReachThePostgresSqlBuilder() {
        var database = new RDBDatabaseMetadata(Dialect.POSTGRES);
        var schema = new RDBSchemaMetadata("public");
        database.addSchema(schema);
        var table = new RDBTableMetadata("p_revision_material");
        table.setSchema(schema);
        for (String name :
                List.of(
                        "fileName",
                        "sourceTool",
                        "sourceName",
                        "sourceNotes",
                        "textContent",
                        "sourceUrl")) {
            var column = new RDBColumnMetadata();
            column.setName(name);
            column.setRealName(
                    name.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT));
            table.addColumn(column);
        }
        new PrototypeTextTermCustomizer().customTable(PrototypeRevisionMaterialEntity.class, table);
        String keyword = "BodyOnlyNeedle 中文 % _ '";
        for (String field : List.of("textContent", "fileName")) {
            var query = new QueryOperatorParameter();
            query.setFromAlias("material");
            query.setWhere(
                    List.of(
                            Term.of(
                                    field,
                                    PrototypeTextTermFragmentBuilder.COPY_LITERAL_CONTAINS
                                            .getTermType(),
                                    keyword)));
            var fragments = QueryTermsFragmentBuilder.of(table).createFragments(query);
            assertTrue(fragments.isNotEmpty(), "Missing actual SQL condition for " + field);
            String sql = String.join(" ", fragments.getSql());
            assertTrue(sql.contains(field.equals("textContent") ? "text_content" : "file_name"));
            assertTrue(sql.contains("lower("));
            assertFalse(sql.contains(keyword));
            assertTrue(fragments.getParameters().contains(keyword));
        }
        var excluded = new QueryOperatorParameter();
        excluded.setWhere(
                List.of(
                        Term.of(
                                "sourceUrl",
                                PrototypeTextTermFragmentBuilder.COPY_LITERAL_CONTAINS
                                        .getTermType(),
                                keyword)));
        assertTrue(QueryTermsFragmentBuilder.of(table).createFragments(excluded).isEmpty());
    }
}
