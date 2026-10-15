package com.gaiys.proto.logging.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.properties.LogStorageProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LogStoreTest {
    @Test
    void saturatedUnavailableSinkRecoversWithoutBlockingProducer() throws Exception {
        var config = new LogStorageProperties();
        var settings = config.getElasticsearch();
        settings.setManageTemplate(false);
        settings.setBatchSize(2);
        settings.setQueueCapacity(4);
        settings.setFlushInterval(Duration.ofMillis(20));
        settings.setMaxRetries(0);
        settings.setRequestTimeout(Duration.ofMillis(50));
        var outage = new AtomicBoolean(true);
        var recovered = new CountDownLatch(1);
        var client = mock(ElasticsearchAsyncClient.class);
        when(client.bulk(any(BulkRequest.class)))
                .thenAnswer(
                        call -> {
                            if (outage.get()) return new CompletableFuture<BulkResponse>();
                            recovered.countDown();
                            return CompletableFuture.completedFuture(
                                    BulkResponse.of(b -> b.errors(false).took(1).items(List.of())));
                        });
        @SuppressWarnings("unchecked")
        ObjectProvider<ElasticsearchAsyncClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        var metrics = new SimpleMeterRegistry();
        var store = new LogStore(config, provider, metrics);
        store.start();
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++)
                store.offer(
                        LogDocument.builder().kind("system").message("controlled burst").build());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2))
                            < 0);
            assertTrue(metrics.counter("proto.logging.queue.dropped").count() > 0);
            outage.set(false);
            store.offer(LogDocument.builder().kind("system").message("recovered").build());
            assertTrue(recovered.await(5, TimeUnit.SECONDS));
        } finally {
            store.close();
        }
    }
}
