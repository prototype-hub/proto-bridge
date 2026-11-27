package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypePageRelationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypePageTraceContextEntity;
import com.gaiys.proto.prototypehub.entity.PrototypePageTraceDecisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.PageTraceSourceRef;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.PageTraceRequests;
import com.gaiys.proto.prototypehub.web.response.PageTraceResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 人工页面关系、来源指针和决策的唯一持久化边界，不推断HTML跳转或代行F06正式批准。 所有写入沿账号→项目→版本锁序重验身份，CAS与持久回执同事务；manifest只在锁外读取。
 * 复用HTTP/SQL追踪及稳定checkpoint；没有新常驻状态、文件、队列或MBean。
 */
@Service
@RequiredArgsConstructor
public class PrototypePageTraceService {
    private static final long MAX_VERSION = 9007199254740991L;
    private final ReactiveRepository<PrototypePageRelationEntity, String> relations;
    private final ReactiveRepository<PrototypePageTraceContextEntity, String> contexts;
    private final ReactiveRepository<PrototypePageTraceDecisionEntity, String> decisions;
    private final PrototypePageTraceSourceService sources;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;

    public Mono<PagerResult<PageTraceResponse.Relation>> queryRelations(
            String version, String revision, PageTraceRequests.RelationQuery input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope -> {
                            if (input.getPageId() != null) requirePage(scope, input.getPageId());
                            return QueryHelper.queryPager(
                                    relationQuery(scope.fixed(), input),
                                    relations::createQuery,
                                    PageTraceResponse.Relation::of);
                        })
                .checkpoint("prototype-hub/page-trace/relations-query");
    }

    public Mono<PageTraceResponse.Relation> relation(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope ->
                                requireRelation(scope.fixed(), id)
                                        .map(PageTraceResponse.Relation::of));
    }

    public Mono<PageTraceResponse.Relation> createRelation(
            String version, String revision, PageTraceRequests.RelationFields input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope -> {
                            validateRelation(scope, input);
                            return locked(scope, () -> createRelation(scope, input));
                        })
                .checkpoint("prototype-hub/page-trace/relation-create");
    }

    private Mono<PageTraceResponse.Relation> createRelation(
            Scope scope, PageTraceRequests.RelationFields input) {
        var request = relationRequest(scope, "create", input, null, null);
        return receipts.replay(
                        request,
                        ignored ->
                                findRelation(scope.fixed(), request.id())
                                        .map(PageTraceResponse.Relation::of))
                .switchIfEmpty(Mono.defer(() -> insertRelation(scope, input, request)));
    }

    private Mono<PageTraceResponse.Relation> insertRelation(
            Scope scope, PageTraceRequests.RelationFields input, Request request) {
        var row = new PrototypePageRelationEntity();
        var fixed = scope.fixed();
        long now = System.currentTimeMillis();
        row.setId(request.id());
        row.setProjectId(fixed.getProjectId());
        row.setVersionId(fixed.getVersionId());
        row.setRevisionId(fixed.getId());
        relationFields(row, input);
        row.setConfigVersion(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return relations
                .insert(row)
                .then(record(scope, request))
                .thenReturn(PageTraceResponse.Relation.of(row));
    }

    public Mono<PageTraceResponse.Relation> updateRelation(
            String version, String revision, String id, PageTraceRequests.RelationUpdate input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope -> {
                            validateRelation(scope, input);
                            return locked(scope, () -> updateRelation(scope, id, input));
                        })
                .checkpoint("prototype-hub/page-trace/relation-update");
    }

    private Mono<PageTraceResponse.Relation> updateRelation(
            Scope scope, String id, PageTraceRequests.RelationUpdate input) {
        var request = relationRequest(scope, "update", input, id, input.getExpectedVersion());
        return receipts.replay(
                        request,
                        ignored ->
                                findRelation(scope.fixed(), id).map(PageTraceResponse.Relation::of))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        requireRelation(scope.fixed(), id)
                                                .flatMap(
                                                        row ->
                                                                saveRelation(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<PageTraceResponse.Relation> saveRelation(
            Scope scope,
            PrototypePageRelationEntity row,
            PageTraceRequests.RelationUpdate input,
            Request request) {
        checkVersion(row.getConfigVersion(), input.getExpectedVersion());
        relationFields(row, input);
        row.setConfigVersion(nextVersion(row.getConfigVersion()));
        row.setUpdatedAt(System.currentTimeMillis());
        return relations
                .createUpdate()
                .set(PrototypePageRelationEntity::getFromPageId, row.getFromPageId())
                .set(PrototypePageRelationEntity::getToPageId, row.getToPageId())
                .set(PrototypePageRelationEntity::getLabel, row.getLabel())
                .set(PrototypePageRelationEntity::getNote, row.getNote())
                .set(PrototypePageRelationEntity::getConfigVersion, row.getConfigVersion())
                .set(PrototypePageRelationEntity::getUpdatedAt, row.getUpdatedAt())
                .where(PrototypePageRelationEntity::getId, row.getId())
                .and(PrototypePageRelationEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(PrototypePageTraceService::requireChanged)
                .then(record(scope, request))
                .thenReturn(PageTraceResponse.Relation.of(row));
    }

    public Mono<Boolean> deleteRelation(
            String version, String revision, String id, PageTraceRequests.Delete input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> deleteRelation(scope, id, input)))
                .checkpoint("prototype-hub/page-trace/relation-delete");
    }

    private Mono<Boolean> deleteRelation(Scope scope, String id, PageTraceRequests.Delete input) {
        var request =
                request(
                        scope,
                        "relation-delete",
                        input.getClientRequestId(),
                        id,
                        Long.toString(input.getExpectedVersion()));
        return receipts.replay(request, ignored -> Mono.just(true))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        requireRelation(scope.fixed(), id)
                                                .flatMap(
                                                        row ->
                                                                removeRelation(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<Boolean> removeRelation(
            Scope scope,
            PrototypePageRelationEntity row,
            PageTraceRequests.Delete input,
            Request request) {
        checkVersion(row.getConfigVersion(), input.getExpectedVersion());
        return relations
                .createDelete()
                .where(PrototypePageRelationEntity::getId, row.getId())
                .and(PrototypePageRelationEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(PrototypePageTraceService::requireChanged)
                .then(record(scope, request))
                .thenReturn(true);
    }

    private QueryParamEntity relationQuery(
            PrototypeRevisionEntity fixed, PageTraceRequests.RelationQuery input) {
        var query =
                QueryParamEntity.newQuery()
                        .where(PrototypePageRelationEntity::getProjectId, fixed.getProjectId())
                        .and(PrototypePageRelationEntity::getVersionId, fixed.getVersionId())
                        .and(PrototypePageRelationEntity::getRevisionId, fixed.getId());
        if (input.getPageId() != null) {
            if ("incoming".equals(input.getDirection()))
                query.and(PrototypePageRelationEntity::getToPageId, input.getPageId());
            else if ("outgoing".equals(input.getDirection()))
                query.and(PrototypePageRelationEntity::getFromPageId, input.getPageId());
            else
                query.nest()
                        .is(PrototypePageRelationEntity::getFromPageId, input.getPageId())
                        .or()
                        .is(PrototypePageRelationEntity::getToPageId, input.getPageId())
                        .end();
        }
        if (input.getKeyword() != null && !input.getKeyword().isEmpty())
            query.accept(
                    PrototypePageRelationEntity::getLabel,
                    PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                    input.getKeyword());
        // 同一范围条件进入COUNT/LIMIT，图仅由当前页构建，永不拉取整个修订关系图。
        var param =
                query.orderByDesc(PrototypePageRelationEntity::getCreatedAt)
                        .orderByAsc(PrototypePageRelationEntity::getId)
                        .getParam();
        return paging(param, input);
    }

    private Mono<PrototypePageRelationEntity> findRelation(
            PrototypeRevisionEntity fixed, String id) {
        return relations
                .createQuery()
                .where(PrototypePageRelationEntity::getId, id)
                .and(PrototypePageRelationEntity::getProjectId, fixed.getProjectId())
                .and(PrototypePageRelationEntity::getVersionId, fixed.getVersionId())
                .and(PrototypePageRelationEntity::getRevisionId, fixed.getId())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypePageRelationEntity> requireRelation(
            PrototypeRevisionEntity fixed, String id) {
        return findRelation(fixed, id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("page_relation")));
    }

    private static void relationFields(
            PrototypePageRelationEntity row, PageTraceRequests.RelationFields input) {
        row.setFromPageId(input.getFromPageId());
        row.setToPageId(input.getToPageId());
        row.setLabel(input.getLabel());
        row.setNote(plain(input.getNote()));
    }

    private Request relationRequest(
            Scope scope,
            String action,
            PageTraceRequests.RelationFields input,
            String id,
            Long expected) {
        return request(
                scope,
                "relation-" + action,
                input.getClientRequestId(),
                id,
                expected == null ? null : expected.toString(),
                input.getFromPageId(),
                input.getToPageId(),
                input.getLabel(),
                plain(input.getNote()));
    }

    private static void validateRelation(Scope scope, PageTraceRequests.RelationFields input) {
        requirePage(scope, input.getFromPageId());
        requirePage(scope, input.getToPageId());
        if (input.getFromPageId().equals(input.getToPageId())) throw invalid();
    }

    public Mono<PageTraceResponse.Context> context(String version, String revision, String page) {
        return preparePage(version, revision, page, Action.READ)
                .flatMap(scope -> contextView(scope, page))
                .checkpoint("prototype-hub/page-trace/context");
    }

    private Mono<PageTraceResponse.Context> contextView(Scope scope, String page) {
        return findContext(scope.fixed(), page)
                .flatMap(row -> contextView(scope, row))
                .defaultIfEmpty(PageTraceResponse.Context.empty(scope.fixed(), page));
    }

    private Mono<PageTraceResponse.Context> contextView(
            Scope scope, PrototypePageTraceContextEntity row) {
        return sources.load(scope.fixed(), row.getPageId(), row.getReferences())
                .map(
                        live ->
                                PageTraceResponse.Context.of(
                                        row,
                                        PrototypePageTraceSourceService.resolve(
                                                row.getReferences(), live)));
    }

    public Mono<PageTraceResponse.Context> updateContext(
            String version, String revision, String page, PageTraceRequests.ContextUpdate input) {
        validateReferences(input.getReferences());
        return preparePage(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> updateContext(scope, page, input)))
                .checkpoint("prototype-hub/page-trace/context-update");
    }

    private Mono<PageTraceResponse.Context> updateContext(
            Scope scope, String page, PageTraceRequests.ContextUpdate input) {
        var fields =
                new ArrayList<String>(
                        List.of(
                                page,
                                input.getExpectedVersion().toString(),
                                input.getEntry().toString()));
        for (var ref : input.getReferences())
            fields.addAll(List.of(ref.kind(), ref.id(), Long.toString(ref.expectedVersion())));
        var request =
                request(
                        scope,
                        "context-update",
                        input.getClientRequestId(),
                        fields.toArray(String[]::new));
        // 重放先于附加来源重验：来源后来变更/删除不会让已成功请求失败，也不复活旧指针。
        return receipts.replay(request, ignored -> contextView(scope, page))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        findContext(scope.fixed(), page)
                                                .defaultIfEmpty(emptyContext(scope.fixed(), page))
                                                .flatMap(
                                                        row ->
                                                                saveContext(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<PageTraceResponse.Context> saveContext(
            Scope scope,
            PrototypePageTraceContextEntity row,
            PageTraceRequests.ContextUpdate input,
            Request request) {
        checkVersion(row.getConfigVersion(), input.getExpectedVersion());
        return sources.load(scope.fixed(), row.getPageId(), input.getReferences())
                .flatMap(
                        live -> {
                            PrototypePageTraceSourceService.validateAttach(
                                    row.getReferences(), input.getReferences(), live);
                            boolean create = row.getId() == null;
                            long now = System.currentTimeMillis();
                            row.setEntry(input.getEntry());
                            row.setReferences(List.copyOf(input.getReferences()));
                            row.setConfigVersion(nextVersion(row.getConfigVersion()));
                            row.setUpdatedAt(now);
                            Mono<Void> mutation;
                            if (create) {
                                row.setId(request.id());
                                row.setCreatedAt(now);
                                mutation = contexts.insert(row).then();
                            } else
                                mutation =
                                        contexts.createUpdate()
                                                .set(
                                                        PrototypePageTraceContextEntity::getEntry,
                                                        row.getEntry())
                                                .set(
                                                        PrototypePageTraceContextEntity
                                                                ::getReferences,
                                                        row.getReferences())
                                                .set(
                                                        PrototypePageTraceContextEntity
                                                                ::getConfigVersion,
                                                        row.getConfigVersion())
                                                .set(
                                                        PrototypePageTraceContextEntity
                                                                ::getUpdatedAt,
                                                        now)
                                                .where(
                                                        PrototypePageTraceContextEntity::getId,
                                                        row.getId())
                                                .and(
                                                        PrototypePageTraceContextEntity
                                                                ::getConfigVersion,
                                                        input.getExpectedVersion())
                                                .execute()
                                                .flatMap(PrototypePageTraceService::requireChanged);
                            // 清空仍保留CAS行；旧创建请求重放读取当前空状态，不能重建已移除引用或入口。
                            return mutation.then(record(scope, request))
                                    .thenReturn(
                                            PageTraceResponse.Context.of(
                                                    row,
                                                    PrototypePageTraceSourceService.resolve(
                                                            row.getReferences(), live)));
                        });
    }

    public Mono<PagerResult<PageTraceResponse.Entry>> entries(
            String version, String revision, PageTraceRequests.Page input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope -> {
                            var fixed = scope.fixed();
                            var query =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypePageTraceContextEntity::getProjectId,
                                                    fixed.getProjectId())
                                            .and(
                                                    PrototypePageTraceContextEntity::getVersionId,
                                                    fixed.getVersionId())
                                            .and(
                                                    PrototypePageTraceContextEntity::getRevisionId,
                                                    fixed.getId())
                                            .and(PrototypePageTraceContextEntity::getEntry, true)
                                            .orderByDesc(
                                                    PrototypePageTraceContextEntity::getUpdatedAt)
                                            .orderByAsc(PrototypePageTraceContextEntity::getId)
                                            .getParam();
                            return QueryHelper.queryPager(
                                    paging(query, input),
                                    () ->
                                            contexts.createQuery()
                                                    .select(
                                                            PrototypePageTraceContextEntity
                                                                    ::getPageId,
                                                            PrototypePageTraceContextEntity
                                                                    ::getConfigVersion,
                                                            PrototypePageTraceContextEntity
                                                                    ::getUpdatedAt),
                                    PageTraceResponse.Entry::of);
                        });
    }

    public Mono<PagerResult<PageTraceResponse.SourceCandidate>> sourceCandidates(
            String version, String revision, String page, PageTraceRequests.SourceQuery input) {
        return preparePage(version, revision, page, Action.READ)
                .flatMap(scope -> sources.query(scope.fixed(), page, input));
    }

    public Mono<PageTraceResponse.SourceDetail> sourceDetail(
            String version, String revision, String page, String kind, String id) {
        return preparePage(version, revision, page, Action.READ)
                .flatMap(scope -> sources.detail(scope.fixed(), page, kind, id));
    }

    private Mono<PrototypePageTraceContextEntity> findContext(
            PrototypeRevisionEntity fixed, String page) {
        return contexts.createQuery()
                .where(PrototypePageTraceContextEntity::getProjectId, fixed.getProjectId())
                .and(PrototypePageTraceContextEntity::getVersionId, fixed.getVersionId())
                .and(PrototypePageTraceContextEntity::getRevisionId, fixed.getId())
                .and(PrototypePageTraceContextEntity::getPageId, page)
                .fetch()
                .singleOrEmpty();
    }

    private static PrototypePageTraceContextEntity emptyContext(
            PrototypeRevisionEntity fixed, String page) {
        var row = new PrototypePageTraceContextEntity();
        row.setProjectId(fixed.getProjectId());
        row.setVersionId(fixed.getVersionId());
        row.setRevisionId(fixed.getId());
        row.setPageId(page);
        row.setEntry(false);
        row.setReferences(List.of());
        row.setConfigVersion(-1L);
        return row;
    }

    static void validateReferences(List<PageTraceSourceRef> refs) {
        var keys = new HashSet<String>();
        for (var ref : refs) if (!keys.add(ref.kind() + ":" + ref.id())) throw invalid();
    }

    public Mono<PagerResult<PageTraceResponse.Decision>> queryDecisions(
            String version, String revision, String page, PageTraceRequests.Page input) {
        return preparePage(version, revision, page, Action.READ)
                .flatMap(
                        scope -> {
                            var fixed = scope.fixed();
                            var query =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypePageTraceDecisionEntity::getProjectId,
                                                    fixed.getProjectId())
                                            .and(
                                                    PrototypePageTraceDecisionEntity::getVersionId,
                                                    fixed.getVersionId())
                                            .and(
                                                    PrototypePageTraceDecisionEntity::getRevisionId,
                                                    fixed.getId())
                                            .and(PrototypePageTraceDecisionEntity::getPageId, page)
                                            .orderByDesc(
                                                    PrototypePageTraceDecisionEntity::getCreatedAt)
                                            .orderByAsc(PrototypePageTraceDecisionEntity::getId)
                                            .getParam();
                            return QueryHelper.queryPager(
                                    paging(query, input),
                                    decisions::createQuery,
                                    PageTraceResponse.Decision::of);
                        });
    }

    public Mono<PageTraceResponse.Decision> decision(
            String version, String revision, String page, String id) {
        return preparePage(version, revision, page, Action.READ)
                .flatMap(
                        scope ->
                                requireDecision(scope.fixed(), page, id)
                                        .map(PageTraceResponse.Decision::of));
    }

    public Mono<PageTraceResponse.Decision> createDecision(
            String version, String revision, String page, PageTraceRequests.DecisionFields input) {
        return preparePage(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> createDecision(scope, page, input)))
                .checkpoint("prototype-hub/page-trace/decision-create");
    }

    private Mono<PageTraceResponse.Decision> createDecision(
            Scope scope, String page, PageTraceRequests.DecisionFields input) {
        var request = decisionRequest(scope, page, "create", input, null, null);
        return receipts.replay(
                        request,
                        ignored ->
                                findDecision(scope.fixed(), page, request.id())
                                        .map(PageTraceResponse.Decision::of))
                .switchIfEmpty(Mono.defer(() -> insertDecision(scope, page, input, request)));
    }

    private Mono<PageTraceResponse.Decision> insertDecision(
            Scope scope, String page, PageTraceRequests.DecisionFields input, Request request) {
        var row = new PrototypePageTraceDecisionEntity();
        var fixed = scope.fixed();
        long now = System.currentTimeMillis();
        row.setId(request.id());
        row.setProjectId(fixed.getProjectId());
        row.setVersionId(fixed.getVersionId());
        row.setRevisionId(fixed.getId());
        row.setPageId(page);
        decisionFields(row, input);
        row.setAuthorId(scope.user());
        row.setUpdatedBy(scope.user());
        row.setConfigVersion(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return decisions
                .insert(row)
                .then(record(scope, request))
                .thenReturn(PageTraceResponse.Decision.of(row));
    }

    public Mono<PageTraceResponse.Decision> updateDecision(
            String version,
            String revision,
            String page,
            String id,
            PageTraceRequests.DecisionUpdate input) {
        return preparePage(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> updateDecision(scope, page, id, input)))
                .checkpoint("prototype-hub/page-trace/decision-update");
    }

    private Mono<PageTraceResponse.Decision> updateDecision(
            Scope scope, String page, String id, PageTraceRequests.DecisionUpdate input) {
        var request = decisionRequest(scope, page, "update", input, id, input.getExpectedVersion());
        return receipts.replay(
                        request,
                        ignored ->
                                findDecision(scope.fixed(), page, id)
                                        .map(PageTraceResponse.Decision::of))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        requireDecision(scope.fixed(), page, id)
                                                .flatMap(
                                                        row ->
                                                                saveDecision(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<PageTraceResponse.Decision> saveDecision(
            Scope scope,
            PrototypePageTraceDecisionEntity row,
            PageTraceRequests.DecisionUpdate input,
            Request request) {
        checkVersion(row.getConfigVersion(), input.getExpectedVersion());
        decisionFields(row, input);
        row.setUpdatedBy(scope.user());
        row.setConfigVersion(nextVersion(row.getConfigVersion()));
        row.setUpdatedAt(System.currentTimeMillis());
        return decisions
                .createUpdate()
                .set(PrototypePageTraceDecisionEntity::getTitle, row.getTitle())
                .set(PrototypePageTraceDecisionEntity::getDecision, row.getDecision())
                .set(PrototypePageTraceDecisionEntity::getRationale, row.getRationale())
                .set(PrototypePageTraceDecisionEntity::getUpdatedBy, row.getUpdatedBy())
                .set(PrototypePageTraceDecisionEntity::getConfigVersion, row.getConfigVersion())
                .set(PrototypePageTraceDecisionEntity::getUpdatedAt, row.getUpdatedAt())
                .where(PrototypePageTraceDecisionEntity::getId, row.getId())
                .and(PrototypePageTraceDecisionEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(PrototypePageTraceService::requireChanged)
                .then(record(scope, request))
                .thenReturn(PageTraceResponse.Decision.of(row));
    }

    public Mono<Boolean> deleteDecision(
            String version,
            String revision,
            String page,
            String id,
            PageTraceRequests.Delete input) {
        return preparePage(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> deleteDecision(scope, page, id, input)))
                .checkpoint("prototype-hub/page-trace/decision-delete");
    }

    private Mono<Boolean> deleteDecision(
            Scope scope, String page, String id, PageTraceRequests.Delete input) {
        var request =
                request(
                        scope,
                        "decision-delete",
                        input.getClientRequestId(),
                        page,
                        id,
                        input.getExpectedVersion().toString());
        return receipts.replay(request, ignored -> Mono.just(true))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        requireDecision(scope.fixed(), page, id)
                                                .flatMap(
                                                        row ->
                                                                removeDecision(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<Boolean> removeDecision(
            Scope scope,
            PrototypePageTraceDecisionEntity row,
            PageTraceRequests.Delete input,
            Request request) {
        checkVersion(row.getConfigVersion(), input.getExpectedVersion());
        return decisions
                .createDelete()
                .where(PrototypePageTraceDecisionEntity::getId, row.getId())
                .and(PrototypePageTraceDecisionEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(PrototypePageTraceService::requireChanged)
                .then(record(scope, request))
                .thenReturn(true);
    }

    private Mono<PrototypePageTraceDecisionEntity> findDecision(
            PrototypeRevisionEntity fixed, String page, String id) {
        return decisions
                .createQuery()
                .where(PrototypePageTraceDecisionEntity::getId, id)
                .and(PrototypePageTraceDecisionEntity::getProjectId, fixed.getProjectId())
                .and(PrototypePageTraceDecisionEntity::getVersionId, fixed.getVersionId())
                .and(PrototypePageTraceDecisionEntity::getRevisionId, fixed.getId())
                .and(PrototypePageTraceDecisionEntity::getPageId, page)
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypePageTraceDecisionEntity> requireDecision(
            PrototypeRevisionEntity fixed, String page, String id) {
        return findDecision(fixed, page, id)
                .switchIfEmpty(
                        Mono.error(() -> PrototypeHubException.missing("page_trace_decision")));
    }

    private static void decisionFields(
            PrototypePageTraceDecisionEntity row, PageTraceRequests.DecisionFields input) {
        row.setTitle(input.getTitle());
        row.setDecision(input.getDecision());
        row.setRationale(input.getRationale());
    }

    private Request decisionRequest(
            Scope scope,
            String page,
            String action,
            PageTraceRequests.DecisionFields input,
            String id,
            Long expected) {
        return request(
                scope,
                "decision-" + action,
                input.getClientRequestId(),
                page,
                id,
                expected == null ? null : expected.toString(),
                input.getTitle(),
                input.getDecision(),
                input.getRationale());
    }

    private Mono<Scope> prepare(String version, String revision, Action action) {
        return access.current()
                .flatMap(actor -> authorize(actor, version, revision, action, true))
                .flatMap(
                        scope ->
                                revisions
                                        .pages(scope.fixed())
                                        .map(
                                                pages ->
                                                        new Scope(
                                                                scope.user(),
                                                                scope.fixed(),
                                                                realPages(pages))));
    }

    private Mono<Scope> preparePage(String version, String revision, String page, Action action) {
        return prepare(version, revision, action)
                .map(
                        scope -> {
                            requirePage(scope, page);
                            return scope;
                        });
    }

    private Mono<Scope> authorize(
            Actor actor, String version, String revision, Action action, boolean audit) {
        if (!actor.authentication()
                .hasPermission("prototype-hub-version", action == Action.READ ? "query" : "save"))
            return Mono.error(PrototypeProjectAccessService.roleDenied());
        return access.requireVersion(actor, version, action, audit)
                .flatMap(row -> revisions.require(row, revision))
                .map(fixed -> new Scope(actor.userId(), fixed, Set.of()));
    }

    private <T> Mono<T> locked(Scope scope, Supplier<Mono<T>> work) {
        return access.lockActiveUsers(scope.user())
                .then(access.lockProject(scope.fixed().getProjectId()))
                .then(access.lockVersion(scope.fixed().getVersionId()))
                .then(access.actor(scope.user()))
                .flatMap(
                        actor ->
                                authorize(
                                        actor,
                                        scope.fixed().getVersionId(),
                                        scope.fixed().getId(),
                                        Action.EDIT,
                                        false))
                .filter(
                        current ->
                                scope.fixed().getProjectId().equals(current.fixed().getProjectId()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")))
                .then(Mono.defer(work))
                .as(transactions::transactional);
    }

    static Set<String> realPages(List<PrototypePageInfo> pages) {
        var ids = new HashSet<String>();
        addPages(pages, ids);
        return Set.copyOf(ids);
    }

    private static void addPages(List<PrototypePageInfo> pages, Set<String> ids) {
        for (var page : pages) {
            if (!page.folder()) ids.add(page.pageId());
            addPages(page.children(), ids);
        }
    }

    private static void requirePage(Scope scope, String page) {
        if (!scope.pages().contains(page)) throw PrototypeHubException.missing("page");
    }

    private Request request(Scope scope, String operation, String key, String... fields) {
        var fixed = scope.fixed();
        var values =
                new ArrayList<String>(
                        List.of(fixed.getProjectId(), fixed.getVersionId(), fixed.getId()));
        values.addAll(Arrays.asList(fields));
        return receipts.request(
                scope.user(),
                "page-trace-" + operation,
                fixed.getId(),
                key,
                values.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.fixed().getVersionId(), scope.fixed().getId());
    }

    private static QueryParamEntity paging(QueryParamEntity query, PageTraceRequests.Page input) {
        query.doPaging(input.getPageIndex(), input.getPageSize());
        query.setParallelPager(true);
        return query;
    }

    private static Mono<Void> requireChanged(int changed) {
        return changed == 1 ? Mono.empty() : Mono.error(conflict());
    }

    private static void checkVersion(long current, long expected) {
        if (current != expected) throw conflict();
    }

    private static long nextVersion(long current) {
        if (current >= MAX_VERSION) throw conflict();
        return current + 1;
    }

    private static String plain(String value) {
        return Objects.toString(value, "");
    }

    static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.page_trace_invalid", "PROTOTYPE_HUB_PAGE_TRACE_INVALID");
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.page_trace_conflict",
                "PROTOTYPE_HUB_PAGE_TRACE_CONFLICT",
                409);
    }

    private record Scope(String user, PrototypeRevisionEntity fixed, Set<String> pages) {}
}
