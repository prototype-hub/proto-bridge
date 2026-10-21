package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.ShareCreateRequest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeHubRequestBoundaryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedDescriptionFailsBeforeAnyUploadOrDatabaseWork(boolean replace) {
        var projects = mock(PrototypeProjectService.class);
        var versions = mock(PrototypeVersionService.class);
        var storage = mock(PrototypeStorageService.class);
        var extractor = mock(SecureZipExtractor.class);
        var work = mock(FileWork.class);
        var transaction = mock(TransactionalOperator.class);
        var cleanup = mock(FileCleanupService.class);
        var database = mock(DatabaseClient.class);
        var receipts = mock(PrototypeCreationReceiptService.class);
        var file = mock(FilePart.class);
        var upload =
                new PrototypeUploadService(
                        projects,
                        versions,
                        storage,
                        extractor,
                        work,
                        transaction,
                        cleanup,
                        database,
                        receipts);
        Mono<PrototypeVersionEntity> result =
                replace
                        ? upload.update("version", "Release", "a".repeat(2001), file)
                        : upload.upload("project", "Release", "a".repeat(2001), file, "actor");
        StepVerifier.create(result)
                .expectErrorSatisfies(
                        failure -> {
                            var error = assertInstanceOf(PrototypeHubException.class, failure);
                            assertEquals(400, error.getStatus());
                            assertEquals(
                                    "PROTOTYPE_HUB_VERSION_DESCRIPTION_INVALID", error.getCode());
                        })
                .verify(Duration.ofSeconds(5));
        verifyNoInteractions(
                projects,
                versions,
                storage,
                extractor,
                work,
                transaction,
                cleanup,
                database,
                receipts,
                file);
    }

    static Stream<String> oversizedPasswords() {
        return Stream.of("a".repeat(73), "密".repeat(25));
    }

    @ParameterizedTest
    @MethodSource("oversizedPasswords")
    void newShareRejectsOversizedUtf8PasswordsBeforePersistence(String password) {
        var projects = mock(PrototypeProjectService.class);
        var versions = mock(PrototypeVersionService.class);
        var database = mock(DatabaseClient.class);
        var transaction = mock(TransactionalOperator.class);
        var version = new PrototypeVersionEntity();
        version.setId("version");
        version.setProjectId("project");
        when(versions.requireById("version")).thenReturn(Mono.just(version));
        try (var work = new FileWork()) {
            var shares = new PrototypeShareService(projects, versions, work, database, transaction);
            StepVerifier.create(
                            shares.createShare(
                                    "version",
                                    new ShareCreateRequest(password, null, null),
                                    "actor"))
                    .expectErrorSatisfies(
                            failure -> {
                                var error = assertInstanceOf(PrototypeHubException.class, failure);
                                assertEquals(400, error.getStatus());
                                assertEquals(
                                        "PROTOTYPE_HUB_SHARE_PASSWORD_TOO_LONG", error.getCode());
                            })
                    .verify(Duration.ofSeconds(5));
        }
        verifyNoInteractions(projects, database, transaction);
    }
}
