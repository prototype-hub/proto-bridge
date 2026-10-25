package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeReviewChallengeEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewInvitationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewRateEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.service.keys.PrototypeShareKeys;
import com.gaiys.proto.prototypehub.web.request.ReviewInvitationCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewInvitationQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewInvitationRevokeRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewInvitationCreatedResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewInvitationResponse;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 固定分享的单次定向邀请，不发送邮件或代替分享授权。 项目→版本锁与OTP消费、分享变更共用；仅对当前有界页面批量核验访客版本。 */
@Service
@RequiredArgsConstructor
public class PrototypeReviewInvitationService {
    public static final long TTL = 24 * 60 * 60 * 1000L;
    private static final long HOUR = 60 * 60 * 1000L;
    private final PrototypeProjectAccessService access;
    private final PrototypeShareService shares;
    private final ReactiveRepository<PrototypeReviewInvitationEntity, String> invitations;
    private final ReactiveRepository<PrototypeReviewGuestEntity, String> guests;
    private final ReactiveRepository<PrototypeReviewRateEntity, String> rates;
    private final TransactionalOperator transactions;

    public Mono<ReviewInvitationCreatedResponse> create(
            String shareId, ReviewInvitationCreateRequest input) {
        return lockedShare(shareId)
                .flatMap(
                        share -> {
                            long now = System.currentTimeMillis();
                            if (version(share.getConfigVersion()) != input.expectedShareVersion())
                                return Mono.error(conflict());
                            if (!active(share, now)) return Mono.error(conflict());
                            String email = PrototypeReviewKeys.email(input.email());
                            return shares.requireRevisionAccess(
                                            share.getToken(),
                                            PrototypeShareKeys.derive(share, share.getRevisionId()),
                                            share.getRevisionId())
                                    .then(guestVersion(share.getProjectId(), email))
                                    .flatMap(
                                            guestVersion -> {
                                                String raw = PrototypeReviewKeys.random();
                                                var row = new PrototypeReviewInvitationEntity();
                                                row.setId(PrototypeReviewKeys.random());
                                                row.setTokenHash(tokenHash(raw));
                                                row.setEmail(email);
                                                row.setProjectId(share.getProjectId());
                                                row.setVersionId(share.getVersionId());
                                                row.setShareId(share.getId());
                                                row.setRevisionId(share.getRevisionId());
                                                row.setShareConfigVersion(
                                                        version(share.getConfigVersion()));
                                                row.setShareGeneration(
                                                        PrototypeReviewKeys.digest(
                                                                share.getAccessKey()));
                                                row.setGuestConfigVersion(guestVersion);
                                                row.setConfigVersion(0L);
                                                row.setRevoked(false);
                                                row.setCreateTime(now);
                                                row.setExpiresAt(
                                                        Math.min(
                                                                now + TTL,
                                                                share.getExpiresAt() == null
                                                                        ? Long.MAX_VALUE
                                                                        : share.getExpiresAt()));
                                                return rate(
                                                                row.getProjectId(),
                                                                "invitation-project",
                                                                60,
                                                                false,
                                                                now)
                                                        .then(
                                                                rate(
                                                                        row.getProjectId(),
                                                                        "invitation-recipient:"
                                                                                + PrototypeReviewKeys
                                                                                        .guest(
                                                                                                row
                                                                                                        .getProjectId(),
                                                                                                email),
                                                                        5,
                                                                        true,
                                                                        now))
                                                        .then(invitations.insert(row))
                                                        .thenReturn(
                                                                new ReviewInvitationCreatedResponse(
                                                                        response(row, "pending"),
                                                                        raw));
                                            });
                        })
                .as(transactions::transactional)
                .checkpoint("prototype-hub/review/invitation/create");
    }

    public Mono<PagerResult<ReviewInvitationResponse>> query(
            String shareId, ReviewInvitationQueryRequest input) {
        return access.requireShare(shareId, PrototypeProjectAccessService.Action.SHARE)
                .flatMap(
                        share -> {
                            QueryParamEntity query =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeReviewInvitationEntity::getShareId,
                                                    shareId)
                                            .and(
                                                    PrototypeReviewInvitationEntity::getProjectId,
                                                    share.getProjectId())
                                            .orderByDesc(
                                                    PrototypeReviewInvitationEntity::getCreateTime)
                                            .orderByAsc(PrototypeReviewInvitationEntity::getId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            return QueryHelper.transformPageResult(
                                    QueryHelper.queryPager(
                                            query, invitations::createQuery, row -> row),
                                    rows -> responses(share, rows));
                        });
    }

    private Mono<List<ReviewInvitationResponse>> responses(
            PrototypeShareEntity share, List<PrototypeReviewInvitationEntity> rows) {
        if (rows.isEmpty()) return Mono.just(List.of());
        List<String> ids =
                rows.stream()
                        .map(row -> PrototypeReviewKeys.guest(row.getProjectId(), row.getEmail()))
                        .distinct()
                        .toList();
        return guests.createQuery()
                .in(PrototypeReviewGuestEntity::getId, ids)
                .fetch()
                .collectMap(PrototypeReviewGuestEntity::getId)
                .map(
                        current ->
                                rows.stream()
                                        .map(
                                                row ->
                                                        response(
                                                                row,
                                                                status(
                                                                        row,
                                                                        share,
                                                                        current.get(
                                                                                PrototypeReviewKeys
                                                                                        .guest(
                                                                                                row
                                                                                                        .getProjectId(),
                                                                                                row
                                                                                                        .getEmail())),
                                                                        System
                                                                                .currentTimeMillis())))
                                        .toList());
    }

    public Mono<ReviewInvitationResponse> revoke(
            String shareId, String id, ReviewInvitationRevokeRequest input) {
        return lockedShare(shareId)
                .flatMap(
                        share ->
                                invitations
                                        .createQuery()
                                        .where(PrototypeReviewInvitationEntity::getId, id)
                                        .and(PrototypeReviewInvitationEntity::getShareId, shareId)
                                        .and(
                                                PrototypeReviewInvitationEntity::getProjectId,
                                                share.getProjectId())
                                        .fetch()
                                        .singleOrEmpty()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "review_invitation")))
                                        .flatMap(
                                                row ->
                                                        guestState(row)
                                                                .flatMap(
                                                                        current -> {
                                                                            if (row.getConfigVersion()
                                                                                            .longValue()
                                                                                    != input
                                                                                            .expectedVersion())
                                                                                return Mono.error(
                                                                                        conflict());
                                                                            String state =
                                                                                    status(
                                                                                            row,
                                                                                            share,
                                                                                            current
                                                                                                    .orElse(
                                                                                                            null),
                                                                                            System
                                                                                                    .currentTimeMillis());
                                                                            if ("revoked"
                                                                                    .equals(state))
                                                                                return Mono.just(
                                                                                        response(
                                                                                                row,
                                                                                                state));
                                                                            if (!"pending"
                                                                                            .equals(
                                                                                                    state)
                                                                                    || row
                                                                                                    .getConfigVersion()
                                                                                            == Long
                                                                                                    .MAX_VALUE)
                                                                                return Mono.error(
                                                                                        conflict());
                                                                            long previous =
                                                                                    row
                                                                                            .getConfigVersion();
                                                                            row.setRevoked(true);
                                                                            row.setConfigVersion(
                                                                                    previous + 1);
                                                                            return invitations
                                                                                    .createUpdate()
                                                                                    .set(
                                                                                            PrototypeReviewInvitationEntity
                                                                                                    ::getRevoked,
                                                                                            true)
                                                                                    .set(
                                                                                            PrototypeReviewInvitationEntity
                                                                                                    ::getConfigVersion,
                                                                                            row
                                                                                                    .getConfigVersion())
                                                                                    .where(
                                                                                            PrototypeReviewInvitationEntity
                                                                                                    ::getId,
                                                                                            id)
                                                                                    .and(
                                                                                            PrototypeReviewInvitationEntity
                                                                                                    ::getConfigVersion,
                                                                                            previous)
                                                                                    .execute()
                                                                                    .flatMap(
                                                                                            count ->
                                                                                                    count
                                                                                                                    == 1
                                                                                                            ? Mono
                                                                                                                    .just(
                                                                                                                            response(
                                                                                                                                    row,
                                                                                                                                    "revoked"))
                                                                                                            : Mono
                                                                                                                    .error(
                                                                                                                            conflict()));
                                                                        })))
                .as(transactions::transactional);
    }

    /** 只读预检和持锁准备共用；真正签发仍在项目→版本锁内重新核验，所有不匹配返回同一错误。 */
    public Mono<PrototypeReviewInvitationEntity> requireToken(
            PrototypeShareEntity share, String revisionId, String email, String token) {
        return invitations
                .createQuery()
                .where(PrototypeReviewInvitationEntity::getTokenHash, tokenHash(token))
                .fetch()
                .singleOrEmpty()
                .flatMap(row -> requireCurrent(row, share, revisionId, email))
                .switchIfEmpty(Mono.error(PrototypeReviewInvitationService::invalid));
    }

    /** 发送受理及消费必须重验持久化绑定，不能用请求提供的新邀请替换已有挑战。 */
    public Mono<Void> validateChallenge(
            PrototypeShareEntity share, PrototypeReviewChallengeEntity challenge) {
        return challenge.getInvitationId() == null
                ? Mono.empty()
                : requireChallenge(share, challenge).then();
    }

    private Mono<PrototypeReviewInvitationEntity> requireChallenge(
            PrototypeShareEntity share, PrototypeReviewChallengeEntity challenge) {
        return invitations
                .findById(challenge.getInvitationId())
                .flatMap(
                        row ->
                                requireCurrent(
                                        row,
                                        share,
                                        challenge.getRevisionId(),
                                        challenge.getEmail()))
                .switchIfEmpty(Mono.error(PrototypeReviewInvitationService::invalid));
    }

    /** 与挑战、访客和会话处于同一事务；失败会回滚消费，重放不能第二次使用邀请。 */
    public Mono<Void> consume(
            PrototypeShareEntity share, PrototypeReviewChallengeEntity challenge) {
        if (challenge.getInvitationId() == null) return Mono.empty();
        return requireChallenge(share, challenge)
                .flatMap(
                        row -> {
                            if (row.getConfigVersion() == Long.MAX_VALUE)
                                return Mono.error(invalid());
                            return invitations
                                    .createUpdate()
                                    .set(
                                            PrototypeReviewInvitationEntity::getConsumedAt,
                                            System.currentTimeMillis())
                                    .set(
                                            PrototypeReviewInvitationEntity::getConfigVersion,
                                            row.getConfigVersion() + 1)
                                    .where(PrototypeReviewInvitationEntity::getId, row.getId())
                                    .and(
                                            PrototypeReviewInvitationEntity::getConfigVersion,
                                            row.getConfigVersion())
                                    .and(PrototypeReviewInvitationEntity::getRevoked, false)
                                    .isNull(PrototypeReviewInvitationEntity::getConsumedAt)
                                    .execute()
                                    .flatMap(
                                            count ->
                                                    count == 1
                                                            ? Mono.empty()
                                                            : Mono.error(invalid()));
                        });
    }

    private Mono<PrototypeReviewInvitationEntity> requireCurrent(
            PrototypeReviewInvitationEntity row,
            PrototypeShareEntity share,
            String revisionId,
            String email) {
        if (!Objects.equals(row.getEmail(), email)
                || !Objects.equals(row.getRevisionId(), revisionId)) return Mono.error(invalid());
        return guestState(row)
                .filter(
                        current ->
                                "pending"
                                        .equals(
                                                status(
                                                        row,
                                                        share,
                                                        current.orElse(null),
                                                        System.currentTimeMillis())))
                .map(ignored -> row)
                .switchIfEmpty(Mono.error(PrototypeReviewInvitationService::invalid));
    }

    private Mono<Optional<PrototypeReviewGuestEntity>> guestState(
            PrototypeReviewInvitationEntity row) {
        return guests.createQuery()
                .where(
                        PrototypeReviewGuestEntity::getId,
                        PrototypeReviewKeys.guest(row.getProjectId(), row.getEmail()))
                .fetch()
                .singleOrEmpty()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private Mono<Long> guestVersion(String projectId, String email) {
        return guests.findById(PrototypeReviewKeys.guest(projectId, email))
                .flatMap(
                        guest ->
                                Boolean.FALSE.equals(guest.getEnabled())
                                        ? Mono.error(conflict())
                                        : Mono.just(version(guest.getConfigVersion())))
                .defaultIfEmpty(0L);
    }

    static String status(
            PrototypeReviewInvitationEntity row,
            PrototypeShareEntity share,
            PrototypeReviewGuestEntity guest,
            long now) {
        if (Boolean.TRUE.equals(row.getRevoked())) return "revoked";
        if (row.getConsumedAt() != null) return "consumed";
        if (row.getExpiresAt() <= now) return "expired";
        if (!active(share, now)
                || !Objects.equals(row.getShareId(), share.getId())
                || !Objects.equals(row.getProjectId(), share.getProjectId())
                || !Objects.equals(row.getVersionId(), share.getVersionId())
                || !Objects.equals(row.getRevisionId(), share.getRevisionId())
                || row.getShareConfigVersion() != version(share.getConfigVersion())
                || !PrototypeReviewKeys.matches(
                        row.getShareGeneration(), PrototypeReviewKeys.digest(share.getAccessKey()))
                || (guest != null && Boolean.FALSE.equals(guest.getEnabled()))
                || row.getGuestConfigVersion()
                        != (guest == null ? 0 : version(guest.getConfigVersion())))
            return "invalidated";
        return "pending";
    }

    private static boolean active(PrototypeShareEntity share, long now) {
        return Boolean.TRUE.equals(share.getEnabled())
                && Boolean.TRUE.equals(share.getReviewEnabled())
                && !Boolean.TRUE.equals(share.getRevoked())
                && share.getRevisionId() != null
                && (share.getRevisionMode() == null || "fixed".equals(share.getRevisionMode()))
                && share.getAccessKey() != null
                && !share.getAccessKey().isBlank()
                && (share.getExpiresAt() == null || share.getExpiresAt() > now);
    }

    private Mono<PrototypeShareEntity> lockedShare(String id) {
        return access.requireShare(id, PrototypeProjectAccessService.Action.SHARE)
                .flatMap(
                        before ->
                                access.lockProject(before.getProjectId())
                                        .then(access.lockVersion(before.getVersionId()))
                                        .then(
                                                access.requireShare(
                                                        id,
                                                        PrototypeProjectAccessService.Action.SHARE))
                                        .filter(
                                                current ->
                                                        Objects.equals(
                                                                        before.getProjectId(),
                                                                        current.getProjectId())
                                                                && Objects.equals(
                                                                        before.getVersionId(),
                                                                        current.getVersionId()))
                                        .switchIfEmpty(Mono.error(conflict())));
    }

    private Mono<Void> rate(String project, String scope, int limit, boolean cooldown, long now) {
        String id = PrototypeReviewKeys.digest("review-rate-v1:" + project + ":" + scope);
        return rates.findById(id)
                .switchIfEmpty(
                        Mono.fromSupplier(
                                () -> {
                                    var row = new PrototypeReviewRateEntity();
                                    row.setId(id);
                                    row.setProjectId(project);
                                    row.setWindowStart(now / HOUR * HOUR);
                                    row.setSendCount(0);
                                    row.setNextAllowedAt(0L);
                                    return row;
                                }))
                .flatMap(
                        row -> {
                            long window = now / HOUR * HOUR;
                            int count = row.getWindowStart() == window ? row.getSendCount() : 0;
                            if (count >= limit || (cooldown && row.getNextAllowedAt() > now))
                                return Mono.error(
                                        new PrototypeHubException(
                                                "error.prototype-hub.review_rate_limited",
                                                "PROTOTYPE_HUB_REVIEW_RATE_LIMITED",
                                                429));
                            row.setWindowStart(window);
                            row.setSendCount(count + 1);
                            row.setNextAllowedAt(cooldown ? now + 60000 : 0L);
                            row.setExpiresAt(Math.max(window + HOUR, row.getNextAllowedAt()));
                            return rates.save(row).then();
                        });
    }

    public Mono<Void> cleanupExpired(long now) {
        return Mono.defer(
                () ->
                        invitations
                                .createQuery()
                                .select(PrototypeReviewInvitationEntity::getId)
                                .lte(PrototypeReviewInvitationEntity::getExpiresAt, now)
                                .orderBy(
                                        SortOrder.asc(
                                                PrototypeReviewInvitationEntity::getExpiresAt),
                                        SortOrder.asc(PrototypeReviewInvitationEntity::getId))
                                .paging(0, 50)
                                .fetch()
                                .map(PrototypeReviewInvitationEntity::getId)
                                .collectList()
                                .flatMap(
                                        ids ->
                                                ids.isEmpty()
                                                        ? Mono.empty()
                                                        : invitations
                                                                .createDelete()
                                                                .in(
                                                                        PrototypeReviewInvitationEntity
                                                                                ::getId,
                                                                        ids)
                                                                .lte(
                                                                        PrototypeReviewInvitationEntity
                                                                                ::getExpiresAt,
                                                                        now)
                                                                .execute()
                                                                .then()));
    }

    public Mono<Void> purgeVersion(String id) {
        return invitations
                .createDelete()
                .where(PrototypeReviewInvitationEntity::getVersionId, id)
                .execute()
                .then();
    }

    public Mono<Void> purgeProject(String id) {
        return invitations
                .createDelete()
                .where(PrototypeReviewInvitationEntity::getProjectId, id)
                .execute()
                .then();
    }

    private static long version(Long value) {
        return value == null ? 0 : value;
    }

    private static String tokenHash(String token) {
        return PrototypeReviewKeys.digest("review-invitation-v1:" + token);
    }

    private static ReviewInvitationResponse response(
            PrototypeReviewInvitationEntity row, String status) {
        return new ReviewInvitationResponse(
                row.getId(),
                PrototypeReviewKeys.masked(row.getEmail()),
                status,
                row.getConfigVersion(),
                row.getCreateTime(),
                row.getExpiresAt(),
                row.getConsumedAt());
    }

    public static PrototypeHubException invalid() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.review_invitation_invalid",
                "PROTOTYPE_HUB_REVIEW_INVITATION_INVALID");
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.review_invitation_conflict",
                "PROTOTYPE_HUB_REVIEW_INVITATION_CONFLICT",
                409);
    }
}
