package com.gaiys.proto.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.spi.R2dbcBadGrammarException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

class DatabaseStartupFailureAnalyzerTest {
    @Test
    void missingTableProducesActionableAnalysisWithoutSqlOrCredentials() {
        String secret = UUID.randomUUID().toString();
        var databaseError =
                new R2dbcBadGrammarException(
                        "relation does not exist; password=" + secret,
                        "42P01",
                        0,
                        "select '" + secret + "' from s_user");
        var failure = new IllegalStateException("Application runner failed", databaseError);
        var analysis = new DatabaseStartupFailureAnalyzer().analyze(failure);
        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("42P01"));
        assertTrue(analysis.getAction().contains("easyorm.auto-ddl=true"));
        assertTrue(analysis.getAction().contains("DB_SCHEMA"));
        assertTrue(analysis.getAction().contains("DATABASE_R2DBC_URL"));
        assertTrue(analysis.getAction().contains("EnableEasyormRepository"));
        assertFalse(analysis.getAction().contains("FLYWAY_ENABLED"));
        assertFalse(analysis.getAction().contains("DATABASE_JDBC_URL"));
        assertFalse((analysis.getDescription() + analysis.getAction()).contains(secret));
        assertFalse(analysis.getDescription().contains(databaseError.getSql()));
        assertSame(databaseError, analysis.getCause());
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(
                                FailureAnalyzer.class, getClass().getClassLoader())
                        .contains(DatabaseStartupFailureAnalyzer.class.getName()));
    }

    @Test
    void invalidInitialPasswordReportsTheVariableAndLengthRequirement() {
        var cause =
                new com.gaiys.proto.system.configuration.initializer.AdministratorInitializer
                        .InitialPasswordException();
        var analysis =
                new AdministratorStartupFailureAnalyzer()
                        .analyze(new IllegalStateException("Application runner failed", cause));
        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("12 to 128"));
        assertTrue(analysis.getAction().contains("ADMIN_USER_PASSWORD"));
        assertTrue(analysis.getAction().contains("ADMIN_PASSWORD"));
        assertTrue(analysis.getAction().contains("existing accounts"));
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(
                                FailureAnalyzer.class, getClass().getClassLoader())
                        .contains(AdministratorStartupFailureAnalyzer.class.getName()));
        assertNull(
                new AdministratorStartupFailureAnalyzer()
                        .analyze(new IllegalStateException("unrelated failure")));
    }

    @Test
    void unrelatedSqlErrorsDoNotSuggestCreatingTables() {
        assertNull(
                new DatabaseStartupFailureAnalyzer()
                        .analyze(new R2dbcBadGrammarException("syntax error", "42601")));
        assertNull(
                new DatabaseStartupFailureAnalyzer()
                        .analyze(new IllegalStateException("unrelated startup failure")));
    }
}
