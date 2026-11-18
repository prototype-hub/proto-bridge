package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.storage.materials.text.MaterialTextExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.RevisionMaterialResponse;
import com.gaiys.proto.prototypehub.web.response.RevisionMaterialTextResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** 正文派生更新必须在锁外解析、提交重验且不覆盖人工来源或复活已删资料。 */
class PrototypeMaterialTextServiceTest {
    @TempDir Path directory;

    @Test
    void oldRowsHaveExplicitStatesAndListNeverContainsDocumentText() {
        var row = material();
        assertEquals("NOT_EXTRACTED", RevisionMaterialResponse.of(row).textStatus());
        assertEquals("", RevisionMaterialTextResponse.of(row).content());
        assertEquals(0, RevisionMaterialTextResponse.of(row).characterCount());
        row.setMaterialType("image");
        assertEquals("UNSUPPORTED", RevisionMaterialTextResponse.of(row).status());
        assertTrue(
                Arrays.stream(RevisionMaterialResponse.class.getRecordComponents())
                        .noneMatch(component -> component.getName().equals("textContent")));
    }

    @Test
    void directMetadataReadReturnsTheExactMaterialWithoutPagingOrFileReads() {
        var fixture = new Fixture();
        var row = material();
        text("私有正文").apply(row);
        when(fixture.query.fetch()).thenReturn(Flux.just(row));
        StepVerifier.create(fixture.service.detail("v", "r", "m"))
                .assertNext(
                        response -> {
                            assertEquals("m", response.id());
                            assertEquals("READY", response.textStatus());
                            assertEquals("requirements.md", response.fileName());
                        })
                .verifyComplete();
        verify(fixture.materials).createQuery();
        verifyNoInteractions(fixture.files);
        when(fixture.query.fetch()).thenReturn(Flux.empty());
        StepVerifier.create(fixture.service.detail("v", "r", "missing"))
                .expectErrorMatches(error -> error instanceof PrototypeHubException)
                .verify();
    }

    @Test
    void bothTextAndMetadataReadsRequireQueryBeforeLookingUpTheMaterial() {
        var fixture = new Fixture();
        when(fixture.auth.hasPermission("prototype-hub-version", "query")).thenReturn(false);
        StepVerifier.create(fixture.service.text("v", "r", "m")).expectError().verify();
        StepVerifier.create(fixture.service.detail("v", "r", "m")).expectError().verify();
        verify(fixture.materials, never()).createQuery();
        verifyNoInteractions(fixture.files);
    }

    @Test
    void existingFileExtractionKeepsExactBytesAndDetectsSameSizeHashCorruption() throws Exception {
        var row = material();
        byte[] bytes = "# 中文说明\n正文 needle 😀".getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve(row.getStoragePath());
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        row.setFileSize((long) bytes.length);
        row.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var properties = new PrototypeHubStorageProperties();
        properties.setRoot(directory);
        try (var work = new FileWork()) {
            var files = new PrototypeRevisionMaterialFileService(properties, work, null, null);
            var result = files.extractText(row).block(Duration.ofSeconds(5));
            assertEquals(MaterialTextExtractor.Status.READY, result.result().status());
            assertEquals(new String(bytes, StandardCharsets.UTF_8), result.result().content());
            assertArrayEquals(bytes, Files.readAllBytes(file));
            bytes[0] = '!';
            Files.write(file, bytes);
            StepVerifier.create(files.extractText(row))
                    .expectErrorMatches(error -> error instanceof PrototypeHubException)
                    .verify();
        }
    }

    @Test
    void parsingWaitsForDatabaseReleaseAndFinishesBeforeLocks() {
        var fixture = new Fixture();
        var release = Sinks.<Void>one();
        var releasing = new AtomicBoolean();
        var parsing = new AtomicBoolean();
        Flux<PrototypeRevisionMaterialEntity> rows =
                Flux.usingWhen(
                        Mono.just("connection"),
                        ignored -> Flux.just(material()),
                        ignored -> {
                            releasing.set(true);
                            return release.asMono();
                        });
        when(fixture.query.fetch()).thenReturn(rows, Flux.just(material()));
        when(fixture.files.extractText(any()))
                .thenAnswer(
                        ignored ->
                                Mono.defer(
                                        () -> {
                                            assertFalse(fixture.locked.get());
                                            assertFalse(fixture.transaction.get());
                                            parsing.set(true);
                                            return Mono.just(text("正文"));
                                        }));
        StepVerifier.create(fixture.service.extractText("v", "r", "m"))
                .then(
                        () -> {
                            assertTrue(releasing.get());
                            assertFalse(parsing.get());
                            release.tryEmitEmpty();
                        })
                .assertNext(response -> assertEquals("正文", response.content()))
                .verifyComplete();
        assertTrue(parsing.get());
        assertTrue(fixture.locked.get());
    }

    @Test
    void extractionRequiresBothQueryAndSaveBeforeReadingFile() {
        var fixture = new Fixture();
        when(fixture.auth.hasPermission("prototype-hub-version", "query")).thenReturn(false);
        StepVerifier.create(fixture.service.extractText("v", "r", "m")).expectError().verify();
        verify(fixture.files, never()).extractText(any());
        verify(fixture.materials, never()).createUpdate();
        when(fixture.auth.hasPermission("prototype-hub-version", "query")).thenReturn(true);
        when(fixture.auth.hasPermission("prototype-hub-version", "save")).thenReturn(false);
        StepVerifier.create(fixture.service.extractText("v", "r", "m")).expectError().verify();
        verify(fixture.files, never()).extractText(any());
    }

    @Test
    void revocationDuringParsingPreventsPublishingText() {
        var fixture = new Fixture();
        var parsed = Sinks.<PrototypeRevisionMaterialFileService.Text>one();
        when(fixture.files.extractText(any())).thenReturn(parsed.asMono());
        StepVerifier.create(fixture.service.extractText("v", "r", "m"))
                .then(
                        () -> {
                            when(fixture.auth.hasPermission("prototype-hub-version", "query"))
                                    .thenReturn(false);
                            parsed.tryEmitValue(text("不得发布"));
                        })
                .expectError()
                .verify();
        verify(fixture.materials, never()).createUpdate();
    }

    @Test
    void deletionDuringParsingNeverRecreatesMaterial() {
        var fixture = new Fixture();
        when(fixture.query.fetch()).thenReturn(Flux.just(material()), Flux.empty());
        StepVerifier.create(fixture.service.extractText("v", "r", "m"))
                .expectErrorMatches(error -> error instanceof PrototypeHubException)
                .verify();
        verify(fixture.materials, never()).createUpdate();
        verify(fixture.materials, never()).insert(any(PrototypeRevisionMaterialEntity.class));
    }

    @Test
    void repeatedCurrentSuccessfulExtractionReusesTheSnapshot() {
        var fixture = new Fixture();
        var row = material();
        text("已保存").apply(row);
        when(fixture.query.fetch()).thenReturn(Flux.just(row));
        StepVerifier.create(fixture.service.extractText("v", "r", "m"))
                .assertNext(response -> assertEquals("已保存", response.content()))
                .verifyComplete();
        verify(fixture.files, never()).extractText(any());
        verify(fixture.materials, never()).createUpdate();
    }

    @Test
    void parserUpgradeReextractsAndLeavesConcurrentManualMetadataUntouched() {
        var fixture = new Fixture();
        var previous = material();
        text("旧正文").apply(previous);
        previous.setTextExtractorVersion("old-extractor");
        var current = material();
        current.setConfigVersion(9L);
        current.setSourceNotes("解析期间保存的来源");
        current.setUpdatedAt(1000L);
        when(fixture.query.fetch()).thenReturn(Flux.just(previous), Flux.just(current));
        StepVerifier.create(fixture.service.extractText("v", "r", "m"))
                .assertNext(response -> assertEquals("正文", response.content()))
                .verifyComplete();
        assertEquals(9L, current.getConfigVersion());
        assertEquals("解析期间保存的来源", current.getSourceNotes());
        assertEquals(1000L, current.getUpdatedAt());
        verify(fixture.update).execute();
    }

    private static PrototypeRevisionMaterialFileService.Text text(String content) {
        return new PrototypeRevisionMaterialFileService.Text(
                new MaterialTextExtractor.Result(
                        MaterialTextExtractor.Status.READY,
                        content,
                        content.codePointCount(0, content.length()),
                        MaterialTextExtractor.VERSION),
                1234L);
    }

    private static PrototypeRevisionMaterialEntity material() {
        var row = new PrototypeRevisionMaterialEntity();
        row.setId("m");
        row.setProjectId("p");
        row.setVersionId("v");
        row.setRevisionId("r");
        row.setMaterialType("document");
        row.setFileName("requirements.md");
        row.setMediaType("text/markdown");
        row.setStoragePath("projects/p/materials/m/content");
        row.setConfigVersion(0L);
        row.setUpdatedAt(1L);
        row.setSha256("original-hash");
        return row;
    }

    private static class Fixture {
        final ReactiveRepository materials = mock(ReactiveRepository.class);
        final ReactiveQuery query = mock(ReactiveQuery.class, RETURNS_SELF);
        final ReactiveUpdate update = mock(ReactiveUpdate.class, RETURNS_SELF);
        final PrototypeRevisionMaterialFileService files =
                mock(PrototypeRevisionMaterialFileService.class);
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final PrototypeRevisionService revisions = mock(PrototypeRevisionService.class);
        final Authentication auth = mock(Authentication.class);
        final AtomicBoolean locked = new AtomicBoolean(), transaction = new AtomicBoolean();
        final PrototypeRevisionMaterialService service;

        Fixture() {
            var actor = new PrototypeProjectAccessService.Actor("u", auth, false);
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            var revision = new PrototypeRevisionEntity();
            revision.setId("r");
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.actor("u")).thenReturn(Mono.just(actor));
            when(auth.hasPermission(eq("prototype-hub-version"), anyString())).thenReturn(true);
            when(access.requireVersion(eq(actor), eq("v"), any(Action.class), anyBoolean()))
                    .thenReturn(Mono.just(version));
            when(access.lockActiveUsers("u"))
                    .thenAnswer(ignored -> Mono.fromRunnable(() -> locked.set(true)));
            when(access.lockProject("p")).thenReturn(Mono.empty());
            when(access.lockVersion("v")).thenReturn(Mono.empty());
            when(revisions.require(version, "r")).thenReturn(Mono.just(revision));
            when(materials.createQuery()).thenReturn(query);
            when(query.fetch()).thenReturn(Flux.just(material()));
            when(materials.createUpdate()).thenReturn(update);
            when(update.execute()).thenReturn(Mono.just(1));
            when(files.extractText(any())).thenReturn(Mono.just(text("正文")));
            var transactions = mock(TransactionalOperator.class);
            when(transactions.transactional(any(Mono.class)))
                    .thenAnswer(
                            call ->
                                    Mono.defer(
                                            () -> {
                                                transaction.set(true);
                                                return ((Mono<?>) call.getArgument(0))
                                                        .doFinally(
                                                                ignored -> transaction.set(false));
                                            }));
            service =
                    new PrototypeRevisionMaterialService(
                            materials, files, access, revisions, null, null, transactions);
        }
    }
}
