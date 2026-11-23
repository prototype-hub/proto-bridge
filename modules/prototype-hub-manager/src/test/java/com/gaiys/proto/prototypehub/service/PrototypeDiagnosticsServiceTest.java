package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.notification.NotificationMailTransport;
import com.gaiys.proto.notification.SmtpWork;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.CapacityUsage;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.Check;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeDiagnosticsServiceTest {
    @TempDir Path root;

    @Test
    void timeoutAndFailuresHaveFixedCodesAndNeverReturnExceptionText() {
        StepVerifier.withVirtualTime(() -> PrototypeDiagnosticsService.check(Mono::never))
                .thenAwait(PrototypeDiagnosticsService.CHECK_TIMEOUT)
                .assertNext(
                        value -> {
                            assertEquals("timeout", value.status());
                            assertEquals("TIMEOUT", value.code());
                            assertNull(value.data());
                        })
                .verifyComplete();
        for (var operation :
                List.<java.util.function.Supplier<Mono<Check<Object>>>>of(
                        () -> Mono.error(new IllegalArgumentException("secret-path-token")),
                        () -> {
                            throw new IllegalStateException("secret-path-token");
                        },
                        Mono::empty)) {
            var value = PrototypeDiagnosticsService.check(operation).block();
            assertEquals(new Check<>("error", "UNAVAILABLE", null), value);
        }
    }

    @Test
    void failedDatabaseSectionsKeepSafeMailAndStorageWithoutCreatingFilesOrSending()
            throws Exception {
        var properties = new PrototypeHubStorageProperties();
        Path absent = root.resolve("not-created/diagnostic");
        properties.setRoot(absent);
        var projects = mock(PrototypeProjectService.class);
        when(projects.usage()).thenReturn(Mono.error(new IllegalStateException("secret-db-host")));
        var licenses = mock(PrototypeLicenseService.class);
        when(licenses.current())
                .thenReturn(Mono.error(new IllegalStateException("secret-license")));
        var mail = mock(NotificationMailTransport.class);
        when(mail.available()).thenReturn(false);
        try (var files = new FileWork();
                var smtp = new SmtpWork()) {
            var service =
                    new PrototypeDiagnosticsService(
                            projects,
                            licenses,
                            properties,
                            files,
                            mail,
                            smtp,
                            mock(DatabaseOperator.class),
                            mock(EntityTableMetadataResolver.class),
                            ObservationRegistry.NOOP);
            var result = service.snapshot().block(Duration.ofSeconds(5));
            assertFalse(result.complete());
            assertEquals("UNAVAILABLE", result.checks().database().code());
            assertEquals("UNAVAILABLE", result.checks().cleanup().code());
            assertEquals("UNAVAILABLE", result.checks().license().code());
            assertEquals("MAIL_NOT_CONFIGURED", result.checks().mail().code());
            assertTrue(result.checks().storage().data().writable());
            assertEquals("nearest_existing_parent", result.checks().storage().data().scope());
            assertFalse(Files.exists(absent.getParent()));
            assertEquals(0, smtp.snapshot().completed());
            String json = new ObjectMapper().writeValueAsString(result);
            assertFalse(json.contains(root.toString()));
            assertFalse(json.contains("secret-"));
        }
        verify(mail).available();
        verifyNoMoreInteractions(mail);
    }

    @Test
    void writableRegularFileIsNeverReportedAsAUsableStorageDirectory() throws Exception {
        Path regular = Files.writeString(root.resolve("regular-file"), "test");
        var projects = mock(PrototypeProjectService.class);
        when(projects.usage()).thenReturn(Mono.empty());
        var licenses = mock(PrototypeLicenseService.class);
        when(licenses.current()).thenReturn(Mono.empty());
        try (var files = new FileWork();
                var smtp = new SmtpWork()) {
            for (Path configured : List.of(regular, regular.resolve("absent-child"))) {
                var properties = new PrototypeHubStorageProperties();
                properties.setRoot(configured);
                var service =
                        new PrototypeDiagnosticsService(
                                projects,
                                licenses,
                                properties,
                                files,
                                mock(NotificationMailTransport.class),
                                smtp,
                                mock(DatabaseOperator.class),
                                mock(EntityTableMetadataResolver.class),
                                ObservationRegistry.NOOP);
                var result = service.snapshot().block(Duration.ofSeconds(5));
                assertEquals("warning", result.checks().storage().status());
                assertEquals("STORAGE_UNAVAILABLE", result.checks().storage().code());
                assertFalse(result.checks().storage().data().writable());
            }
        }
        assertEquals("test", Files.readString(regular));
    }

    @Test
    void capacityRetainsUnlimitedSemanticsAndSafeLicenseProjectionCannotIncludeClaims()
            throws Exception {
        var data =
                PrototypeDiagnosticsService.licenseData(
                        new PrototypeLicenseStatus(
                                1,
                                "private-installation",
                                "valid",
                                true,
                                new PrototypeLicenseStatus.AcceptedCredential(
                                        "private-license",
                                        1,
                                        "private-fingerprint",
                                        1,
                                        "private-key"),
                                new PrototypeLicenseStatus.VerifiedEntitlements(
                                        "private-customer",
                                        "permanent",
                                        List.of("private-feature"),
                                        12,
                                        50,
                                        1,
                                        1,
                                        null,
                                        null,
                                        3,
                                        null),
                                new PrototypeLicenseStatus.FreeEntitlements(5, 5, List.of()),
                                new PrototypeLicenseStatus.EnforcedCapacity(12, 50, "license"),
                                new PrototypeLicenseStatus.TrialActivation(1L, 2L),
                                new PrototypeLicenseStatus.AdvancedEntitlements(
                                        true,
                                        "untrusted-private-state",
                                        List.of("private-feature"))));
        var json = new ObjectMapper().valueToTree(data);
        Set<String> fields =
                StreamSupport.stream(
                                ((Iterable<String>) () -> json.fieldNames()).spliterator(), false)
                        .collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        "status",
                        "trustConfigured",
                        "capacitySource",
                        "projectLimit",
                        "versionLimit",
                        "advancedModuleAvailable",
                        "advancedStatus",
                        "enabledFeatureCount"),
                fields);
        assertEquals("unknown", data.advancedStatus());
        assertFalse(json.toString().contains("private-"));
        var unlimited = CapacityUsage.from(99, -1);
        assertEquals(-1, unlimited.remaining());
        assertTrue(unlimited.canCreate());
        assertFalse(unlimited.overLimit());
    }
}
