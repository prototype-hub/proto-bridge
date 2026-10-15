package com.gaiys.proto.analyzer;

import io.r2dbc.spi.R2dbcException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Explains missing database tables during Spring Boot startup failure analysis.
 *
 * <p>The framework creates this diagnostic adapter; only SQLSTATE 42P01 is handled here. It points
 * to EasyORM metadata synchronization without exposing SQL/credentials or executing schema changes
 * itself.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseStartupFailureAnalyzer extends AbstractFailureAnalyzer<R2dbcException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, R2dbcException cause) {
        if (!"42P01".equals(cause.getSqlState())) return null;
        return new FailureAnalysis(
                "A required database table is missing (SQLSTATE 42P01). "
                        + "The configured schema must contain both the system and PrototypeHub tables.",
                "Check easyorm.auto-ddl=true, DB_SCHEMA and DATABASE_R2DBC_URL. "
                        + "Ensure the database user can create tables and add columns/indexes, "
                        + "and mapped entities are registered by EnableEasyormRepository. "
                        + "For legacy table names or incompatible column types, follow "
                        + "doc/DATABASE_MIGRATION.md; automatic DDL does not transform historical data.",
                cause);
    }
}
