package com.gaiys.proto.prototypehub.service;

import static com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExtractor.error;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeCopyEntryEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeCopySnapshotEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExporter;
import com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExtractor;
import com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExtractor.Source;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.CopyInventoryRequests;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Deleted;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Entry;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Occurrence;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Page;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Report;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Saved;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 固定页首次提取与人工维护。沿账号→项目→版本锁序，数据/CAS/持久回执同事务；
 * 文件解析及来源JSON转换在FileWork，锁内只做有界数据库操作。复用HTTP/SQL追踪和checkpoint，
 * 不记录原文/链接/请求键，不增加常驻队列、缓存或MBean；四个请求级槽覆盖导出响应生命周期。
 */
@Service
@RequiredArgsConstructor
public class PrototypeCopyInventoryService {
    private final ReactiveRepository<PrototypeCopySnapshotEntity, String> snapshots;
    private final ReactiveRepository<PrototypeCopyEntryEntity, String> entries;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;
    private final PrototypeCopyExtractor extractor;
    private final PrototypeCopyExporter exporter;
    private final FileWork work;
    private final ObjectMapper mapper;
    private final Semaphore exports = new Semaphore(4);
    private static final TypeReference<List<Occurrence>> OCCURRENCES = new TypeReference<>() {};

    private record Scope(String user, Source source) {}

    private record Raw(
            PrototypeCopySnapshotEntity snapshot,
            int manualCount,
            List<PrototypeCopyEntryEntity> rows,
            int total) {}

    private record Changed(long inventoryVersion, PrototypeCopyEntryEntity entry) {}

    private record Prepared(
            PrototypeCopySnapshotEntity snapshot, List<PrototypeCopyEntryEntity> entries) {}

    public Mono<CopyInventoryResponse> context(String version, String revision, String page) {
        return prepare(version, revision, page, Action.READ)
                .flatMap(
                        scope ->
                                locked(scope, Action.READ, ignored -> raw(scope, List.of(), 0))
                                        .map(raw -> summary(scope.source(), raw)))
                .checkpoint("prototype-hub/copy/context");
    }

    public Mono<Page> query(
            String version, String revision, String page, CopyInventoryRequests.Query input) {
        return prepare(version, revision, page, Action.READ)
                .flatMap(
                        scope ->
                                locked(
                                                scope,
                                                Action.READ,
                                                ignored -> {
                                                    var query =
                                                            QueryParamEntity.newQuery()
                                                                    .where(
                                                                            PrototypeCopyEntryEntity
                                                                                    ::getSnapshotId,
                                                                            id(scope))
                                                                    .and(
                                                                            PrototypeCopyEntryEntity
                                                                                    ::getProjectId,
                                                                            scope.source()
                                                                                    .projectId())
                                                                    .and(
                                                                            PrototypeCopyEntryEntity
                                                                                    ::getVersionId,
                                                                            version)
                                                                    .and(
                                                                            PrototypeCopyEntryEntity
                                                                                    ::getRevisionId,
                                                                            revision)
                                                                    .and(
                                                                            PrototypeCopyEntryEntity
                                                                                    ::getPageId,
                                                                            page);
                                                    if (input.getKind() != null)
                                                        query.and(
                                                                PrototypeCopyEntryEntity::getKind,
                                                                "STATIC".equals(input.getKind())
                                                                        ? 0
                                                                        : 1);
                                                    if (input.getHasRequirement() != null) {
                                                        if (input.getHasRequirement())
                                                            query.not(
                                                                    PrototypeCopyEntryEntity
                                                                            ::getRequirementUrl,
                                                                    "");
                                                        else
                                                            query.and(
                                                                    PrototypeCopyEntryEntity
                                                                            ::getRequirementUrl,
                                                                    "");
                                                    }
                                                    String keyword =
                                                            plain(input.getKeyword(), 100).trim();
                                                    if (!keyword.isEmpty()) {
                                                        String term =
                                                                PrototypeTextTermFragmentBuilder
                                                                        .COPY_LITERAL_CONTAINS
                                                                        .getTermType();
                                                        query.nest()
                                                                .and(
                                                                        PrototypeCopyEntryEntity
                                                                                ::getText,
                                                                        term,
                                                                        keyword)
                                                                .or(
                                                                        PrototypeCopyEntryEntity
                                                                                ::getBusinessTerm,
                                                                        term,
                                                                        keyword)
                                                                .or(
                                                                        PrototypeCopyEntryEntity
                                                                                ::getNote,
                                                                        term,
                                                                        keyword)
                                                                .end();
                                                    }
                                                    query.orderByAsc(
                                                                    PrototypeCopyEntryEntity
                                                                            ::getKind)
                                                            .orderByAsc(
                                                                    PrototypeCopyEntryEntity
                                                                            ::getSourceOrder)
                                                            .orderByAsc(
                                                                    PrototypeCopyEntryEntity
                                                                            ::getCreatedAt)
                                                            .orderByAsc(
                                                                    PrototypeCopyEntryEntity
                                                                            ::getId);
                                                    var param = query.getParam();
                                                    param.doPaging(
                                                            input.getPageIndex(),
                                                            input.getPageSize());
                                                    param.setParallelPager(true);
                                                    return QueryHelper.queryPager(
                                                                    param,
                                                                    entries::createQuery,
                                                                    Function.identity())
                                                            .flatMap(
                                                                    result ->
                                                                            raw(
                                                                                    scope,
                                                                                    result
                                                                                            .getData(),
                                                                                    result
                                                                                            .getTotal()));
                                                })
                                        .flatMap(
                                                raw ->
                                                        work.call(
                                                                () ->
                                                                        new Page(
                                                                                summary(
                                                                                        scope
                                                                                                .source(),
                                                                                        raw),
                                                                                responses(
                                                                                        raw.rows()),
                                                                                raw.total(),
                                                                                input
                                                                                        .getPageIndex(),
                                                                                input
                                                                                        .getPageSize()))))
                .checkpoint("prototype-hub/copy/query");
    }

    public Mono<CopyInventoryResponse> extract(
            String version, String revision, String page, CopyInventoryRequests.Mutation input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope -> {
                            Request request =
                                    request(
                                            scope,
                                            "extract",
                                            input.getClientRequestId(),
                                            null,
                                            null);
                            return snapshot(scope)
                                    .hasElement()
                                    .flatMap(
                                            existing ->
                                                    existing
                                                            ? Mono.just(
                                                                    new Prepared(null, List.of()))
                                                            : work.call(() -> extracted(scope)))
                                    .flatMap(
                                            prepared ->
                                                    locked(
                                                            scope,
                                                            Action.EDIT,
                                                            ignored ->
                                                                    receipts.replay(
                                                                                    request,
                                                                                    resource ->
                                                                                            raw(
                                                                                                    scope,
                                                                                                    List
                                                                                                            .of(),
                                                                                                    0))
                                                                            .switchIfEmpty(
                                                                                    Mono.defer(
                                                                                            () ->
                                                                                                    snapshot(
                                                                                                                    scope)
                                                                                                            .flatMap(
                                                                                                                    row ->
                                                                                                                            record(
                                                                                                                                            scope,
                                                                                                                                            request)
                                                                                                                                    .then(
                                                                                                                                            raw(
                                                                                                                                                    scope,
                                                                                                                                                    List
                                                                                                                                                            .of(),
                                                                                                                                                    0)))
                                                                                                            .switchIfEmpty(
                                                                                                                    Mono
                                                                                                                            .defer(
                                                                                                                                    () -> {
                                                                                                                                        if (prepared
                                                                                                                                                        .snapshot()
                                                                                                                                                == null)
                                                                                                                                            return Mono
                                                                                                                                                    .error(
                                                                                                                                                            error(
                                                                                                                                                                    "CONFLICT",
                                                                                                                                                                    409));
                                                                                                                                        return snapshots
                                                                                                                                                .insert(
                                                                                                                                                        prepared
                                                                                                                                                                .snapshot())
                                                                                                                                                .then(
                                                                                                                                                        prepared.entries()
                                                                                                                                                                        .isEmpty()
                                                                                                                                                                ? Mono
                                                                                                                                                                        .empty()
                                                                                                                                                                : entries
                                                                                                                                                                        .insert(
                                                                                                                                                                                prepared
                                                                                                                                                                                        .entries()))
                                                                                                                                                .then(
                                                                                                                                                        record(
                                                                                                                                                                scope,
                                                                                                                                                                request))
                                                                                                                                                .then(
                                                                                                                                                        raw(
                                                                                                                                                                scope,
                                                                                                                                                                List
                                                                                                                                                                        .of(),
                                                                                                                                                                0));
                                                                                                                                    }))))))
                                    // 必须在事务成功提交后才将持久预算失败转换为422；不得回滚快照和回执。
                                    .flatMap(
                                            raw ->
                                                    "LIMIT_EXCEEDED"
                                                                    .equals(
                                                                            raw.snapshot()
                                                                                    .getExtractionState())
                                                            ? Mono.error(
                                                                    error("LIMIT_EXCEEDED", 422))
                                                            : Mono.just(
                                                                    summary(scope.source(), raw)));
                        })
                .checkpoint("prototype-hub/copy/extract");
    }

    private Prepared extracted(Scope scope) throws Exception {
        var source = scope.source();
        var extracted = extractor.extract(source);
        long now = System.currentTimeMillis();
        var row = new PrototypeCopySnapshotEntity();
        row.setId(id(scope));
        row.setProjectId(source.projectId());
        row.setVersionId(source.versionId());
        row.setRevisionId(source.revisionId());
        row.setPageId(source.pageId());
        row.setPagePath(source.pagePath());
        row.setManifestSha256(source.manifestSha256());
        row.setFileSha256(source.file().sha256());
        row.setExtractorVersion(1);
        row.setExtractionState(extracted.state());
        row.setLimitReason(extracted.limitReason());
        row.setStaticCount(extracted.literals().size());
        row.setConfigVersion(0L);
        row.setExtractedAt(now);
        var rows = new ArrayList<PrototypeCopyEntryEntity>();
        for (var literal : extracted.literals()) {
            var entry =
                    entry(scope, PrototypeCopyExtractor.digest(id(scope), literal.text()), 0, now);
            entry.setText(literal.text());
            entry.setSourceOrder(literal.occurrences().getFirst().ordinal());
            entry.setOccurrenceCount(literal.occurrences().size());
            entry.setOccurrencesJson(mapper.writeValueAsString(literal.occurrences()));
            rows.add(entry);
        }
        return new Prepared(row, rows);
    }

    public Mono<Saved> create(
            String version, String revision, String page, CopyInventoryRequests.Fields input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        Action.EDIT,
                                        ignored -> {
                                            String text = manual(input.getText());
                                            String term = plain(input.getBusinessTerm(), 200),
                                                    note = plain(input.getNote(), 2000),
                                                    url = url(input.getRequirementUrl());
                                            Request request =
                                                    request(
                                                            scope,
                                                            "create",
                                                            input.getClientRequestId(),
                                                            null,
                                                            null,
                                                            text,
                                                            term,
                                                            note,
                                                            url);
                                            return requiredSnapshot(scope)
                                                    .flatMap(
                                                            snapshot ->
                                                                    receipts.replay(
                                                                                    request,
                                                                                    resource ->
                                                                                            find(
                                                                                                            scope,
                                                                                                            request
                                                                                                                    .id())
                                                                                                    .map(
                                                                                                            row ->
                                                                                                                    new Changed(
                                                                                                                            snapshot
                                                                                                                                    .getConfigVersion(),
                                                                                                                            row)))
                                                                            .switchIfEmpty(
                                                                                    Mono.defer(
                                                                                            () ->
                                                                                                    scoped(
                                                                                                                    scope)
                                                                                                            .and(
                                                                                                                    PrototypeCopyEntryEntity
                                                                                                                            ::getKind,
                                                                                                                    1)
                                                                                                            .count()
                                                                                                            .flatMap(
                                                                                                                    count -> {
                                                                                                                        if (count
                                                                                                                                >= 200)
                                                                                                                            return Mono
                                                                                                                                    .error(
                                                                                                                                            error(
                                                                                                                                                    "MANUAL_LIMIT_EXCEEDED",
                                                                                                                                                    422));
                                                                                                                        var
                                                                                                                                row =
                                                                                                                                        entry(
                                                                                                                                                scope,
                                                                                                                                                request
                                                                                                                                                        .id(),
                                                                                                                                                1,
                                                                                                                                                System
                                                                                                                                                        .currentTimeMillis());
                                                                                                                        row
                                                                                                                                .setText(
                                                                                                                                        text);
                                                                                                                        row
                                                                                                                                .setBusinessTerm(
                                                                                                                                        term);
                                                                                                                        row
                                                                                                                                .setNote(
                                                                                                                                        note);
                                                                                                                        row
                                                                                                                                .setRequirementUrl(
                                                                                                                                        url);
                                                                                                                        return entries.insert(
                                                                                                                                        row)
                                                                                                                                .then(
                                                                                                                                        bump(
                                                                                                                                                scope,
                                                                                                                                                snapshot))
                                                                                                                                .flatMap(
                                                                                                                                        next ->
                                                                                                                                                record(
                                                                                                                                                                scope,
                                                                                                                                                                request)
                                                                                                                                                        .thenReturn(
                                                                                                                                                                new Changed(
                                                                                                                                                                        next,
                                                                                                                                                                        row)));
                                                                                                                    }))));
                                        }))
                .flatMap(
                        result ->
                                work.call(
                                        () ->
                                                new Saved(
                                                        result.inventoryVersion(),
                                                        response(result.entry()))))
                .checkpoint("prototype-hub/copy/create");
    }

    public Mono<Saved> update(
            String version,
            String revision,
            String page,
            String entryId,
            CopyInventoryRequests.Update input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        Action.EDIT,
                                        ignored -> {
                                            String term = plain(input.getBusinessTerm(), 200),
                                                    note = plain(input.getNote(), 2000),
                                                    url = url(input.getRequirementUrl());
                                            String suppliedText =
                                                    input.getText() == null
                                                            ? null
                                                            : plain(input.getText(), 2000);
                                            Request request =
                                                    request(
                                                            scope,
                                                            "update",
                                                            input.getClientRequestId(),
                                                            entryId,
                                                            input.getExpectedVersion(),
                                                            suppliedText,
                                                            term,
                                                            note,
                                                            url);
                                            return requiredSnapshot(scope)
                                                    .flatMap(
                                                            snapshot ->
                                                                    receipts.replay(
                                                                                    request,
                                                                                    resource ->
                                                                                            find(
                                                                                                            scope,
                                                                                                            entryId)
                                                                                                    .map(
                                                                                                            row ->
                                                                                                                    new Changed(
                                                                                                                            snapshot
                                                                                                                                    .getConfigVersion(),
                                                                                                                            row)))
                                                                            .switchIfEmpty(
                                                                                    Mono.defer(
                                                                                            () ->
                                                                                                    requireEntry(
                                                                                                                    scope,
                                                                                                                    entryId)
                                                                                                            .flatMap(
                                                                                                                    row -> {
                                                                                                                        if (row
                                                                                                                                                .getKind()
                                                                                                                                        == 0
                                                                                                                                && suppliedText
                                                                                                                                        != null)
                                                                                                                            return Mono
                                                                                                                                    .error(
                                                                                                                                            error(
                                                                                                                                                    "STATIC_IMMUTABLE",
                                                                                                                                                    400));
                                                                                                                        String
                                                                                                                                text =
                                                                                                                                        row
                                                                                                                                                                .getKind()
                                                                                                                                                        == 1
                                                                                                                                                ? manual(
                                                                                                                                                        suppliedText)
                                                                                                                                                : row
                                                                                                                                                        .getText();
                                                                                                                        checkVersion(
                                                                                                                                row,
                                                                                                                                input
                                                                                                                                        .getExpectedVersion());
                                                                                                                        long
                                                                                                                                next =
                                                                                                                                        row
                                                                                                                                                        .getConfigVersion()
                                                                                                                                                + 1,
                                                                                                                                now =
                                                                                                                                        System
                                                                                                                                                .currentTimeMillis();
                                                                                                                        var
                                                                                                                                update =
                                                                                                                                        entries.createUpdate()
                                                                                                                                                .set(
                                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                                ::getBusinessTerm,
                                                                                                                                                        term)
                                                                                                                                                .set(
                                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                                ::getNote,
                                                                                                                                                        note)
                                                                                                                                                .set(
                                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                                ::getRequirementUrl,
                                                                                                                                                        url)
                                                                                                                                                .set(
                                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                                ::getConfigVersion,
                                                                                                                                                        next)
                                                                                                                                                .set(
                                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                                ::getUpdatedAt,
                                                                                                                                                        now);
                                                                                                                        if (row
                                                                                                                                        .getKind()
                                                                                                                                == 1)
                                                                                                                            update
                                                                                                                                    .set(
                                                                                                                                            PrototypeCopyEntryEntity
                                                                                                                                                    ::getText,
                                                                                                                                            text);
                                                                                                                        return update.where(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getId,
                                                                                                                                        entryId)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getSnapshotId,
                                                                                                                                        id(
                                                                                                                                                scope))
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getProjectId,
                                                                                                                                        scope.source()
                                                                                                                                                .projectId())
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getVersionId,
                                                                                                                                        version)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getRevisionId,
                                                                                                                                        revision)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getPageId,
                                                                                                                                        page)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getConfigVersion,
                                                                                                                                        input
                                                                                                                                                .getExpectedVersion())
                                                                                                                                .execute()
                                                                                                                                .flatMap(
                                                                                                                                        changed ->
                                                                                                                                                changed
                                                                                                                                                                == 1
                                                                                                                                                        ? bump(
                                                                                                                                                                scope,
                                                                                                                                                                snapshot)
                                                                                                                                                        : Mono
                                                                                                                                                                .error(
                                                                                                                                                                        error(
                                                                                                                                                                                "CONFLICT",
                                                                                                                                                                                409)))
                                                                                                                                .flatMap(
                                                                                                                                        inventoryVersion ->
                                                                                                                                                record(
                                                                                                                                                                scope,
                                                                                                                                                                request)
                                                                                                                                                        .then(
                                                                                                                                                                Mono
                                                                                                                                                                        .fromSupplier(
                                                                                                                                                                                () -> {
                                                                                                                                                                                    row
                                                                                                                                                                                            .setText(
                                                                                                                                                                                                    text);
                                                                                                                                                                                    row
                                                                                                                                                                                            .setBusinessTerm(
                                                                                                                                                                                                    term);
                                                                                                                                                                                    row
                                                                                                                                                                                            .setNote(
                                                                                                                                                                                                    note);
                                                                                                                                                                                    row
                                                                                                                                                                                            .setRequirementUrl(
                                                                                                                                                                                                    url);
                                                                                                                                                                                    row
                                                                                                                                                                                            .setConfigVersion(
                                                                                                                                                                                                    next);
                                                                                                                                                                                    row
                                                                                                                                                                                            .setUpdatedAt(
                                                                                                                                                                                                    now);
                                                                                                                                                                                    return new Changed(
                                                                                                                                                                                            inventoryVersion,
                                                                                                                                                                                            row);
                                                                                                                                                                                })));
                                                                                                                    }))));
                                        }))
                .flatMap(
                        result ->
                                work.call(
                                        () ->
                                                new Saved(
                                                        result.inventoryVersion(),
                                                        response(result.entry()))))
                .checkpoint("prototype-hub/copy/update");
    }

    public Mono<Deleted> delete(
            String version,
            String revision,
            String page,
            String entryId,
            CopyInventoryRequests.Delete input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        Action.EDIT,
                                        ignored -> {
                                            Request request =
                                                    request(
                                                            scope,
                                                            "delete",
                                                            input.getClientRequestId(),
                                                            entryId,
                                                            input.getExpectedVersion());
                                            return requiredSnapshot(scope)
                                                    .flatMap(
                                                            snapshot ->
                                                                    receipts.replay(
                                                                                    request,
                                                                                    resource ->
                                                                                            Mono
                                                                                                    .just(
                                                                                                            new Deleted(
                                                                                                                    snapshot
                                                                                                                            .getConfigVersion(),
                                                                                                                    true)))
                                                                            .switchIfEmpty(
                                                                                    Mono.defer(
                                                                                            () ->
                                                                                                    requireEntry(
                                                                                                                    scope,
                                                                                                                    entryId)
                                                                                                            .flatMap(
                                                                                                                    row -> {
                                                                                                                        if (row
                                                                                                                                        .getKind()
                                                                                                                                != 1)
                                                                                                                            return Mono
                                                                                                                                    .error(
                                                                                                                                            error(
                                                                                                                                                    "STATIC_IMMUTABLE",
                                                                                                                                                    400));
                                                                                                                        checkVersion(
                                                                                                                                row,
                                                                                                                                input
                                                                                                                                        .getExpectedVersion());
                                                                                                                        return entries.createDelete()
                                                                                                                                .where(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getId,
                                                                                                                                        entryId)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getSnapshotId,
                                                                                                                                        id(
                                                                                                                                                scope))
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getProjectId,
                                                                                                                                        scope.source()
                                                                                                                                                .projectId())
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getVersionId,
                                                                                                                                        version)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getRevisionId,
                                                                                                                                        revision)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getPageId,
                                                                                                                                        page)
                                                                                                                                .and(
                                                                                                                                        PrototypeCopyEntryEntity
                                                                                                                                                ::getConfigVersion,
                                                                                                                                        input
                                                                                                                                                .getExpectedVersion())
                                                                                                                                .execute()
                                                                                                                                .flatMap(
                                                                                                                                        changed ->
                                                                                                                                                changed
                                                                                                                                                                == 1
                                                                                                                                                        ? bump(
                                                                                                                                                                scope,
                                                                                                                                                                snapshot)
                                                                                                                                                        : Mono
                                                                                                                                                                .error(
                                                                                                                                                                        error(
                                                                                                                                                                                "CONFLICT",
                                                                                                                                                                                409)))
                                                                                                                                .flatMap(
                                                                                                                                        next ->
                                                                                                                                                record(
                                                                                                                                                                scope,
                                                                                                                                                                request)
                                                                                                                                                        .thenReturn(
                                                                                                                                                                new Deleted(
                                                                                                                                                                        next,
                                                                                                                                                                        true)));
                                                                                                                    }))));
                                        }))
                .checkpoint("prototype-hub/copy/delete");
    }

    public Mono<Void> export(
            String version,
            String revision,
            String page,
            CopyInventoryRequests.Export input,
            ServerHttpResponse response) {
        return prepare(version, revision, page, Action.READ)
                .flatMap(
                        scope ->
                                Mono.usingWhen(
                                        Mono.fromSupplier(
                                                () -> {
                                                    if (!exports.tryAcquire())
                                                        throw error("EXPORT_BUSY", 429);
                                                    return true;
                                                }),
                                        permit ->
                                                locked(
                                                                scope,
                                                                Action.READ,
                                                                ignored ->
                                                                        requiredSnapshot(scope)
                                                                                .flatMap(
                                                                                        snapshot -> {
                                                                                            if (!snapshot.getConfigVersion()
                                                                                                    .equals(
                                                                                                            input
                                                                                                                    .getExpectedVersion()))
                                                                                                return Mono
                                                                                                        .error(
                                                                                                                error(
                                                                                                                        "CONFLICT",
                                                                                                                        409));
                                                                                            return scoped(
                                                                                                            scope)
                                                                                                    .orderBy(
                                                                                                            SortOrder
                                                                                                                    .asc(
                                                                                                                            PrototypeCopyEntryEntity
                                                                                                                                    ::getKind),
                                                                                                            SortOrder
                                                                                                                    .asc(
                                                                                                                            PrototypeCopyEntryEntity
                                                                                                                                    ::getSourceOrder),
                                                                                                            SortOrder
                                                                                                                    .asc(
                                                                                                                            PrototypeCopyEntryEntity
                                                                                                                                    ::getCreatedAt),
                                                                                                            SortOrder
                                                                                                                    .asc(
                                                                                                                            PrototypeCopyEntryEntity
                                                                                                                                    ::getId))
                                                                                                    .paging(
                                                                                                            0,
                                                                                                            2201)
                                                                                                    .fetch()
                                                                                                    .collectList()
                                                                                                    .flatMap(
                                                                                                            rows ->
                                                                                                                    rows
                                                                                                                                            .size()
                                                                                                                                    > 2200
                                                                                                                            ? Mono
                                                                                                                                    .error(
                                                                                                                                            error(
                                                                                                                                                    "EXPORT_LIMIT_EXCEEDED",
                                                                                                                                                    422))
                                                                                                                            : raw(
                                                                                                                                    scope,
                                                                                                                                    rows,
                                                                                                                                    rows
                                                                                                                                            .size()));
                                                                                        }))
                                                        .flatMap(
                                                                raw ->
                                                                        work.call(
                                                                                () ->
                                                                                        exporter
                                                                                                .bytes(
                                                                                                        new Report(
                                                                                                                1,
                                                                                                                summary(
                                                                                                                        scope
                                                                                                                                .source(),
                                                                                                                        raw),
                                                                                                                responses(
                                                                                                                        raw
                                                                                                                                .rows())),
                                                                                                        input
                                                                                                                .getFormat())))
                                                        .flatMap(
                                                                bytes ->
                                                                        authorize(
                                                                                        scope
                                                                                                .user(),
                                                                                        version,
                                                                                        Action.READ)
                                                                                .then(
                                                                                        Mono.defer(
                                                                                                () -> {
                                                                                                    var
                                                                                                            headers =
                                                                                                                    response
                                                                                                                            .getHeaders();
                                                                                                    headers
                                                                                                            .setContentType(
                                                                                                                    "csv"
                                                                                                                                    .equals(
                                                                                                                                            input
                                                                                                                                                    .getFormat())
                                                                                                                            ? MediaType
                                                                                                                                    .parseMediaType(
                                                                                                                                            "text/csv;charset=UTF-8")
                                                                                                                            : MediaType
                                                                                                                                    .APPLICATION_JSON);
                                                                                                    headers
                                                                                                            .setContentDisposition(
                                                                                                                    ContentDisposition
                                                                                                                            .attachment()
                                                                                                                            .filename(
                                                                                                                                    "copy-inventory-"
                                                                                                                                            + PrototypeCopyExtractor
                                                                                                                                                    .digest(
                                                                                                                                                            revision,
                                                                                                                                                            page)
                                                                                                                                                    .substring(
                                                                                                                                                            0,
                                                                                                                                                            24)
                                                                                                                                            + "."
                                                                                                                                            + input
                                                                                                                                                    .getFormat())
                                                                                                                            .build());
                                                                                                    headers
                                                                                                            .set(
                                                                                                                    HttpHeaders
                                                                                                                            .CACHE_CONTROL,
                                                                                                                    "private, no-store");
                                                                                                    headers
                                                                                                            .set(
                                                                                                                    "X-Content-Type-Options",
                                                                                                                    "nosniff");
                                                                                                    headers
                                                                                                            .set(
                                                                                                                    "Referrer-Policy",
                                                                                                                    "no-referrer");
                                                                                                    headers
                                                                                                            .setContentLength(
                                                                                                                    bytes.length);
                                                                                                    return response
                                                                                                            .writeWith(
                                                                                                                    Mono
                                                                                                                            .just(
                                                                                                                                    response.bufferFactory()
                                                                                                                                            .wrap(
                                                                                                                                                    bytes)));
                                                                                                }))),
                                        permit -> Mono.fromRunnable(exports::release),
                                        (permit, failure) -> Mono.fromRunnable(exports::release),
                                        permit -> Mono.fromRunnable(exports::release)))
                .checkpoint("prototype-hub/copy/export");
    }

    private Mono<Scope> prepare(String version, String revision, String page, Action action) {
        return access.current()
                .flatMap(
                        actor ->
                                authorize(actor.userId(), version, action)
                                        .flatMap(row -> revisions.requireManifest(row, revision))
                                        .flatMap(
                                                row ->
                                                        work.call(
                                                                () ->
                                                                        new Scope(
                                                                                actor.userId(),
                                                                                extractor.source(
                                                                                        row,
                                                                                        page)))));
    }

    private Mono<PrototypeVersionEntity> authorize(String user, String version, Action action) {
        return access.actor(user)
                .flatMap(
                        actor ->
                                actor.authentication()
                                                .hasPermission(
                                                        "prototype-hub-version",
                                                        action == Action.READ ? "query" : "save")
                                        ? access.requireVersion(actor, version, action, false)
                                        : Mono.error(PrototypeProjectAccessService.roleDenied()));
    }

    private <T> Mono<T> locked(Scope scope, Action action, Function<Scope, Mono<T>> operation) {
        var source = scope.source();
        return access.lockActiveUsers(scope.user())
                .then(access.lockProject(source.projectId()))
                .then(access.lockVersion(source.versionId()))
                .then(authorize(scope.user(), source.versionId(), action))
                .filter(version -> source.projectId().equals(version.getProjectId()))
                .switchIfEmpty(Mono.error(error("PAGE_INVALID", 404)))
                .flatMap(version -> revisions.require(version, source.revisionId()))
                .then(Mono.defer(() -> operation.apply(scope)))
                .as(transactions::transactional);
    }

    private Mono<PrototypeCopySnapshotEntity> snapshot(Scope scope) {
        var source = scope.source();
        return snapshots
                .createQuery()
                .where(PrototypeCopySnapshotEntity::getId, id(scope))
                .and(PrototypeCopySnapshotEntity::getProjectId, source.projectId())
                .and(PrototypeCopySnapshotEntity::getVersionId, source.versionId())
                .and(PrototypeCopySnapshotEntity::getRevisionId, source.revisionId())
                .and(PrototypeCopySnapshotEntity::getPageId, source.pageId())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeCopySnapshotEntity> requiredSnapshot(Scope scope) {
        return snapshot(scope).switchIfEmpty(Mono.error(error("NOT_EXTRACTED", 409)));
    }

    private ReactiveQuery<PrototypeCopyEntryEntity> scoped(Scope scope) {
        var source = scope.source();
        return entries.createQuery()
                .where(PrototypeCopyEntryEntity::getSnapshotId, id(scope))
                .and(PrototypeCopyEntryEntity::getProjectId, source.projectId())
                .and(PrototypeCopyEntryEntity::getVersionId, source.versionId())
                .and(PrototypeCopyEntryEntity::getRevisionId, source.revisionId())
                .and(PrototypeCopyEntryEntity::getPageId, source.pageId());
    }

    private Mono<PrototypeCopyEntryEntity> find(Scope scope, String entry) {
        return scoped(scope).and(PrototypeCopyEntryEntity::getId, entry).fetch().singleOrEmpty();
    }

    private Mono<PrototypeCopyEntryEntity> requireEntry(Scope scope, String entry) {
        return find(scope, entry).switchIfEmpty(Mono.error(error("ENTRY_NOT_FOUND", 404)));
    }

    private Mono<Raw> raw(Scope scope, List<PrototypeCopyEntryEntity> rows, int total) {
        return snapshot(scope)
                .defaultIfEmpty(new PrototypeCopySnapshotEntity())
                .flatMap(
                        snapshot ->
                                scoped(scope)
                                        .and(PrototypeCopyEntryEntity::getKind, 1)
                                        .count()
                                        .map(count -> new Raw(snapshot, count, rows, total)));
    }

    private static String id(Scope scope) {
        var s = scope.source();
        return PrototypeCopyExtractor.digest(
                s.projectId(), s.versionId(), s.revisionId(), s.pageId());
    }

    private CopyInventoryResponse summary(Source source, Raw raw) {
        var row = raw.snapshot();
        boolean exists = row.getId() != null;
        String state = exists ? row.getExtractionState() : "NOT_EXTRACTED";
        return new CopyInventoryResponse(
                source.projectId(),
                source.versionId(),
                source.revisionId(),
                source.pageId(),
                source.pagePath(),
                source.manifestSha256(),
                source.file().sha256(),
                row.getId(),
                row.getExtractorVersion(),
                state,
                row.getLimitReason(),
                state.equals("READY") || state.equals("EMPTY"),
                true,
                exists ? row.getStaticCount() : 0,
                raw.manualCount(),
                row.getConfigVersion(),
                row.getExtractedAt());
    }

    private List<Entry> responses(List<PrototypeCopyEntryEntity> rows) throws Exception {
        var result = new ArrayList<Entry>();
        for (var row : rows) result.add(response(row));
        return List.copyOf(result);
    }

    private Entry response(PrototypeCopyEntryEntity row) throws Exception {
        return new Entry(
                row.getId(),
                row.getKind() == 0 ? "STATIC" : "MANUAL",
                row.getText(),
                row.getBusinessTerm(),
                row.getNote(),
                row.getRequirementUrl(),
                row.getOccurrenceCount(),
                mapper.readValue(row.getOccurrencesJson(), OCCURRENCES),
                row.getConfigVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private PrototypeCopyEntryEntity entry(Scope scope, String id, int kind, long now) {
        var s = scope.source();
        var row = new PrototypeCopyEntryEntity();
        row.setId(id);
        row.setSnapshotId(id(scope));
        row.setProjectId(s.projectId());
        row.setVersionId(s.versionId());
        row.setRevisionId(s.revisionId());
        row.setPageId(s.pageId());
        row.setKind(kind);
        row.setSourceOrder(0);
        row.setBusinessTerm("");
        row.setNote("");
        row.setRequirementUrl("");
        row.setOccurrenceCount(0);
        row.setOccurrencesJson("[]");
        row.setConfigVersion(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private Mono<Long> bump(Scope scope, PrototypeCopySnapshotEntity row) {
        if (row.getConfigVersion() == Long.MAX_VALUE) return Mono.error(error("CONFLICT", 409));
        long next = row.getConfigVersion() + 1;
        return snapshots
                .createUpdate()
                .set(PrototypeCopySnapshotEntity::getConfigVersion, next)
                .where(PrototypeCopySnapshotEntity::getId, id(scope))
                .and(PrototypeCopySnapshotEntity::getConfigVersion, row.getConfigVersion())
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? Mono.just(next)
                                        : Mono.error(error("CONFLICT", 409)));
    }

    private Request request(
            Scope scope,
            String operation,
            String key,
            String entry,
            Long version,
            String... fields) {
        if (key == null) throw error("INPUT_INVALID", 400);
        var s = scope.source();
        var values = new ArrayList<String>();
        values.addAll(
                java.util.Arrays.asList(
                        s.projectId(),
                        s.versionId(),
                        s.revisionId(),
                        s.pageId(),
                        entry,
                        version == null ? null : version.toString()));
        values.addAll(java.util.Arrays.asList(fields));
        return receipts.request(
                scope.user(), "copy-" + operation, id(scope), key, values.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.source().versionId(), scope.source().revisionId());
    }

    private static void checkVersion(PrototypeCopyEntryEntity row, Long expected) {
        if (expected == null
                || row.getConfigVersion() == Long.MAX_VALUE
                || !row.getConfigVersion().equals(expected)) throw error("CONFLICT", 409);
    }

    private static String manual(String text) {
        text = plain(text, 2000);
        if (text.isBlank()) throw error("INPUT_INVALID", 400);
        return text;
    }

    private static String url(String text) {
        text = plain(text, 2048);
        return text.isEmpty() ? text : PrototypeWorkItemService.validatedUrl(text);
    }

    static String plain(String text, int limit) {
        if (text == null) return "";
        if (text.length() > limit || text.indexOf('\0') >= 0) throw error("INPUT_INVALID", 400);
        for (int i = 0; i < text.length(); i++) {
            char code = text.charAt(i);
            if (Character.isHighSurrogate(code)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw error("INPUT_INVALID", 400);
            } else if (Character.isLowSurrogate(code)) throw error("INPUT_INVALID", 400);
        }
        return text;
    }
}
