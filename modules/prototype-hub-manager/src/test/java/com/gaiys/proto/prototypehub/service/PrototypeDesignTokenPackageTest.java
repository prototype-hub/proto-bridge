package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeDesignTokenPackageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.DesignTokenPackageRequests;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument;
import com.gaiys.proto.prototypehub.web.response.DesignTokenPackageImportResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeDesignTokenPackageTest {
    @TempDir Path directory;

    @Test
    void previewReportsSemanticErrorsWithoutWritingAndFatalJsonCannotBecomeAPackage() {
        var properties = new PrototypeHubStorageProperties();
        properties.setRoot(directory);
        try (var work = new FileWork()) {
            var service = new PrototypeDesignTokenPackageFileService(properties, work, null, null);
            byte[] invalid =
                    "{\"wrong\":{\"$type\":\"number\",\"$value\":\"text\"}}"
                            .getBytes(StandardCharsets.UTF_8);
            StepVerifier.create(service.validate(file(invalid)))
                    .assertNext(document -> assertFalse(document.isValid()))
                    .verifyComplete();
            StepVerifier.create(
                            service.upload(
                                    "p",
                                    "v",
                                    file(invalid),
                                    false,
                                    candidate -> {
                                        fail(
                                                "Semantic invalidity without acknowledgement must not reach persistence");
                                        return Mono.empty();
                                    }))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException p
                                            && p.getCode()
                                                    .equals(
                                                            "PROTOTYPE_HUB_TOKEN_PACKAGE_SEMANTIC_INVALID"))
                    .verify();
            StepVerifier.create(service.validate(file("{broken".getBytes(StandardCharsets.UTF_8))))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException p
                                            && p.getStatus() == 400)
                    .verify();
            assertFalse(Files.exists(directory.resolve("projects")));
        }
    }

    @Test
    void wireDistinguishesInitialInputsAndStrictEmptySelectionAndKeepsPackageEnvelopeName()
            throws Exception {
        var json = new ObjectMapper();
        assertNull(json.readValue("{}", DesignTokenPackageRequests.Document.class).getInputs());
        assertNull(
                json.readValue("{\"inputs\":null}", DesignTokenPackageRequests.Document.class)
                        .getInputs());
        assertEquals(
                java.util.Map.of(),
                json.readValue("{\"inputs\":{}}", DesignTokenPackageRequests.Document.class)
                        .getInputs());
        var result =
                json.readTree(
                        json.writeValueAsBytes(
                                new DesignTokenPackageImportResponse("original", "missing", null)));
        assertTrue(result.has("package"));
        assertTrue(result.get("package").isNull());
        assertFalse(result.has("value"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"inputs\":{\"scheme\":1}}",
                "{\"inputs\":{\"scheme\":true}}",
                "{\"inputs\":{\"scheme\":null}}",
                "{\"inputs\":{\"scheme\":[]}}",
                "{\"inputs\":{\"scheme\":{}}}",
                "{\"inputs\":1}",
                "{\"inputs\":false}",
                "{\"inputs\":[]}"
            })
    void contextInputsNeverCoerceNonStringValues(String input) {
        assertThrows(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                () ->
                        new ObjectMapper()
                                .readValue(input, DesignTokenPackageRequests.Document.class));
    }

    private static FilePart file(byte[] bytes) {
        var file = mock(FilePart.class);
        when(file.filename()).thenReturn("tokens.json");
        when(file.content())
                .thenAnswer(
                        ignored -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)));
        return file;
    }

    @Test
    void rawDownloadPreservesBomWhitespaceAndExtensionsAndRejectsChangedBytes() throws Exception {
        byte[] raw =
                ("\ufeff{\r\n  \"$extensions\": {\"example.test\": {\"text\": \"<script>x</script>\"}},\r\n"
                                + "  \"size\": {\"$type\": \"number\", \"$value\": 2}\r\n}\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        var row = row();
        row.setStoragePath("projects/p/design-token-packages/store/content");
        row.setFileName("设计.tokens.json");
        row.setFileSize((long) raw.length);
        row.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        Path target = directory.resolve(row.getStoragePath());
        Files.createDirectories(target.getParent());
        Files.write(target, raw);
        var properties = new PrototypeHubStorageProperties();
        properties.setRoot(directory);
        try (var work = new FileWork()) {
            var service = new PrototypeDesignTokenPackageFileService(properties, work, null, null);
            var response = service.read(row).block(Duration.ofSeconds(3));
            assertNotNull(response);
            assertArrayEquals(raw, response.getBody().getContentAsByteArray());
            assertEquals(raw.length, response.getHeaders().getContentLength());
            assertEquals("private, no-store", response.getHeaders().getCacheControl());
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
            assertEquals(
                    org.springframework.http.MediaType.APPLICATION_OCTET_STREAM,
                    response.getHeaders().getContentType());
            assertTrue(
                    response.getHeaders()
                            .getFirst("Content-Disposition")
                            .startsWith("attachment;"));
            assertTrue(
                    response.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));
            raw[raw.length - 1] = ' ';
            Files.write(target, raw);
            StepVerifier.create(service.read(row))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException p
                                            && p.getCode()
                                                    .equals(
                                                            "PROTOTYPE_HUB_TOKEN_PACKAGE_FILE_MISSING"))
                    .verify();
        }
    }

    @Test
    void cancellationClosesLateWritersAndWaitsPhysicalFinally() {
        var attempt = new PrototypeDesignTokenPackageFileService.Attempt();
        assertTrue(attempt.beginWriting());
        var finished = new AtomicBoolean();
        StepVerifier.create(attempt.closeWriting().doOnSuccess(ignored -> finished.set(true)))
                .then(
                        () -> {
                            assertFalse(finished.get());
                            assertFalse(attempt.beginWriting());
                            attempt.completeWriting();
                        })
                .verifyComplete();
        assertTrue(finished.get());
    }

    @ParameterizedTest
    @CsvSource({"0,true,false", "0,false,true", "1,true,true", "2,true,false", "2,false,false"})
    void candidateCleanupWaitsOutcomeAndKeepsUnknown(int status, boolean owned, boolean deleted) {
        var service = spy(new PrototypeDesignTokenPackageFileService(null, null, null, null));
        doReturn(Mono.empty()).when(service).delete("p", "path");
        var attempt = new PrototypeDesignTokenPackageFileService.Attempt();
        attempt.ownDirectory();
        var manager = mock(TransactionSynchronizationManager.class);
        var synchronization = ArgumentCaptor.forClass(TransactionSynchronization.class);
        assertTrue(attempt.enlist(manager));
        verify(manager).registerSynchronization(synchronization.capture());
        attempt.candidateOwned(owned);
        StepVerifier.create(service.release("p", "v", "path", attempt))
                .then(
                        () -> {
                            verify(service, never()).delete("p", "path");
                            synchronization
                                    .getValue()
                                    .afterCompletion(status)
                                    .block(Duration.ofSeconds(2));
                        })
                .verifyComplete();
        verify(service, times(deleted ? 1 : 0)).delete("p", "path");
    }

    @Test
    void revokedAtLockCannotReplayOrWriteSourcesAndDelete() {
        var f = new Fixture();
        when(f.access.actor("u")).thenReturn(Mono.just(f.actor), Mono.just(f.revoked));
        var update = new RevisionMaterialRequests.Update();
        update.setExpectedVersion(0L);
        update.setClientRequestId("key");
        StepVerifier.create(f.service.update("v", "r", "package", update))
                .expectErrorMatches(
                        error -> error instanceof PrototypeHubException p && p.getStatus() == 403)
                .verify();
        verifyNoInteractions(f.packages, f.receipts, f.files, f.cleanup);
        var order = inOrder(f.access);
        order.verify(f.access).lockActiveUsers("u");
        order.verify(f.access).lockProject("p");
        order.verify(f.access).lockVersion("v");
    }

    @Test
    void importReplayAfterDeletionReturnsMissingWithoutCreatingOrCounting() {
        var f = new Fixture();
        var query = f.query(Flux.empty());
        f.requests();
        when(f.receipts.replay(any(), any()))
                .thenAnswer(call -> ((Function<String, Mono<?>>) call.getArgument(1)).apply("v"));
        var document = new DesignTokenDocument();
        document.setValid(true);
        var candidate =
                new PrototypeDesignTokenPackageFileService.Candidate(
                        "candidate", "tokens.json", 2, "hash", document);
        when(f.files.upload(anyString(), anyString(), any(), anyBoolean(), any()))
                .thenAnswer(
                        call ->
                                ((Function<
                                                        PrototypeDesignTokenPackageFileService
                                                                .Candidate,
                                                        Mono<
                                                                PrototypeDesignTokenPackageFileService
                                                                                .Stored<
                                                                        DesignTokenPackageImportResponse>>>)
                                                call.getArgument(4))
                                        .apply(candidate)
                                        .map(
                                                PrototypeDesignTokenPackageFileService.Stored
                                                        ::response));
        StepVerifier.create(
                        f.service.importPackage(
                                "v",
                                "r",
                                mock(FilePart.class),
                                new RevisionMaterialRequests.Source(),
                                "false",
                                "original-key"))
                .assertNext(
                        result -> {
                            assertEquals("missing", result.status());
                            assertNotNull(result.id());
                            assertNull(result.value());
                        })
                .verifyComplete();
        verify(query, never()).count();
        verify(f.packages, never()).insert(any(PrototypeDesignTokenPackageEntity.class));
        verify(f.receipts, never()).record(any(), anyString(), anyString());
    }

    @Test
    void readRechecksCurrentAuthorizationAfterFileWork() {
        var f = new Fixture();
        f.query(Flux.just(row()));
        when(f.access.actor("u")).thenReturn(Mono.just(f.actor), Mono.just(f.revoked));
        when(f.files.read(any()))
                .thenReturn(
                        Mono.just(
                                ResponseEntity.ok(
                                        (Resource) new ByteArrayResource(new byte[] {1}))));
        StepVerifier.create(f.service.read("v", "r", "package"))
                .expectErrorMatches(
                        error -> error instanceof PrototypeHubException p && p.getStatus() == 403)
                .verify();
        verify(f.files).read(any());
    }

    @Test
    void staleSourceCasDoesNotTouchBytesOrEnqueueCleanup() {
        var f = new Fixture();
        var row = row();
        row.setConfigVersion(2L);
        f.query(Flux.just(row));
        f.requests();
        when(f.receipts.replay(any(), any())).thenReturn(Mono.empty());
        var input = new RevisionMaterialRequests.Update();
        input.setExpectedVersion(1L);
        input.setClientRequestId("source-key");
        input.setSourceName("new");
        StepVerifier.create(f.service.update("v", "r", "package", input))
                .expectErrorMatches(
                        error -> error instanceof PrototypeHubException p && p.getStatus() == 409)
                .verify();
        verify(f.packages, never()).createUpdate();
        verifyNoInteractions(f.files, f.cleanup);
    }

    private static PrototypeDesignTokenPackageEntity row() {
        var row = new PrototypeDesignTokenPackageEntity();
        row.setId("package");
        row.setProjectId("p");
        row.setVersionId("v");
        row.setRevisionId("r");
        row.setConfigVersion(0L);
        row.setSha256("hash");
        row.setStoragePath("candidate");
        return row;
    }

    private static final class Fixture {
        final ReactiveRepository<PrototypeDesignTokenPackageEntity, String> packages =
                mock(ReactiveRepository.class);
        final PrototypeDesignTokenPackageFileService files =
                mock(PrototypeDesignTokenPackageFileService.class);
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final PrototypeRevisionService revisions = mock(PrototypeRevisionService.class);
        final PrototypeCreationReceiptService receipts =
                mock(PrototypeCreationReceiptService.class);
        final FileCleanupService cleanup = mock(FileCleanupService.class);
        final Actor actor;
        final Actor revoked = new Actor("u", mock(Authentication.class), false);
        final PrototypeDesignTokenPackageService service;

        Fixture() {
            var tx = mock(TransactionalOperator.class);
            when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
            var auth = mock(Authentication.class);
            when(auth.hasPermission("prototype-hub-version", "save")).thenReturn(true);
            when(auth.hasPermission("prototype-hub-version", "query")).thenReturn(true);
            actor = new Actor("u", auth, false);
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            var revision = new PrototypeRevisionEntity();
            revision.setId("r");
            revision.setProjectId("p");
            revision.setVersionId("v");
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.actor("u")).thenReturn(Mono.just(actor));
            for (var action : List.of(Action.READ, Action.EDIT)) {
                when(access.requireVersion(actor, "v", action, true))
                        .thenReturn(Mono.just(version));
                when(access.requireVersion(actor, "v", action, false))
                        .thenReturn(Mono.just(version));
            }
            when(revisions.require(version, "r")).thenReturn(Mono.just(revision));
            when(access.lockActiveUsers("u")).thenReturn(Mono.empty());
            when(access.lockProject("p")).thenReturn(Mono.empty());
            when(access.lockVersion("v")).thenReturn(Mono.empty());
            service =
                    new PrototypeDesignTokenPackageService(
                            packages,
                            files,
                            access,
                            revisions,
                            receipts,
                            cleanup,
                            tx,
                            new ObjectMapper());
        }

        ReactiveQuery<PrototypeDesignTokenPackageEntity> query(
                Flux<PrototypeDesignTokenPackageEntity> rows) {
            ReactiveQuery<PrototypeDesignTokenPackageEntity> query =
                    mock(ReactiveQuery.class, RETURNS_SELF);
            when(packages.createQuery()).thenReturn(query);
            when(query.fetch()).thenReturn(rows);
            return query;
        }

        void requests() {
            var real = new PrototypeCreationReceiptService();
            when(receipts.request(
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            any(String[].class)))
                    .thenAnswer(
                            call ->
                                    real.request(
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            (String[]) call.getRawArguments()[4]));
        }
    }
}
