package com.gaiys.proto.prototypehub.storage.materials.text;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

/** 真子JVM绘图验证页边界、资源预算和失败回收，不以文本提取测试代替视觉输出。 */
class PdfPreviewProcessTest {
    @Test
    void rendersOnlyTheChosenPageAtFixedDpiAndKeepsOriginalBytes() throws Exception {
        byte[] source = pdf(2, 300, 150, false);
        byte[] original = source.clone();
        var first = PdfPreviewProcess.render(source, 0);
        var second = PdfPreviewProcess.render(source, 1);
        assertEquals(2, first.pages());
        assertEquals(400, first.width());
        assertEquals(200, first.height());
        assertEquals(1, second.page());
        var red = ImageIO.read(new ByteArrayInputStream(first.bytes()));
        var blue = ImageIO.read(new ByteArrayInputStream(second.bytes()));
        assertEquals(0xff0000, red.getRGB(50, 50) & 0xffffff);
        assertEquals(0x0000ff, blue.getRGB(50, 50) & 0xffffff);
        assertArrayEquals(original, source);
    }

    @Test
    void rejectsEncryptionBrokenInputPageRangeAndGeometryOrPageBudgets() throws Exception {
        assertCode("ENCRYPTED", () -> PdfPreviewProcess.render(pdf(1, 300, 150, true), 0));
        assertCode("INVALID", () -> PdfPreviewProcess.render("%PDF-broken".getBytes(), 0));
        assertCode("PAGE_INVALID", () -> PdfPreviewProcess.render(pdf(1, 300, 150, false), 1));
        assertCode("LIMIT_EXCEEDED", () -> PdfPreviewProcess.render(pdf(101, 300, 150, false), 0));
        assertCode("LIMIT_EXCEEDED", () -> PdfPreviewProcess.render(pdf(1, 4000, 4000, false), 0));
        assertCode(
                "LIMIT_EXCEEDED",
                () ->
                        PdfPreviewProcess.render(
                                new byte[MaterialTextExtractor.MAX_INPUT_BYTES + 1], 0));
    }

    @Test
    void cancellationWhileWorkerIsAliveReapsThatProcessAndItsPrivateFiles() throws Exception {
        Set<Path> before = temporaryDirectories();
        byte[] source = pdf(100, 300, 150, false);
        var executor = Executors.newSingleThreadExecutor();
        long worker = -1;
        try {
            var task = executor.submit(() -> PdfPreviewProcess.render(source, 0));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (worker < 0 && !task.isDone() && System.nanoTime() < deadline) {
                worker =
                        ProcessHandle.current()
                                .children()
                                .filter(
                                        child ->
                                                child.info()
                                                        .commandLine()
                                                        .orElse("")
                                                        .contains(PdfPreviewWorker.class.getName()))
                                .mapToLong(ProcessHandle::pid)
                                .findFirst()
                                .orElse(-1);
                if (worker < 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            assertTrue(worker > 0, "observed the actual running PDF worker before cancellation");
            assertTrue(ProcessHandle.of(worker).orElseThrow().isAlive());
            assertTrue(task.cancel(true));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertFalse(ProcessHandle.of(worker).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(before, temporaryDirectories());
    }

    @Test
    void hardTimeoutAndThreadCancellationRemovePrivateInputsAndReapWorkers() throws Exception {
        Set<Path> before = temporaryDirectories();
        assertCode("LIMIT_EXCEEDED", () -> PdfPreviewProcess.render(pdf(1, 300, 150, false), 0, 1));
        assertEquals(before, temporaryDirectories());
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    java.io.IOException.class,
                    () -> PdfPreviewProcess.render(pdf(1, 300, 150, false), 0));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(before, temporaryDirectories());
        assertTrue(
                ProcessHandle.current()
                        .children()
                        .noneMatch(
                                child ->
                                        child.info()
                                                .commandLine()
                                                .orElse("")
                                                .contains(PdfPreviewWorker.class.getName())));
    }

    private Set<Path> temporaryDirectories() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .startsWith("prototype-material-preview-"))
                    .collect(Collectors.toSet());
        }
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(PrototypeHubException.class, action);
        assertEquals("PROTOTYPE_HUB_MATERIAL_PREVIEW_" + code, error.getCode());
    }

    private byte[] pdf(int pages, float width, float height, boolean encrypted) throws Exception {
        try (var document = new PDDocument();
                var bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(new PDRectangle(width, height));
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(i == 0 ? java.awt.Color.RED : java.awt.Color.BLUE);
                    content.addRect(0, 0, width, height);
                    content.fill();
                }
            }
            if (encrypted)
                document.protect(
                        new StandardProtectionPolicy("owner", "reader", new AccessPermission()));
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
