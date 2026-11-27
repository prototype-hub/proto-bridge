package com.gaiys.proto.prototypehub.configuration.customizer;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypePageRelationEntity;
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

class PrototypePageRelationTextQueryTest {
    @Test
    void relationKeywordUsesBoundLiteralSearchIncludingPercentUnderscoreAndQuotes() {
        var database = new RDBDatabaseMetadata(Dialect.POSTGRES);
        var schema = new RDBSchemaMetadata("public");
        database.addSchema(schema);
        var table = new RDBTableMetadata("p_page_relation");
        table.setSchema(schema);
        for (String name : List.of("label", "note")) {
            var column = new RDBColumnMetadata();
            column.setName(name);
            column.setRealName(name);
            table.addColumn(column);
        }
        new PrototypeTextTermCustomizer().customTable(PrototypePageRelationEntity.class, table);
        var query = new QueryOperatorParameter();
        query.setFromAlias("relation");
        String literal = "中文 % _ '";
        query.setWhere(
                List.of(
                        Term.of(
                                "label",
                                PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                                literal)));
        var fragment = QueryTermsFragmentBuilder.of(table).createFragments(query);
        String sql = String.join(" ", fragment.getSql());
        assertTrue(fragment.isNotEmpty());
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).contains("like"));
        assertFalse(sql.contains(literal));
        assertTrue(fragment.getParameters().contains(literal));
        query.setWhere(
                List.of(
                        Term.of(
                                "note",
                                PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                                literal)));
        assertTrue(QueryTermsFragmentBuilder.of(table).createFragments(query).isEmpty());
    }
}
