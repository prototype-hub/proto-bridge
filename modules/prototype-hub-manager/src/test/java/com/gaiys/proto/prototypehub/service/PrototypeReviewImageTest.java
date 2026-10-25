package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 实际解码及上传所有权门控；不能将取消或响应失败当成数据库回滚。 */
class PrototypeReviewImageTest {
    @Test
    void acceptsActualPngAndJpegAndRejectsUnsupportedCorruptAndDimensionBombs() throws Exception {
        for (String format : new String[] {"png", "jpeg"}) {
            byte[] bytes = image(format);
            var metadata = PrototypeReviewImageService.inspect(bytes);
            assertEquals(3, metadata.width());
            assertEquals(2, metadata.height());
            assertEquals(bytes.length, metadata.size());
            assertEquals("image/" + format, metadata.contentType());
        }
        for (byte[] bytes :
                new byte[][] {
                    new byte[0],
                    "<svg></svg>".getBytes(),
                    "GIF89a".getBytes(),
                    new byte[] {(byte) 0xff, (byte) 0xd8, 0, (byte) 0xd9}
                }) {
            assertThrows(
                    PrototypeHubException.class, () -> PrototypeReviewImageService.inspect(bytes));
        }
        byte[] bomb = image("png");
        ByteBuffer.wrap(bomb).putInt(16, 16385);
        assertThrows(PrototypeHubException.class, () -> PrototypeReviewImageService.inspect(bomb));
        ByteBuffer.wrap(bomb).putInt(16, 4001).putInt(20, 4000);
        assertThrows(PrototypeHubException.class, () -> PrototypeReviewImageService.inspect(bomb));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        PrototypeReviewImageService.inspect(
                                new byte[PrototypeReviewImageService.MAX_BYTES + 1]));
    }

    @Test
    void cancellationWaitsForPhysicalFinallyAndClosesLateWriters() {
        var attempt = new PrototypeReviewImageService.Attempt();
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
        var storage = mock(PrototypeStorageService.class);
        when(storage.deleteReviewImage(anyString(), anyString())).thenReturn(Mono.empty());
        var service = new PrototypeReviewImageService(null, null, storage, null, null);
        var attempt = new PrototypeReviewImageService.Attempt();
        attempt.ownDirectory();
        var manager = mock(TransactionSynchronizationManager.class);
        var synchronization = ArgumentCaptor.forClass(TransactionSynchronization.class);
        assertTrue(attempt.enlist(manager));
        verify(manager).registerSynchronization(synchronization.capture());
        attempt.candidateOwned(owned);
        StepVerifier.create(service.release("p", "v", "path", attempt))
                .then(
                        () -> {
                            verifyNoInteractions(storage);
                            synchronization
                                    .getValue()
                                    .afterCompletion(status)
                                    .block(Duration.ofSeconds(2));
                        })
                .verifyComplete();
        if (deleted) verify(storage).deleteReviewImage("p", "path");
        else verifyNoInteractions(storage);
    }

    @Test
    void failedCompensationPersistsRetryOnlyAfterDefiniteRollback() {
        var storage = mock(PrototypeStorageService.class);
        when(storage.deleteReviewImage(anyString(), anyString()))
                .thenReturn(Mono.error(new java.io.IOException()));
        var cleanup = mock(FileCleanupService.class);
        when(cleanup.enqueueReviewImage(anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        var service = new PrototypeReviewImageService(null, null, storage, cleanup, null);
        var attempt = new PrototypeReviewImageService.Attempt();
        attempt.ownDirectory();
        service.release("p", "v", "path", attempt).block(Duration.ofSeconds(2));
        verify(cleanup).enqueueReviewImage("p", "v", "path");
        assertFalse(attempt.enlist(mock(TransactionSynchronizationManager.class)));
    }

    private static byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(
                ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }
}
