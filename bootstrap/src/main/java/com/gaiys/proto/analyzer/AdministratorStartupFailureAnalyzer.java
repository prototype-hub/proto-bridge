package com.gaiys.proto.analyzer;

import com.gaiys.proto.system.configuration.initializer.AdministratorInitializer.InitialPasswordException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Explains invalid initial administrator credentials during Spring Boot startup failure analysis.
 *
 * <p>The framework instantiates this diagnostic adapter after initialization fails. It reports the
 * configuration requirement without exposing the password or creating/modifying administrator data.
 */
public class AdministratorStartupFailureAnalyzer
        extends AbstractFailureAnalyzer<InitialPasswordException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InitialPasswordException cause) {
        return new FailureAnalysis(
                "The initial administrator password must not be blank and must contain 12 to 128 characters.",
                "Set ADMIN_USER_PASSWORD in config/application-local.yml or the ADMIN_PASSWORD "
                        + "environment variable to a valid password, then restart. "
                        + "This setting only creates the first administrator; existing accounts "
                        + "keep their current password.",
                cause);
    }
}
