package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.validation.RevisionMaterialSource;
import com.gaiys.proto.prototypehub.storage.materials.RevisionMaterialFiles;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeRevisionMaterialTest {
    @TempDir Path directory;

    @Test
    void sourceIsManualNormalizedAndNeverAcceptsUnsafeOrCredentialUrls() {
        var blank = RevisionMaterialSource.of(new RevisionMaterialRequests.Source());
        assertFalse(blank.recorded());
        var source =
                RevisionMaterialSource.of(
                        RevisionMaterialRequests.Source.of(
                                " Axure ", "Export", "https://example.org/design", "manual"));
        assertEquals("Axure", source.tool());
        assertTrue(source.recorded());
        for (String url :
                List.of(
                        "javascript:alert(1)",
                        "file:///etc/passwd",
                        "/relative",
                        "https://user:pass@example.org/",
                        "https://example.org/\n")) {
            assertThrows(
                    PrototypeHubException.class,
                    () ->
                            RevisionMaterialSource.of(
                                    RevisionMaterialRequests.Source.of(null, null, url, null)));
        }
        assertThrows(
                PrototypeHubException.class,
                () ->
                        RevisionMaterialSource.of(
                                RevisionMaterialRequests.Source.of(
                                        "x".repeat(81), null, null, null)));
    }

    @Test
    void privateMaterialPathsNeverAcceptClientNamesOrSymlinkParents() throws Exception {
        assertEquals(
                directory.resolve("projects/p/materials/m/content").toAbsolutePath(),
                SafePaths.material(directory, "p", "projects/p/materials/m/content"));
        for (String path :
                List.of(
                        "projects/p/materials/../content",
                        "projects/other/materials/m/content",
                        "projects/p/materials/m/x/content",
                        "projects/p/materials/m/content/../content")) {
            assertThrows(
                    PrototypeHubException.class, () -> SafePaths.material(directory, "p", path));
        }
        Path parent = directory.resolve("projects/p/materials");
        Files.createDirectories(parent);
        Files.createSymbolicLink(parent.resolve("m"), directory);
        assertThrows(
                PrototypeHubException.class,
                () -> SafePaths.material(directory, "p", "projects/p/materials/m/content"));
        for (String name : List.of("../file.pdf", "C:\\file.txt", "a\n.txt", "x".repeat(256))) {
            assertThrows(PrototypeHubException.class, () -> RevisionMaterialFiles.filename(name));
        }
    }

    @Test
    void documentsRequireSafeBoundedFormatAndOfficeContainersAreNotExtracted() throws Exception {
        assertEquals(
                "application/pdf",
                RevisionMaterialFiles.inspect(
                                "%PDF-1.7\noriginal".getBytes(StandardCharsets.US_ASCII),
                                "notes.PDF",
                                null)
                        .mediaType());
        assertEquals(
                "text/markdown",
                RevisionMaterialFiles.inspect(
                                "# 原始说明".getBytes(StandardCharsets.UTF_8), "prd.md", null)
                        .mediaType());
        for (String name : List.of("index.html", "image.svg", "source.rp", "archive.zip")) {
            assertThrows(
                    PrototypeHubException.class,
                    () -> RevisionMaterialFiles.inspect(new byte[] {1}, name, null));
        }
        assertThrows(
                Exception.class,
                () -> RevisionMaterialFiles.inspect(new byte[] {(byte) 0xff}, "notes.txt", null));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        RevisionMaterialFiles.inspect(
                                "<html>page".getBytes(StandardCharsets.UTF_8), "notes.txt", null));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        RevisionMaterialFiles.inspect(
                                new byte[RevisionMaterialFiles.MAX_DOCUMENT_BYTES + 1],
                                "notes.pdf",
                                null));
        Path office = office("[Content_Types].xml", "word/document.xml");
        assertEquals(
                "document",
                RevisionMaterialFiles.inspect(Files.readAllBytes(office), "notes.docx", office)
                        .type());
        for (String extra :
                List.of(
                        "word/vbaProject.bin",
                        "../escape.xml",
                        "xl/macrosheets/sheet1.xml",
                        "xl/intlMacrosheets/sheet1.xml")) {
            Path invalid = office("[Content_Types].xml", "word/document.xml", extra);
            assertThrows(
                    PrototypeHubException.class,
                    () ->
                            RevisionMaterialFiles.inspect(
                                    Files.readAllBytes(invalid), "notes.docx", invalid));
        }
        Path wrong = office("[Content_Types].xml", "xl/workbook.xml");
        assertThrows(
                PrototypeHubException.class,
                () ->
                        RevisionMaterialFiles.inspect(
                                Files.readAllBytes(wrong), "notes.docx", wrong));
    }

    @Test
    void macroContentTypesCannotHideBehindAnOrdinaryOfficeExtension() throws Exception {
        for (String marker :
                List.of(
                        "application/vnd.ms-excel.sheet.macroEnabled.main+xml",
                        "application/vnd.ms-excel.macrosheet+xml",
                        "macro&#69;nabled",
                        "<!DOCTYPE Types [<!ENTITY macro 'x'>]>")) {
            Path file = Files.createTempFile(directory, "macro", ".bin");
            try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zip.write(marker.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
                zip.write("<fixture/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            assertThrows(
                    PrototypeHubException.class,
                    () ->
                            RevisionMaterialFiles.inspect(
                                    Files.readAllBytes(file), "notes.xlsx", file));
        }
    }

    @Test
    void cancellationWaitsForPhysicalFinallyAndClosesLateWriters() {
        var attempt = new PrototypeRevisionMaterialFileService.Attempt();
        assertTrue(attempt.beginWriting());
        var released = new AtomicBoolean();
        StepVerifier.create(attempt.closeWriting().doOnSuccess(ignored -> released.set(true)))
                .then(
                        () -> {
                            assertFalse(released.get());
                            assertFalse(attempt.beginWriting());
                            attempt.completeWriting();
                        })
                .verifyComplete();
        assertTrue(released.get());
    }

    @ParameterizedTest
    @CsvSource({"0,true,false", "0,false,true", "1,true,true", "2,true,false", "2,false,false"})
    void cleanupWaitsActualTransactionOutcomeAndNeverDeletesUnknown(
            int status, boolean owned, boolean deleted) {
        var service = spy(new PrototypeRevisionMaterialFileService(null, null, null, null));
        doReturn(Mono.empty()).when(service).delete("p", "path");
        var attempt = new PrototypeRevisionMaterialFileService.Attempt();
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

    private Path office(String... entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("<fixture/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path file = Files.createTempFile(directory, "office", ".bin");
        Files.write(file, bytes.toByteArray());
        return file;
    }
}
