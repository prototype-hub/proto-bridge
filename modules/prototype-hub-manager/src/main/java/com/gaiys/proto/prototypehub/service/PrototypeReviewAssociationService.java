package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewAssociationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.service.keys.PrototypeShareKeys;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationContextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationRevokeRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewAssociationContextResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewAssociationResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewGuestResponse;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 人工核实授权的唯一写入边界；原身份不合并，代次失效后仍需显式撤销。写入与评审持相同项目→版本锁，审计随资源永久清理。 */
@Service
@RequiredArgsConstructor
public class PrototypeReviewAssociationService {
    private final PrototypeProjectAccessService access;
    private final PrototypeShareService shares;
    private final ReactiveRepository<PrototypeReviewThreadEntity, String> threads;
    private final ReactiveRepository<PrototypeReviewAssociationEntity, String> associations;
    private final ReactiveRepository<PrototypeShareEntity, String> shareRows;
    private final ReactiveRepository<PrototypeReviewGuestEntity, String> guests;
    private final ReactiveRepository<PrototypeCreationReceiptEntity, String> receiptRows;
    private final PrototypeCreationReceiptService receipts;
    private final QueryHelper queries;
    private final TransactionalOperator transactions;

    public Mono<ReviewAssociationContextResponse> context(
            String project, ReviewAssociationContextRequest input) {
        return source(project, input.getSourceThreadId())
                .flatMap(
                        source -> {
                            var share =
                                    shareRows
                                            .findById(source.getShareId())
                                            .map(Optional::of)
                                            .defaultIfEmpty(Optional.empty());
                            var active =
                                    associations
                                            .createQuery()
                                            .where(
                                                    PrototypeReviewAssociationEntity
                                                            ::getActiveScopeKey,
                                                    scopeKey(source))
                                            .fetch()
                                            .singleOrEmpty()
                                            .flatMap(row -> response(project, row.getId()))
                                            .map(Optional::of)
                                            .defaultIfEmpty(Optional.empty());
                            return Mono.zip(share, active)
                                    .flatMap(
                                            values -> {
                                                var current = values.getT1().orElse(null);
                                                return scopeAvailable(source, current)
                                                        .map(
                                                                eligible ->
                                                                        new ReviewAssociationContextResponse(
                                                                                PrototypeReviewThreadService
                                                                                        .response(
                                                                                                source),
                                                                                source
                                                                                        .getVersionId(),
                                                                                source.getShareId(),
                                                                                source
                                                                                        .getRevisionId(),
                                                                                current == null
                                                                                        ? null
                                                                                        : version(
                                                                                                current
                                                                                                        .getConfigVersion()),
                                                                                eligible,
                                                                                eligible
                                                                                        ? null
                                                                                        : "SHARE_UNAVAILABLE",
                                                                                values.getT2()
                                                                                        .orElse(
                                                                                                null)));
                                            });
                        });
    }

    public Mono<PagerResult<ReviewAssociationResponse>> query(
            String project, ReviewAssociationQueryRequest input) {
        return source(project, input.getSourceThreadId())
                .flatMap(
                        source -> {
                            var page = new QueryParamEntity();
                            page.doPaging(input.getPageIndex(), input.getPageSize());
                            page.setParallelPager(true);
                            var query = snapshots();
                            query.where(
                                    c ->
                                            c.is(
                                                            PrototypeReviewAssociationEntity
                                                                    ::getProjectId,
                                                            project)
                                                    .and(
                                                            PrototypeReviewAssociationEntity
                                                                    ::getVersionId,
                                                            source.getVersionId())
                                                    .and(
                                                            PrototypeReviewAssociationEntity
                                                                    ::getShareId,
                                                            source.getShareId())
                                                    .and(
                                                            PrototypeReviewAssociationEntity
                                                                    ::getRevisionId,
                                                            source.getRevisionId())
                                                    .and(
                                                            PrototypeReviewAssociationEntity
                                                                    ::getSourceVerificationLevel,
                                                            "unverified")
                                                    .and(
                                                            PrototypeReviewAssociationEntity
                                                                    ::getSourceGuestId,
                                                            source.getGuestId()));
                            query.where(page);
                            return query.orderByDesc(
                                            PrototypeReviewAssociationEntity::getCreateTime)
                                    .orderByAsc(PrototypeReviewAssociationEntity::getId)
                                    .fetchPaged(
                                            rows ->
                                                    Mono.just(
                                                            rows.stream()
                                                                    .map(
                                                                            PrototypeReviewAssociationService
                                                                                    ::response)
                                                                    .toList()));
                        });
    }

    public Mono<ReviewAssociationResponse> create(
            String project, ReviewAssociationCreateRequest input) {
        return source(project, input.getSourceThreadId())
                .flatMap(
                        before ->
                                access.lockProject(project)
                                        .then(access.lockVersion(before.getVersionId()))
                                        .then(source(project, input.getSourceThreadId()))
                                        .filter(current -> sameSource(before, current))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        PrototypeReviewAssociationService
                                                                ::conflict))
                                        .flatMap(
                                                current ->
                                                        access.current()
                                                                .flatMap(
                                                                        actor ->
                                                                                create(
                                                                                        current,
                                                                                        actor,
                                                                                        input)))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/review/association/create");
    }

    private Mono<ReviewAssociationResponse> create(
            PrototypeReviewThreadEntity source,
            PrototypeProjectAccessService.Actor actor,
            ReviewAssociationCreateRequest input) {
        var request =
                receipts.request(
                        "member:null:" + actor.userId(),
                        "review-association-create",
                        scopeKey(source),
                        input.getClientRequestId(),
                        source.getProjectId(),
                        source.getId(),
                        input.getTargetGuestId(),
                        input.getExpectedShareVersion().toString(),
                        input.getExpectedTargetVersion().toString(),
                        input.getEvidenceReference());
        // 精确重放返回当前状态；撤销/失效后不恢复旧授权，也不再次插入审计。
        return receipts.replay(request, id -> response(source.getProjectId(), id))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        Mono.zip(
                                                        shareRows
                                                                .findById(source.getShareId())
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                PrototypeReviewAssociationService
                                                                                        ::conflict)),
                                                        guests.findById(input.getTargetGuestId())
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                PrototypeReviewAssociationService
                                                                                        ::invalidTarget)))
                                                .flatMap(
                                                        values -> {
                                                            var share = values.getT1();
                                                            var guest = values.getT2();
                                                            if (!sourceShare(source, share)
                                                                    || !active(
                                                                            share,
                                                                            System
                                                                                    .currentTimeMillis())
                                                                    || version(
                                                                                    share
                                                                                            .getConfigVersion())
                                                                            != input
                                                                                    .getExpectedShareVersion())
                                                                return Mono.error(conflict());
                                                            if (!source.getProjectId()
                                                                            .equals(
                                                                                    guest
                                                                                            .getProjectId())
                                                                    || Boolean.FALSE.equals(
                                                                            guest.getEnabled())
                                                                    || version(
                                                                                    guest
                                                                                            .getConfigVersion())
                                                                            != input
                                                                                    .getExpectedTargetVersion())
                                                                return Mono.error(invalidTarget());
                                                            return shares.requireRevisionAccess(
                                                                            share.getToken(),
                                                                            PrototypeShareKeys
                                                                                    .derive(
                                                                                            share,
                                                                                            source
                                                                                                    .getRevisionId()),
                                                                            source.getRevisionId())
                                                                    .then(
                                                                            associations
                                                                                    .createQuery()
                                                                                    .where(
                                                                                            PrototypeReviewAssociationEntity
                                                                                                    ::getActiveScopeKey,
                                                                                            scopeKey(
                                                                                                    source))
                                                                                    .count())
                                                                    .flatMap(
                                                                            count -> {
                                                                                if (count != 0)
                                                                                    return Mono
                                                                                            .error(
                                                                                                    conflict());
                                                                                var row =
                                                                                        new PrototypeReviewAssociationEntity();
                                                                                row.setId(
                                                                                        PrototypeReviewKeys
                                                                                                .random());
                                                                                row
                                                                                        .setActiveScopeKey(
                                                                                                scopeKey(
                                                                                                        source));
                                                                                row.setProjectId(
                                                                                        source
                                                                                                .getProjectId());
                                                                                row.setVersionId(
                                                                                        source
                                                                                                .getVersionId());
                                                                                row.setShareId(
                                                                                        source
                                                                                                .getShareId());
                                                                                row.setRevisionId(
                                                                                        source
                                                                                                .getRevisionId());
                                                                                row
                                                                                        .setSourceThreadId(
                                                                                                source
                                                                                                        .getId());
                                                                                row
                                                                                        .setSourceGuestId(
                                                                                                source
                                                                                                        .getGuestId());
                                                                                row
                                                                                        .setSourceVerificationLevel(
                                                                                                "unverified");
                                                                                row
                                                                                        .setTargetGuestId(
                                                                                                guest
                                                                                                        .getId());
                                                                                row
                                                                                        .setTargetVerificationLevel(
                                                                                                "email");
                                                                                row
                                                                                        .setShareConfigVersion(
                                                                                                version(
                                                                                                        share
                                                                                                                .getConfigVersion()));
                                                                                row
                                                                                        .setShareGeneration(
                                                                                                PrototypeReviewKeys
                                                                                                        .digest(
                                                                                                                share
                                                                                                                        .getAccessKey()));
                                                                                row
                                                                                        .setTargetGuestConfigVersion(
                                                                                                version(
                                                                                                        guest
                                                                                                                .getConfigVersion()));
                                                                                row
                                                                                        .setEvidenceReference(
                                                                                                input
                                                                                                        .getEvidenceReference());
                                                                                row.setCreatedBy(
                                                                                        actor
                                                                                                .userId());
                                                                                row
                                                                                        .setCreatedByName(
                                                                                                actor.authentication()
                                                                                                        .getUser()
                                                                                                        .getName());
                                                                                row.setCreateTime(
                                                                                        System
                                                                                                .currentTimeMillis());
                                                                                row.setRevoked(
                                                                                        false);
                                                                                row
                                                                                        .setConfigVersion(
                                                                                                0L);
                                                                                return associations
                                                                                        .insert(row)
                                                                                        .then(
                                                                                                receipts
                                                                                                        .record(
                                                                                                                request,
                                                                                                                row
                                                                                                                        .getId(),
                                                                                                                row
                                                                                                                        .getRevisionId()))
                                                                                        .then(
                                                                                                response(
                                                                                                        row
                                                                                                                .getProjectId(),
                                                                                                        row
                                                                                                                .getId()));
                                                                            });
                                                        })));
    }

    public Mono<ReviewAssociationResponse> revoke(
            String project, String id, ReviewAssociationRevokeRequest input) {
        return access.requireProject(project, PrototypeProjectAccessService.Action.MEMBERS)
                .then(find(project, id))
                .flatMap(
                        before ->
                                access.lockProject(project)
                                        .then(access.lockVersion(before.getVersionId()))
                                        .then(
                                                access.requireProject(
                                                        project,
                                                        PrototypeProjectAccessService.Action
                                                                .MEMBERS))
                                        .then(find(project, id))
                                        .flatMap(
                                                row ->
                                                        access.current()
                                                                .flatMap(
                                                                        actor ->
                                                                                revoke(
                                                                                        row, actor,
                                                                                        input)))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/review/association/revoke");
    }

    private Mono<ReviewAssociationResponse> revoke(
            PrototypeReviewAssociationEntity row,
            PrototypeProjectAccessService.Actor actor,
            ReviewAssociationRevokeRequest input) {
        var request =
                receipts.request(
                        "member:null:" + actor.userId(),
                        "review-association-revoke",
                        row.getId(),
                        input.clientRequestId(),
                        row.getProjectId(),
                        row.getId(),
                        input.expectedVersion().toString(),
                        input.reason());
        return receipts.replay(request, id -> response(row.getProjectId(), id))
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    if (row.getConfigVersion()
                                                    != input.expectedVersion().longValue()
                                            || row.getConfigVersion() == Long.MAX_VALUE)
                                        return Mono.error(conflict());
                                    if (Boolean.TRUE.equals(row.getRevoked()))
                                        return receipts.record(
                                                        request, row.getId(), row.getRevisionId())
                                                .then(response(row.getProjectId(), row.getId()));
                                    return associations
                                            .createUpdate()
                                            .set(PrototypeReviewAssociationEntity::getRevoked, true)
                                            .setNull(
                                                    PrototypeReviewAssociationEntity
                                                            ::getActiveScopeKey)
                                            .set(
                                                    PrototypeReviewAssociationEntity
                                                            ::getConfigVersion,
                                                    row.getConfigVersion() + 1)
                                            .set(
                                                    PrototypeReviewAssociationEntity::getRevokedBy,
                                                    actor.userId())
                                            .set(
                                                    PrototypeReviewAssociationEntity
                                                            ::getRevokedByName,
                                                    actor.authentication().getUser().getName())
                                            .set(
                                                    PrototypeReviewAssociationEntity::getRevokedAt,
                                                    System.currentTimeMillis())
                                            .set(
                                                    PrototypeReviewAssociationEntity
                                                            ::getRevokeReason,
                                                    input.reason())
                                            .where(
                                                    PrototypeReviewAssociationEntity::getId,
                                                    row.getId())
                                            .and(
                                                    PrototypeReviewAssociationEntity::getProjectId,
                                                    row.getProjectId())
                                            .and(
                                                    PrototypeReviewAssociationEntity
                                                            ::getConfigVersion,
                                                    input.expectedVersion())
                                            .and(
                                                    PrototypeReviewAssociationEntity::getRevoked,
                                                    false)
                                            .execute()
                                            .flatMap(
                                                    changed ->
                                                            changed == 1
                                                                    ? receipts.record(
                                                                            request,
                                                                            row.getId(),
                                                                            row.getRevisionId())
                                                                    : Mono.error(conflict()))
                                            .then(response(row.getProjectId(), row.getId()));
                                }));
    }

    private Mono<PrototypeReviewThreadEntity> source(String project, String id) {
        return access.requireProject(project, PrototypeProjectAccessService.Action.MEMBERS)
                .then(
                        threads.createQuery()
                                .where(PrototypeReviewThreadEntity::getId, id)
                                .and(PrototypeReviewThreadEntity::getProjectId, project)
                                .fetch()
                                .singleOrEmpty())
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("review_thread")))
                .flatMap(
                        row ->
                                "unverified".equals(row.getVerificationLevel())
                                        ? Mono.just(row)
                                        : Mono.error(
                                                PrototypeHubException.badRequest(
                                                        "error.prototype-hub.association_source_invalid",
                                                        "PROTOTYPE_HUB_ASSOCIATION_SOURCE_INVALID")));
    }

    private Mono<PrototypeReviewAssociationEntity> find(String project, String id) {
        return associations
                .createQuery()
                .where(PrototypeReviewAssociationEntity::getId, id)
                .and(PrototypeReviewAssociationEntity::getProjectId, project)
                .fetch()
                .singleOrEmpty()
                .switchIfEmpty(
                        Mono.error(() -> PrototypeHubException.missing("review_association")));
    }

    private String scopeKey(PrototypeReviewThreadEntity row) {
        return receipts.request(
                        "review-association",
                        "scope",
                        "scope",
                        "scope",
                        row.getProjectId(),
                        row.getVersionId(),
                        row.getShareId(),
                        row.getRevisionId(),
                        "unverified",
                        row.getGuestId())
                .fingerprint();
    }

    private static boolean sameSource(
            PrototypeReviewThreadEntity a, PrototypeReviewThreadEntity b) {
        return Objects.equals(a.getProjectId(), b.getProjectId())
                && Objects.equals(a.getVersionId(), b.getVersionId())
                && Objects.equals(a.getShareId(), b.getShareId())
                && Objects.equals(a.getRevisionId(), b.getRevisionId())
                && Objects.equals(a.getGuestId(), b.getGuestId())
                && Objects.equals(a.getVerificationLevel(), b.getVerificationLevel());
    }

    private Mono<Boolean> scopeAvailable(
            PrototypeReviewThreadEntity source, PrototypeShareEntity share) {
        if (!sourceShare(source, share) || !active(share, System.currentTimeMillis()))
            return Mono.just(false);
        // 跨表条件使用and().is方法引用；and(getter,value)会先转列名而丢失实体别名。
        return queries.select(Snapshot.class)
                .all(PrototypeVersionEntity.class, Snapshot::setVersion)
                .as(PrototypeRevisionEntity::getId, Snapshot::setRevisionId)
                .from(PrototypeVersionEntity.class)
                .innerJoin(
                        PrototypeRevisionEntity.class,
                        on ->
                                on.is(
                                        PrototypeRevisionEntity::getVersionId,
                                        PrototypeVersionEntity::getId))
                .where(
                        c ->
                                c.is(PrototypeVersionEntity::getId, source.getVersionId())
                                        .and(
                                                PrototypeVersionEntity::getProjectId,
                                                source.getProjectId())
                                        .isNull(PrototypeVersionEntity::getDeletedTime)
                                        .and()
                                        .is(PrototypeRevisionEntity::getId, source.getRevisionId())
                                        .and()
                                        .is(
                                                PrototypeRevisionEntity::getProjectId,
                                                source.getProjectId()))
                .fetch()
                .hasElements();
    }

    static boolean active(PrototypeShareEntity share, long now) {
        return share != null
                && Boolean.TRUE.equals(share.getEnabled())
                && Boolean.TRUE.equals(share.getReviewEnabled())
                && !Boolean.TRUE.equals(share.getRevoked())
                && share.getRevisionId() != null
                && (share.getRevisionMode() == null || "fixed".equals(share.getRevisionMode()))
                && share.getAccessKey() != null
                && !share.getAccessKey().isBlank()
                && (share.getExpiresAt() == null || share.getExpiresAt() > now);
    }

    private static boolean sourceShare(
            PrototypeReviewThreadEntity source, PrototypeShareEntity share) {
        return share != null
                && Objects.equals(source.getProjectId(), share.getProjectId())
                && Objects.equals(source.getVersionId(), share.getVersionId())
                && Objects.equals(source.getRevisionId(), share.getRevisionId());
    }

    static long version(Long value) {
        return value == null ? 0 : value;
    }

    /** 唯一未撤销槽使JOIN至多匹配一行；COUNT与分页共享数据库括号谓词，不在内存修补授权或总数。 */
    Mono<QueryHelper.JoinSpec<ThreadRow>> visitorQuery(
            String project,
            String versionId,
            String revision,
            String shareId,
            String actor,
            String level,
            QueryParamEntity page) {
        return Mono.zip(shareRows.findById(shareId), guests.findById(actor))
                .switchIfEmpty(Mono.error(PrototypeReviewThreadService::unavailable))
                .map(
                        values -> {
                            var share = values.getT1();
                            var guest = values.getT2();
                            var query =
                                    queries.select(ThreadRow.class)
                                            .all(
                                                    PrototypeReviewThreadEntity.class,
                                                    ThreadRow::setThread)
                                            .from(PrototypeReviewThreadEntity.class)
                                            .leftJoin(
                                                    PrototypeReviewAssociationEntity.class,
                                                    on ->
                                                            on.is(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getSourceGuestId,
                                                                            PrototypeReviewThreadEntity
                                                                                    ::getGuestId)
                                                                    .is(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getSourceVerificationLevel,
                                                                            PrototypeReviewThreadEntity
                                                                                    ::getVerificationLevel)
                                                                    .is(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getProjectId,
                                                                            PrototypeReviewThreadEntity
                                                                                    ::getProjectId)
                                                                    .is(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getVersionId,
                                                                            PrototypeReviewThreadEntity
                                                                                    ::getVersionId)
                                                                    .is(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getShareId,
                                                                            PrototypeReviewThreadEntity
                                                                                    ::getShareId)
                                                                    .is(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getRevisionId,
                                                                            PrototypeReviewThreadEntity
                                                                                    ::getRevisionId)
                                                                    .and(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getRevoked,
                                                                            false)
                                                                    .and(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getTargetVerificationLevel,
                                                                            "email")
                                                                    .and(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getTargetGuestId,
                                                                            actor)
                                                                    .and(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getShareConfigVersion,
                                                                            version(
                                                                                    share
                                                                                            .getConfigVersion()))
                                                                    .and(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getShareGeneration,
                                                                            PrototypeReviewKeys
                                                                                    .digest(
                                                                                            share
                                                                                                    .getAccessKey()))
                                                                    .and(
                                                                            PrototypeReviewAssociationEntity
                                                                                    ::getTargetGuestConfigVersion,
                                                                            version(
                                                                                    guest
                                                                                            .getConfigVersion())));
                            query.where(page);
                            query.where(
                                    c ->
                                            c.is(PrototypeReviewThreadEntity::getProjectId, project)
                                                    .and(
                                                            PrototypeReviewThreadEntity
                                                                    ::getVersionId,
                                                            versionId)
                                                    .and(
                                                            PrototypeReviewThreadEntity
                                                                    ::getRevisionId,
                                                            revision)
                                                    .and(
                                                            PrototypeReviewThreadEntity::getShareId,
                                                            shareId)
                                                    .nest()
                                                    .nest()
                                                    .is(
                                                            PrototypeReviewThreadEntity::getGuestId,
                                                            actor)
                                                    .and()
                                                    .is(
                                                            PrototypeReviewThreadEntity
                                                                    ::getVerificationLevel,
                                                            level)
                                                    .end()
                                                    .or()
                                                    .notNull(
                                                            PrototypeReviewAssociationEntity::getId)
                                                    .end());
                            return query;
                        });
    }

    private QueryHelper.JoinSpec<Snapshot> snapshots() {
        return queries.select(Snapshot.class)
                .all(PrototypeReviewAssociationEntity.class, Snapshot::setAssociation)
                .all(PrototypeShareEntity.class, Snapshot::setShare)
                .all(PrototypeReviewGuestEntity.class, Snapshot::setGuest)
                .all(PrototypeVersionEntity.class, Snapshot::setVersion)
                .as(PrototypeRevisionEntity::getId, Snapshot::setRevisionId)
                .from(PrototypeReviewAssociationEntity.class)
                .leftJoin(
                        PrototypeShareEntity.class,
                        on ->
                                on.is(
                                        PrototypeShareEntity::getId,
                                        PrototypeReviewAssociationEntity::getShareId))
                .leftJoin(
                        PrototypeReviewGuestEntity.class,
                        on ->
                                on.is(
                                        PrototypeReviewGuestEntity::getId,
                                        PrototypeReviewAssociationEntity::getTargetGuestId))
                .leftJoin(
                        PrototypeVersionEntity.class,
                        on ->
                                on.is(
                                        PrototypeVersionEntity::getId,
                                        PrototypeReviewAssociationEntity::getVersionId))
                .leftJoin(
                        PrototypeRevisionEntity.class,
                        on ->
                                on.is(
                                        PrototypeRevisionEntity::getId,
                                        PrototypeReviewAssociationEntity::getRevisionId));
    }

    private Mono<ReviewAssociationResponse> response(String project, String id) {
        return snapshots()
                .where(
                        c ->
                                c.is(PrototypeReviewAssociationEntity::getId, id)
                                        .and(
                                                PrototypeReviewAssociationEntity::getProjectId,
                                                project))
                .fetch()
                .singleOrEmpty()
                .switchIfEmpty(
                        Mono.error(() -> PrototypeHubException.missing("review_association")))
                .map(PrototypeReviewAssociationService::response);
    }

    static ReviewAssociationResponse response(Snapshot snapshot) {
        var row = snapshot.getAssociation();
        var guest = snapshot.getGuest();
        String reason = invalidReason(snapshot, System.currentTimeMillis());
        return new ReviewAssociationResponse(
                row.getId(),
                row.getSourceThreadId(),
                row.getVersionId(),
                row.getShareId(),
                row.getRevisionId(),
                row.getTargetGuestId(),
                guest == null || guest.getId() == null ? null : ReviewGuestResponse.from(guest),
                Boolean.TRUE.equals(row.getRevoked())
                        ? "revoked"
                        : reason == null ? "active" : "invalidated",
                Boolean.TRUE.equals(row.getRevoked()) ? null : reason,
                row.getConfigVersion(),
                row.getShareConfigVersion(),
                row.getTargetGuestConfigVersion(),
                row.getEvidenceReference(),
                row.getCreatedBy(),
                row.getCreatedByName(),
                row.getCreateTime(),
                row.getRevokedBy(),
                row.getRevokedByName(),
                row.getRevokedAt(),
                row.getRevokeReason());
    }

    static String invalidReason(Snapshot s, long now) {
        var row = s.getAssociation();
        var share = s.getShare();
        var guest = s.getGuest();
        if (!active(share, now)) return "SHARE_UNAVAILABLE";
        if (!Objects.equals(row.getProjectId(), share.getProjectId())
                || !Objects.equals(row.getVersionId(), share.getVersionId())
                || !Objects.equals(row.getRevisionId(), share.getRevisionId())
                || row.getShareConfigVersion() != version(share.getConfigVersion())
                || !PrototypeReviewKeys.matches(
                        row.getShareGeneration(), PrototypeReviewKeys.digest(share.getAccessKey())))
            return "SHARE_CHANGED";
        if (s.getVersion() == null
                || s.getVersion().getId() == null
                || s.getVersion().getDeletedTime() != null
                || s.getRevisionId() == null) return "REVISION_UNAVAILABLE";
        if (guest == null
                || guest.getId() == null
                || !Objects.equals(row.getProjectId(), guest.getProjectId())
                || Boolean.FALSE.equals(guest.getEnabled())) return "TARGET_UNAVAILABLE";
        if (row.getTargetGuestConfigVersion() != version(guest.getConfigVersion()))
            return "TARGET_CHANGED";
        return null;
    }

    /** 身份清理和普通撤销不删除审计；永久项目/版本清理才分批删除关联及操作回执。 */
    Mono<Void> purge(String field, String id) {
        return Mono.defer(
                        () ->
                                associations
                                        .createQuery()
                                        .select(PrototypeReviewAssociationEntity::getId)
                                        .where(field, id)
                                        .paging(0, 50)
                                        .fetch()
                                        .map(PrototypeReviewAssociationEntity::getId)
                                        .collectList())
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(false)
                                        : receiptRows
                                                .createDelete()
                                                .in(
                                                        PrototypeCreationReceiptEntity
                                                                ::getResourceId,
                                                        ids)
                                                .execute()
                                                .then(
                                                        associations
                                                                .createDelete()
                                                                .in(
                                                                        PrototypeReviewAssociationEntity
                                                                                ::getId,
                                                                        ids)
                                                                .execute())
                                                .thenReturn(true))
                .repeat()
                .takeUntil(more -> !more)
                .then();
    }

    static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.association_conflict",
                "PROTOTYPE_HUB_ASSOCIATION_CONFLICT",
                409);
    }

    static PrototypeHubException invalidTarget() {
        return new PrototypeHubException(
                "error.prototype-hub.association_target_invalid",
                "PROTOTYPE_HUB_ASSOCIATION_TARGET_INVALID",
                409);
    }

    /** 单次QueryHelper内部投影；不作为HTTP实体或授权缓存。 */
    @Getter
    @Setter
    public static class Snapshot {
        private PrototypeReviewAssociationEntity association;
        private PrototypeShareEntity share;
        private PrototypeReviewGuestEntity guest;
        private PrototypeVersionEntity version;
        private String revisionId;
    }

    /** 线程数据库授权查询结果；分页后只映射投影，不过滤。 */
    @Getter
    @Setter
    public static class ThreadRow {
        private PrototypeReviewThreadEntity thread;
    }
}
