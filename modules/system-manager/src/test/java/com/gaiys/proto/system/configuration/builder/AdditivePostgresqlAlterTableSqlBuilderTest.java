package com.gaiys.proto.system.configuration.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.system.configuration.metadata.PartialIndexMetadata;
import java.sql.JDBCType;
import java.util.List;
import org.hswebframework.ezorm.rdb.executor.BatchSqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.AlterRequest;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CommonCreateTableSqlBuilder;
import org.hswebframework.ezorm.rdb.supports.postgres.PostgresqlSchemaMetadata;
import org.junit.jupiter.api.Test;

class AdditivePostgresqlAlterTableSqlBuilderTest {
    @Test
    void officialCreateAndAddPathsBothRetainThePartialUniquePredicate() {
        var schema = schema();
        var wanted = wanted(schema);
        assertInstanceOf(PartialIndexMetadata.class, wanted.clone().getIndexes().getFirst());
        String created = statements(CommonCreateTableSqlBuilder.INSTANCE.build(wanted));
        assertTrue(created.contains("IS NOT NULL AND"));
        assertTrue(created.contains("<>''"));
        var existing = schema.newTable("fixture");
        column(existing, "code", 512);
        column(existing, "custom_data", 4000);
        String added =
                statements(
                        new AdditivePostgresqlAlterTableSqlBuilder()
                                .build(request(wanted, existing)));
        assertTrue(added.contains(" add ") && added.contains("new_text"), added);
        assertTrue(added.contains("IS NOT NULL AND") && added.contains("<>''"), added);
        assertFalse(added.contains("drop column"), added);
        assertFalse(added.contains("alter column"), added);
        assertFalse(added.contains("drop index"), added);
        assertEquals(512, existing.getColumn("code").orElseThrow().getLength());
        assertTrue(existing.getColumn("custom_data").isPresent());
        assertEquals(128, wanted.getColumn("code").orElseThrow().getLength());
    }

    @Test
    void anExistingSameNamedIndexIsNeverRebuiltAndDesiredMetadataIsNotMutated() {
        var schema = schema();
        var wanted = wanted(schema);
        var existing = schema.newTable("fixture");
        column(existing, "code", 512);
        column(existing, "custom_data", 4000);
        existing.addIndex(wanted.getIndexes().getFirst().clone());
        String added =
                statements(
                        new AdditivePostgresqlAlterTableSqlBuilder()
                                .build(request(wanted, existing)));
        assertFalse(added.contains("index"), added);
        assertTrue(added.contains("new_text"), added);
        assertEquals(1, wanted.getIndexes().size());
        assertInstanceOf(PartialIndexMetadata.class, wanted.getIndexes().getFirst());
        assertEquals(1, existing.getIndexes().size());
    }

    private static PostgresqlSchemaMetadata schema() {
        var database = new RDBDatabaseMetadata(Dialect.POSTGRES);
        var schema = new PostgresqlSchemaMetadata("test");
        database.addSchema(schema);
        schema.addFeature(CommonCreateTableSqlBuilder.INSTANCE);
        schema.addFeature(new PartialIndexSqlBuilder());
        return schema;
    }

    private static RDBTableMetadata wanted(PostgresqlSchemaMetadata schema) {
        var table = schema.newTable("fixture");
        column(table, "code", 128);
        column(table, "new_text", 255);
        var index = new RDBIndexMetadata("fixture_code_idx");
        index.setUnique(true);
        var code = new RDBIndexMetadata.IndexColumn();
        code.setColumn("code");
        code.setSort(RDBIndexMetadata.IndexSort.asc);
        index.setColumns(List.of(code));
        table.addIndex(new PartialIndexMetadata(index, "\"code\" IS NOT NULL AND \"code\"<>''"));
        return table;
    }

    private static AlterRequest request(RDBTableMetadata wanted, RDBTableMetadata existing) {
        // Even a caller requesting destructive alteration must get the same additive policy.
        return AlterRequest.builder()
                .newTable(wanted)
                .oldTable(existing)
                .allowDrop(true)
                .allowAlter(true)
                .allowIndexAlter(true)
                .build();
    }

    private static void column(RDBTableMetadata table, String name, int length) {
        var column = new RDBColumnMetadata();
        column.setName(name);
        column.setJdbcType(JDBCType.VARCHAR, String.class);
        column.setLength(length);
        table.addColumn(column);
    }

    private static String statements(SqlRequest request) {
        var text = new StringBuilder(request.getSql());
        if (request instanceof BatchSqlRequest batch)
            for (SqlRequest child : batch.getBatch()) text.append('\n').append(statements(child));
        return text.toString();
    }
}
