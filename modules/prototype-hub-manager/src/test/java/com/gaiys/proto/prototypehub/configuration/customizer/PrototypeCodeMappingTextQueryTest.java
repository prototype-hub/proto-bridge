package com.gaiys.proto.prototypehub.configuration.customizer;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeComponentCodeMappingEntity;
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

class PrototypeCodeMappingTextQueryTest {
    @Test
    void aliasLiteralSearchRegistersAParameterizedPostgresCondition() {
        var database = new RDBDatabaseMetadata(Dialect.POSTGRES);
        var schema = new RDBSchemaMetadata("public");
        database.addSchema(schema);
        var table = new RDBTableMetadata("p_component_code_mapping");
        table.setSchema(schema);
        for (String name : List.of("alias", "snippet")) {
            var column = new RDBColumnMetadata();
            column.setName(name);
            column.setRealName(name);
            table.addColumn(column);
        }
        new PrototypeTextTermCustomizer()
                .customTable(PrototypeComponentCodeMappingEntity.class, table);
        String keyword = "中文 % _ '";
        var query = new QueryOperatorParameter();
        query.setFromAlias("mapping");
        query.setWhere(
                List.of(
                        Term.of(
                                "alias",
                                PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                                keyword)));
        var fragments = QueryTermsFragmentBuilder.of(table).createFragments(query);
        String sql = String.join(" ", fragments.getSql());
        assertTrue(fragments.isNotEmpty());
        assertTrue(sql.contains("alias"));
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).contains("like"));
        assertFalse(sql.contains(keyword));
        assertTrue(fragments.getParameters().contains(keyword));
        query.setWhere(
                List.of(
                        Term.of(
                                "snippet",
                                PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                                keyword)));
        assertTrue(QueryTermsFragmentBuilder.of(table).createFragments(query).isEmpty());
    }
}
