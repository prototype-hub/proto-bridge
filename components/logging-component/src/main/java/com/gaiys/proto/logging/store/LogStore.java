package com.gaiys.proto.logging.store;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.FieldType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.ilm.PutLifecycleRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.masker.SensitiveValueMasker;
import com.gaiys.proto.logging.properties.LogStorageProperties;
import com.gaiys.proto.logging.query.LogQuery;
import com.gaiys.proto.logging.translator.LogQueryTranslator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Stores recent masked logs locally and optionally writes ordered batches to Elasticsearch.
 *
 * <p>The local cache is bounded by capacity and retention. Spring owns the background subscription
 * and query worker; Elasticsearch failures do not remove the local copy or interrupt producers.
 * Existing Micrometer counters and the bootstrap health indicator cover failures and availability,
 * so this component does not add per-record tracing or a separate MBean management surface.
 */
@Component
public class LogStore {
    private final Cache<String, LogDocument> local;
    private final ElasticsearchAsyncClient client;
    private final LogStorageProperties.Elasticsearch settings;
    private final Sinks.Many<LogDocument> queue;
    private final Counter failed, dropped, written;
    private final Scheduler queries = Schedulers.newBoundedElastic(2, 64, "log-query");
    private Disposable subscription;
    private volatile boolean prepared;

    public LogStore(
            LogStorageProperties config,
            ObjectProvider<ElasticsearchAsyncClient> clients,
            MeterRegistry metrics) {
        settings = config.getElasticsearch();
        client = clients.getIfAvailable();
        if (config.getLocalCapacity() < 1
                || settings.getQueueCapacity() < 1
                || settings.getBatchSize() < 1
                || settings.getBatchSize() > 1000
                || settings.getMaxRetries() < 0
                || settings.getMaxRetries() > 5
                || settings.getRetentionDays() < 1
                || settings.getReplicas() < 0
                || settings.getReplicas() > 10
                || config.getLocalRetention().isNegative()
                || config.getLocalRetention().isZero()
                || settings.getRequestTimeout().isNegative()
                || settings.getRequestTimeout().isZero()
                || settings.getRetryBackoff().isNegative()
                || settings.getRetryBackoff().isZero()
                || settings.getFlushInterval().isNegative()
                || settings.getFlushInterval().isZero())
            throw new IllegalArgumentException("validation.log_settings_invalid");
        local =
                Caffeine.newBuilder()
                        .maximumSize(config.getLocalCapacity())
                        .expireAfterWrite(config.getLocalRetention())
                        .build();
        queue =
                Sinks.many()
                        .unicast()
                        .onBackpressureBuffer(
                                new ArrayBlockingQueue<>(settings.getQueueCapacity()));
        failed = metrics.counter("proto.logging.es.failures");
        dropped = metrics.counter("proto.logging.queue.dropped");
        written = metrics.counter("proto.logging.es.written");
    }

    @PostConstruct
    public void start() {
        if (client == null) return;
        // One lifecycle subscription owns bounded batches; concatMap preserves write order.
        subscription =
                queue.asFlux()
                        .bufferTimeout(settings.getBatchSize(), settings.getFlushInterval(), true)
                        .concatMap(this::writeBatch)
                        .subscribe();
    }

    private Mono<Void> writeBatch(List<LogDocument> batch) {
        return write(batch)
                .retryWhen(Retry.backoff(settings.getMaxRetries(), settings.getRetryBackoff()))
                .onErrorResume(error -> recoverWriteFailure(batch.size()));
    }

    private Mono<Void> recoverWriteFailure(int size) {
        // A failed batch remains observable without terminating delivery of subsequent batches.
        failed.increment(size);
        LoggerFactory.getLogger("proto.logging.fallback")
                .warn(
                        "Elasticsearch log write unavailable; local JSON and recent log cache retained");
        return Mono.empty();
    }

    public synchronized void offer(LogDocument document) {
        document.identified();
        if (document.getMessage() != null)
            document.setMessage(SensitiveValueMasker.redact(document.getMessage()));
        if (document.getDescribe() != null)
            document.setDescribe(SensitiveValueMasker.redact(document.getDescribe()));
        if (document.getExceptionStack() != null) document.setExceptionStack("[REDACTED]");
        local.put(document.getId(), document);
        if (client != null && queue.tryEmitNext(document).isFailure()) dropped.increment();
    }

    private Mono<Void> prepare() {
        if (prepared || !settings.isManageTemplate()) return Mono.empty();
        String policy =
                """
    {"policy":{"phases":{"hot":{"actions":{"rollover":{"max_age":"1d","max_primary_shard_size":"10gb"}}},"delete":{"min_age":"%sd","actions":{"delete":{}}}}}}
    """
                        .formatted(settings.getRetentionDays());
        String template =
                """
    {"index_patterns":["%s"],"priority":500,"data_stream":{},"template":{"settings":{"index.lifecycle.name":"%s","number_of_shards":1,"number_of_replicas":%d},"mappings":{"dynamic_templates":[{"strings":{"match_mapping_type":"string","mapping":{"type":"keyword","ignore_above":8192}}}],"properties":{"@timestamp":{"type":"date"},"createTime":{"type":"long"},"requestTime":{"type":"long"},"responseTime":{"type":"long"},"duration":{"type":"long"},"status":{"type":"integer"},"httpHeaders":{"type":"object","enabled":false},"parameters":{"type":"object","enabled":false}}}}}
    """
                        .formatted(
                                settings.getDataStream(),
                                settings.getIlmPolicy(),
                                settings.getReplicas());
        // Preparation is ordered: policy, template, stream, then historical field mappings.
        return putLifecycle(policy)
                .then(putIndexTemplate(template))
                .then(createDataStream())
                .then(putRequestMetadataMapping())
                .doOnSuccess(ignored -> prepared = true)
                .then();
    }

    private Mono<Void> putLifecycle(String policy) {
        return Mono.fromFuture(
                        () ->
                                client.ilm()
                                        .putLifecycle(
                                                new PutLifecycleRequest.Builder()
                                                        .name(settings.getIlmPolicy())
                                                        .withJson(new StringReader(policy))
                                                        .build()))
                .then();
    }

    private Mono<Void> putIndexTemplate(String template) {
        return Mono.fromFuture(
                        () ->
                                client.indices()
                                        .putIndexTemplate(
                                                new PutIndexTemplateRequest.Builder()
                                                        .name(settings.getDataStream())
                                                        .withJson(new StringReader(template))
                                                        .build()))
                .then();
    }

    private Mono<Void> createDataStream() {
        return Mono.fromFuture(
                        () ->
                                client.indices()
                                        .createDataStream(q -> q.name(settings.getDataStream())))
                .onErrorResume(
                        error ->
                                isElasticsearchError(error, "resource_already_exists_exception")
                                        ? Mono.empty()
                                        : Mono.error(error))
                .then();
    }

    private Mono<Void> putRequestMetadataMapping() {
        // Keep maps in _source without indexing arbitrary request keys, including historical
        // indices.
        return Mono.fromFuture(
                        () ->
                                client.indices()
                                        .putMapping(
                                                new PutMappingRequest.Builder()
                                                        .index(settings.getDataStream())
                                                        .withJson(
                                                                new StringReader(
                                                                        "{\"properties\":{\"httpHeaders\":{\"type\":\"object\",\"enabled\":false},\"parameters\":{\"type\":\"object\",\"enabled\":false}}}"))
                                                        .build()))
                .then();
    }

    private Mono<Void> write(List<LogDocument> documents) {
        var bulk = bulkRequest(documents);
        return prepare()
                .timeout(settings.getRequestTimeout())
                .then(
                        Mono.fromFuture(() -> client.bulk(bulk))
                                .timeout(settings.getRequestTimeout()))
                .flatMap(response -> acceptBulkResponse(response, documents.size()));
    }

    private BulkRequest bulkRequest(List<LogDocument> documents) {
        var request = new BulkRequest.Builder();
        for (var doc : documents)
            request.operations(
                    op ->
                            op.create(
                                    create ->
                                            create.index(settings.getDataStream())
                                                    .id(doc.getId())
                                                    .document(doc)));
        return request.build();
    }

    private Mono<Void> acceptBulkResponse(BulkResponse response, int size) {
        // Retry can replay created IDs; 409 means that the previously accepted copy is retained.
        if (response.items().stream()
                .anyMatch(item -> item.error() != null && item.status() != 409))
            return Mono.error(new IllegalStateException("log_bulk_rejected"));
        written.increment(size);
        return Mono.empty();
    }

    public Mono<LogQuery.Page> query(String kind, LogQuery query) {
        query.validate();
        if (client == null)
            return Mono.fromCallable(() -> queryLocal(kind, query)).subscribeOn(queries);
        var search = searchRequest(kind, query);
        return Mono.fromFuture(() -> client.search(search, LogDocument.class))
                .timeout(settings.getRequestTimeout())
                .map(response -> searchPage(response, query))
                .onErrorResume(error -> recoverQueryFailure(error, query));
    }

    private LogQuery.Page queryLocal(String kind, LogQuery query) {
        // This snapshot is bounded by the configured local cache capacity.
        var all =
                local.asMap().values().stream()
                        .filter(LogQueryTranslator.memory(kind, query))
                        .sorted(LogQueryTranslator.order(query))
                        .toList();
        return new LogQuery.Page(
                all.size(),
                query.getPageIndex(),
                query.getPageSize(),
                all.stream()
                        .skip((long) query.getPageIndex() * query.getPageSize())
                        .limit(query.getPageSize())
                        .toList());
    }

    private SearchRequest searchRequest(String kind, LogQuery query) {
        var request =
                new SearchRequest.Builder()
                        .index(settings.getDataStream())
                        .from(query.getPageIndex() * query.getPageSize())
                        .size(query.getPageSize())
                        .trackTotalHits(t -> t.enabled(true))
                        .query(LogQueryTranslator.elastic(kind, query));
        var sorts =
                query.getSorts().isEmpty()
                        ? List.of(new LogQuery.Sort("createTime", "desc"))
                        : query.getSorts();
        for (var sort : sorts)
            request.sort(
                    s ->
                            s.field(
                                    f ->
                                            f.field(sort.name())
                                                    // Historical indices can have no mapping for
                                                    // newly captured fields.
                                                    .unmappedType(
                                                            LogQuery.numeric(sort.name())
                                                                    ? FieldType.Long
                                                                    : FieldType.Keyword)
                                                    // Match the local comparator: missing first
                                                    // ascending, last descending.
                                                    .missing(
                                                            "asc".equalsIgnoreCase(sort.order())
                                                                    ? "_first"
                                                                    : "_last")
                                                    .order(
                                                            "asc".equalsIgnoreCase(sort.order())
                                                                    ? SortOrder.Asc
                                                                    : SortOrder.Desc)));
        return request.build();
    }

    private LogQuery.Page searchPage(SearchResponse<LogDocument> response, LogQuery query) {
        return new LogQuery.Page(
                response.hits().total() == null ? 0 : response.hits().total().value(),
                query.getPageIndex(),
                query.getPageSize(),
                response.hits().hits().stream()
                        .map(hit -> hit.source())
                        .filter(Objects::nonNull)
                        .toList());
    }

    private Mono<LogQuery.Page> recoverQueryFailure(Throwable error, LogQuery query) {
        if (isElasticsearchError(error, "index_not_found_exception"))
            return Mono.just(
                    new LogQuery.Page(0, query.getPageIndex(), query.getPageSize(), List.of()));
        return Mono.error(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "error.log_store_unavailable"));
    }

    private static boolean isElasticsearchError(Throwable error, String type) {
        return error instanceof ElasticsearchException es && type.equals(es.error().type());
    }

    public Mono<Boolean> ping() {
        return client == null
                ? Mono.just(true)
                : Mono.fromFuture(client::ping)
                        .timeout(settings.getRequestTimeout())
                        .map(value -> value.value())
                        .onErrorReturn(false);
    }

    @PreDestroy
    public void close() {
        // Stop background delivery before releasing the query worker.
        if (subscription != null) subscription.dispose();
        queries.dispose();
    }
}
