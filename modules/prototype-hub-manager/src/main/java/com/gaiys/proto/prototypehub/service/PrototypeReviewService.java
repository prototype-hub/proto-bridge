package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeReviewChallengeEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewRateEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewUnverifiedGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.service.keys.PrototypeShareKeys;
import com.gaiys.proto.prototypehub.service.notification.NotificationGateway;
import com.gaiys.proto.prototypehub.service.notification.SmtpNotificationGateway;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.ReviewChallengeRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewUnverifiedRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewVerifyRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewChallengeResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewContextResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewContextResponse.ReviewIdentity;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 公开分享的邮箱验证与显式未验证身份；项目→版本锁是跨实例签发及限频的串行化边界。
 *
 * <p>BCrypt/SMTP只在数据库事务外的FileWork执行；错误尝试先提交，再在外层返回通用错误。 无内部用户、常驻身份缓存或通知队列；短期数据复用既有Retention周期。
 */
@Service
@RequiredArgsConstructor
public class PrototypeReviewService {
    public static final long SESSION_TTL = 8 * 60 * 60 * 1000L;
    public static final long CODE_TTL = 5 * 60 * 1000L;
    private static final long HOUR = 60 * 60 * 1000L;
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);
    private final PrototypeShareService shares;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypeReviewGuestEntity, String> guests;
    private final ReactiveRepository<PrototypeReviewUnverifiedGuestEntity, String> unverifiedGuests;
    private final ReactiveRepository<PrototypeReviewChallengeEntity, String> challenges;
    private final ReactiveRepository<PrototypeReviewSessionEntity, String> sessions;
    private final ReactiveRepository<PrototypeReviewRateEntity, String> rates;
    private final NotificationGateway notifications;
    private final FileWork work;
    private final TransactionalOperator transactions;
    private final ObservationRegistry observations;

    public Mono<ReviewContextResponse> context(
            String token, String revisionId, String key, String cookie) {
        return requireScope(token, revisionId, key)
                .flatMap(
                        share ->
                                sessions.createQuery()
                                        .where(
                                                PrototypeReviewSessionEntity::getId,
                                                PrototypeReviewKeys.digest(cookie))
                                        .fetch()
                                        .singleOrEmpty()
                                        .filter(
                                                session ->
                                                        validSession(
                                                                session,
                                                                share,
                                                                revisionId,
                                                                System.currentTimeMillis()))
                                        .flatMap(
                                                session ->
                                                        sessionIdentity(session, share)
                                                                .map(
                                                                        identity ->
                                                                                context(
                                                                                        share,
                                                                                        revisionId,
                                                                                        cookie,
                                                                                        identity)))
                                        .defaultIfEmpty(context(share, revisionId, cookie, null)));
    }

    /** 明确选择低保障路径；持父锁后再次校验策略，既不降级已验证会话也不找回旧访客。 */
    public Mono<Verified> unverified(String token, ReviewUnverifiedRequest input, String cookie) {
        return lockedScope(token, input.revisionId(), input.accessKey())
                .filter(share -> Boolean.TRUE.equals(share.getReviewAllowUnverified()))
                .switchIfEmpty(Mono.error(PrototypeReviewService::denied))
                .flatMap(
                        share ->
                                sessions.findById(PrototypeReviewKeys.digest(cookie))
                                        .flatMap(
                                                session ->
                                                        reuseUnverified(
                                                                share, input, cookie, session))
                                        .switchIfEmpty(
                                                Mono.defer(
                                                        () ->
                                                                issueUnverified(
                                                                        share, input, cookie))))
                .as(transactions::transactional)
                .checkpoint("prototype-hub/review/unverified");
    }

    private Mono<Verified> reuseUnverified(
            PrototypeShareEntity share,
            ReviewUnverifiedRequest input,
            String cookie,
            PrototypeReviewSessionEntity session) {
        if (!knownLevel(session)) return Mono.error(denied());
        // 新授权下显式创建替代身份，不恢复失配/过期身份；旧授权仍由lockedScope拒绝。
        if (!validSession(session, share, input.revisionId(), System.currentTimeMillis()))
            return issueUnverified(share, input, cookie);
        if (emailLevel(session))
            return Mono.error(
                    new PrototypeHubException(
                            "error.prototype-hub.review_identity_exists",
                            "PROTOTYPE_HUB_REVIEW_IDENTITY_EXISTS",
                            409));
        return sessionIdentity(session, share)
                .map(
                        identity ->
                                new Verified(
                                        cookie,
                                        context(share, input.revisionId(), cookie, identity)))
                .switchIfEmpty(Mono.defer(() -> issueUnverified(share, input, cookie)));
    }

    private Mono<Verified> issueUnverified(
            PrototypeShareEntity share, ReviewUnverifiedRequest input, String oldCookie) {
        long now = System.currentTimeMillis();
        String raw = PrototypeReviewKeys.random();
        var guest = new PrototypeReviewUnverifiedGuestEntity();
        guest.setId(PrototypeReviewKeys.random());
        guest.setProjectId(share.getProjectId());
        guest.setDisplayName(input.displayName().strip());
        guest.setCompany(
                input.company() == null || input.company().isBlank()
                        ? null
                        : input.company().strip());
        guest.setEnabled(true);
        guest.setConfigVersion(0L);
        guest.setCreateTime(now);
        guest.setModifyTime(now);
        guest.setExpiresAt(now + SESSION_TTL);
        var session = newSession(share, input.revisionId(), guest.getId(), raw, now);
        session.setVerificationLevel("unverified");
        // 与OTP使用独立的固定域；清除Cookie不能绕过项目总量，复用有效会话不消费配额。
        return rate(share.getProjectId(), "unverified-project", 60, false, now)
                .then(
                        rate(
                                share.getProjectId(),
                                "unverified-cookie:" + PrototypeReviewKeys.digest(oldCookie),
                                5,
                                true,
                                now))
                .then(unverifiedGuests.insert(guest))
                .then(sessions.deleteById(PrototypeReviewKeys.digest(oldCookie)))
                .then(sessions.insert(session))
                .thenReturn(
                        new Verified(
                                raw,
                                context(
                                        share,
                                        input.revisionId(),
                                        raw,
                                        identity(guest, session.getExpiresAt()))));
    }

    private Mono<ReviewIdentity> sessionIdentity(
            PrototypeReviewSessionEntity session, PrototypeShareEntity share) {
        if (!knownLevel(session)) return Mono.error(denied());
        if (emailLevel(session))
            return guests.findById(session.getGuestId())
                    .filter(
                            guest ->
                                    !Boolean.FALSE.equals(guest.getEnabled())
                                            && share.getProjectId().equals(guest.getProjectId()))
                    .map(guest -> identity(guest, session.getExpiresAt()));
        if (!Boolean.TRUE.equals(share.getReviewAllowUnverified())) return Mono.empty();
        return unverifiedGuests
                .findById(session.getGuestId())
                .filter(
                        guest ->
                                Boolean.TRUE.equals(guest.getEnabled())
                                        && share.getProjectId().equals(guest.getProjectId())
                                        && guest.getExpiresAt() > System.currentTimeMillis()
                                        && session.getExpiresAt() <= guest.getExpiresAt())
                .map(guest -> identity(guest, session.getExpiresAt()));
    }

    public Mono<ReviewChallengeResponse> challenge(
            String token, ReviewChallengeRequest input, String cookie) {
        return requireScope(token, input.revisionId(), input.accessKey())
                .then(
                        Mono.defer(
                                () ->
                                        notifications.available()
                                                ? Mono.empty()
                                                : Mono.error(
                                                        SmtpNotificationGateway.unavailable())))
                .then(
                        work.call(
                                () -> {
                                    return Observation.createNotStarted(
                                                    "prototype.review.prepare", observations)
                                            .observe(
                                                    () -> {
                                                        String code = PrototypeReviewKeys.code();
                                                        return new PreparedCode(
                                                                code, PASSWORDS.encode(code));
                                                    });
                                }))
                .flatMap(
                        code ->
                                lockedScope(token, input.revisionId(), input.accessKey())
                                        .flatMap(
                                                share ->
                                                        prepareChallenge(
                                                                share, input, cookie, code))
                                        .as(transactions::transactional)
                                        // pending 行和限频先提交。取消/失败不进入delivered，永远不能验证。
                                        .flatMap(
                                                prepared ->
                                                        notifications
                                                                .sendVerification(
                                                                        prepared.entity()
                                                                                .getEmail(),
                                                                        code.code())
                                                                .then(
                                                                        markDelivered(
                                                                                token,
                                                                                input,
                                                                                prepared.entity()))
                                                                .thenReturn(
                                                                        new ReviewChallengeResponse(
                                                                                prepared.entity()
                                                                                        .getId(),
                                                                                prepared.entity()
                                                                                        .getExpiresAt(),
                                                                                prepared
                                                                                        .resendAfter()))))
                .checkpoint("prototype-hub/review/challenge");
    }

    private Mono<PreparedChallenge> prepareChallenge(
            PrototypeShareEntity share,
            ReviewChallengeRequest input,
            String cookie,
            PreparedCode code) {
        long now = System.currentTimeMillis();
        String email = PrototypeReviewKeys.email(input.email());
        return guestEnabled(share.getProjectId(), email)
                .flatMap(enabled -> enabled ? Mono.empty() : Mono.error(denied()))
                .then(useRate(share.getProjectId(), email, now))
                .then(
                        challenges
                                .createDelete()
                                .where(
                                        PrototypeReviewChallengeEntity::getProjectId,
                                        share.getProjectId())
                                .and(PrototypeReviewChallengeEntity::getEmail, email)
                                .execute())
                .then(
                        Mono.defer(
                                () -> {
                                    PrototypeReviewChallengeEntity challenge =
                                            new PrototypeReviewChallengeEntity();
                                    challenge.setId(PrototypeReviewKeys.random());
                                    challenge.setProjectId(share.getProjectId());
                                    challenge.setVersionId(share.getVersionId());
                                    challenge.setShareId(share.getId());
                                    challenge.setRevisionId(input.revisionId());
                                    challenge.setConfigVersion(version(share));
                                    challenge.setShareGeneration(
                                            PrototypeReviewKeys.digest(share.getAccessKey()));
                                    challenge.setCookieHash(PrototypeReviewKeys.digest(cookie));
                                    challenge.setEmail(email);
                                    challenge.setDisplayName(input.displayName().strip());
                                    challenge.setCompany(
                                            input.company() == null || input.company().isBlank()
                                                    ? null
                                                    : input.company().strip());
                                    challenge.setCodeHash(code.hash());
                                    challenge.setDelivered(false);
                                    challenge.setAttempts(0);
                                    challenge.setConsumed(false);
                                    challenge.setCreateTime(now);
                                    challenge.setExpiresAt(now + CODE_TTL);
                                    return challenges
                                            .insert(challenge)
                                            .thenReturn(
                                                    new PreparedChallenge(challenge, now + 60_000));
                                }));
    }

    private Mono<Void> markDelivered(
            String token, ReviewChallengeRequest input, PrototypeReviewChallengeEntity pending) {
        return lockedScope(token, input.revisionId(), input.accessKey())
                .filter(
                        share ->
                                bound(
                                        pending.getShareId(),
                                        pending.getProjectId(),
                                        pending.getVersionId(),
                                        pending.getRevisionId(),
                                        pending.getConfigVersion(),
                                        pending.getShareGeneration(),
                                        share,
                                        input.revisionId()))
                .switchIfEmpty(Mono.error(PrototypeReviewService::denied))
                .flatMap(
                        share ->
                                guestEnabled(share.getProjectId(), pending.getEmail())
                                        .flatMap(
                                                enabled ->
                                                        enabled
                                                                ? Mono.empty()
                                                                : Mono.error(denied())))
                .then(
                        Mono.defer(
                                () ->
                                        challenges
                                                .createUpdate()
                                                .set(
                                                        PrototypeReviewChallengeEntity
                                                                ::getDelivered,
                                                        true)
                                                .where(
                                                        PrototypeReviewChallengeEntity::getId,
                                                        pending.getId())
                                                .and(
                                                        PrototypeReviewChallengeEntity::getCodeHash,
                                                        pending.getCodeHash())
                                                .and(
                                                        PrototypeReviewChallengeEntity::getConsumed,
                                                        false)
                                                .gt(
                                                        PrototypeReviewChallengeEntity
                                                                ::getExpiresAt,
                                                        System.currentTimeMillis())
                                                .execute()))
                .flatMap(count -> count == 1 ? Mono.empty() : Mono.error(codeInvalid()))
                .as(transactions::transactional)
                .then();
    }

    public Mono<Verified> verify(String token, ReviewVerifyRequest input, String cookie) {
        return requireScope(token, input.revisionId(), input.accessKey())
                .flatMap(
                        share ->
                                challenges
                                        .createQuery()
                                        .where(
                                                PrototypeReviewChallengeEntity::getId,
                                                input.challengeId())
                                        .fetch()
                                        .singleOrEmpty()
                                        .filter(
                                                challenge ->
                                                        validChallenge(
                                                                challenge,
                                                                share,
                                                                input.revisionId(),
                                                                cookie,
                                                                System.currentTimeMillis()))
                                        .switchIfEmpty(
                                                Mono.error(PrototypeReviewService::codeInvalid)))
                .flatMap(
                        before ->
                                work.call(
                                                () ->
                                                        Observation.createNotStarted(
                                                                        "prototype.review.verify",
                                                                        observations)
                                                                .observe(
                                                                        () ->
                                                                                PASSWORDS.matches(
                                                                                        input
                                                                                                .code(),
                                                                                        before
                                                                                                .getCodeHash())))
                                        .flatMap(
                                                correct ->
                                                        lockedScope(
                                                                        token,
                                                                        input.revisionId(),
                                                                        input.accessKey())
                                                                .flatMap(
                                                                        share ->
                                                                                consume(
                                                                                        share,
                                                                                        before,
                                                                                        correct,
                                                                                        cookie))
                                                                .as(transactions::transactional)))
                // false 返回值经过事务提交后才转错误，五次错误不会被异常回滚。
                .flatMap(
                        result ->
                                result.accepted()
                                        ? Mono.just(result.verified())
                                        : Mono.error(codeInvalid()))
                .checkpoint("prototype-hub/review/verify");
    }

    private Mono<ConsumeResult> consume(
            PrototypeShareEntity share,
            PrototypeReviewChallengeEntity before,
            boolean correct,
            String cookie) {
        return challenges
                .createQuery()
                .where(PrototypeReviewChallengeEntity::getId, before.getId())
                .forUpdate()
                .fetch()
                .singleOrEmpty()
                .filter(
                        current ->
                                Objects.equals(current.getCodeHash(), before.getCodeHash())
                                        && validChallenge(
                                                current,
                                                share,
                                                before.getRevisionId(),
                                                cookie,
                                                System.currentTimeMillis()))
                // 与管理启停共享项目锁；恢复访客也不能让被删除的验证码重新出现。
                .filterWhen(current -> guestEnabled(share.getProjectId(), current.getEmail()))
                .flatMap(
                        current -> {
                            if (!correct)
                                return challenges
                                        .createUpdate()
                                        .set(
                                                PrototypeReviewChallengeEntity::getAttempts,
                                                current.getAttempts() + 1)
                                        .where(
                                                PrototypeReviewChallengeEntity::getId,
                                                current.getId())
                                        .execute()
                                        .thenReturn(new ConsumeResult(false, null));
                            return challenges
                                    .createUpdate()
                                    .set(PrototypeReviewChallengeEntity::getConsumed, true)
                                    .where(PrototypeReviewChallengeEntity::getId, current.getId())
                                    .execute()
                                    .then(issueSession(share, current, cookie))
                                    .map(verified -> new ConsumeResult(true, verified));
                        })
                .defaultIfEmpty(new ConsumeResult(false, null));
    }

    private Mono<Verified> issueSession(
            PrototypeShareEntity share,
            PrototypeReviewChallengeEntity challenge,
            String oldCookie) {
        long now = System.currentTimeMillis();
        String guestId = PrototypeReviewKeys.guest(share.getProjectId(), challenge.getEmail());
        return guests.findById(guestId)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    PrototypeReviewGuestEntity guest =
                                            new PrototypeReviewGuestEntity();
                                    guest.setId(guestId);
                                    guest.setProjectId(share.getProjectId());
                                    guest.setEmail(challenge.getEmail());
                                    guest.setCreateTime(now);
                                    guest.setEnabled(true);
                                    guest.setConfigVersion(0L);
                                    return Mono.just(guest);
                                }))
                .flatMap(
                        guest -> {
                            guest.setDisplayName(challenge.getDisplayName());
                            guest.setCompany(challenge.getCompany());
                            guest.setModifyTime(now);
                            String raw = PrototypeReviewKeys.random();
                            PrototypeReviewSessionEntity session =
                                    newSession(share, challenge.getRevisionId(), guestId, raw, now);
                            session.setVerificationLevel("email");
                            return guests.save(guest)
                                    // EasyORM save默认忽略null；自述公司清空必须显式写NULL。
                                    .then(
                                            guest.getCompany() == null
                                                    ? guests.createUpdate()
                                                            .setNull(
                                                                    PrototypeReviewGuestEntity
                                                                            ::getCompany)
                                                            .where(
                                                                    PrototypeReviewGuestEntity
                                                                            ::getId,
                                                                    guestId)
                                                            .execute()
                                                            .then()
                                                    : Mono.empty())
                                    .then(
                                            sessions.deleteById(
                                                    PrototypeReviewKeys.digest(oldCookie)))
                                    .then(sessions.insert(session))
                                    .thenReturn(
                                            new Verified(
                                                    raw,
                                                    context(
                                                            share,
                                                            challenge.getRevisionId(),
                                                            raw,
                                                            identity(
                                                                    guest,
                                                                    session.getExpiresAt()))));
                        });
    }

    private static PrototypeReviewSessionEntity newSession(
            PrototypeShareEntity share, String revisionId, String guestId, String raw, long now) {
        var session = new PrototypeReviewSessionEntity();
        session.setId(PrototypeReviewKeys.digest(raw));
        session.setGuestId(guestId);
        session.setProjectId(share.getProjectId());
        session.setVersionId(share.getVersionId());
        session.setShareId(share.getId());
        session.setRevisionId(revisionId);
        session.setConfigVersion(version(share));
        session.setShareGeneration(PrototypeReviewKeys.digest(share.getAccessKey()));
        session.setCreateTime(now);
        session.setExpiresAt(now + SESSION_TTL);
        return session;
    }

    public Mono<Void> logout(String token, String revisionId, String key, String cookie) {
        return lockedScope(token, revisionId, key)
                .then(
                        sessions.createDelete()
                                .where(
                                        PrototypeReviewSessionEntity::getId,
                                        PrototypeReviewKeys.digest(cookie))
                                .execute())
                .as(transactions::transactional)
                .then();
    }

    private Mono<PrototypeShareEntity> requireScope(String token, String revisionId, String key) {
        return shares.requireActive(token)
                .filter(
                        share ->
                                Boolean.TRUE.equals(share.getReviewEnabled())
                                        && PrototypeReviewKeys.matches(
                                                PrototypeShareKeys.derive(share, revisionId), key))
                .switchIfEmpty(Mono.error(PrototypeReviewService::denied))
                .flatMap(
                        share ->
                                shares.requireRevisionAccess(token, key, revisionId)
                                        .thenReturn(share));
    }

    private Mono<PrototypeShareEntity> lockedScope(String token, String revisionId, String key) {
        return requireScope(token, revisionId, key)
                .flatMap(
                        before ->
                                access.lockProject(before.getProjectId())
                                        .then(access.lockVersion(before.getVersionId()))
                                        .then(requireScope(token, revisionId, key)));
    }

    /** 无访客行表示尚未完成首次验证；历史行的空状态继续视为启用。 */
    private Mono<Boolean> guestEnabled(String projectId, String email) {
        return guests.findById(PrototypeReviewKeys.guest(projectId, email))
                .map(guest -> !Boolean.FALSE.equals(guest.getEnabled()))
                .defaultIfEmpty(true);
    }

    private Mono<Void> useRate(String projectId, String email, long now) {
        return rate(projectId, "project", 60, false, now)
                .then(rate(projectId, PrototypeReviewKeys.guest(projectId, email), 5, true, now));
    }

    private Mono<Void> rate(String projectId, String scope, int limit, boolean cooldown, long now) {
        String id = PrototypeReviewKeys.digest("review-rate-v1:" + projectId + ":" + scope);
        return rates.findById(id)
                .switchIfEmpty(
                        Mono.fromSupplier(
                                () -> {
                                    PrototypeReviewRateEntity row = new PrototypeReviewRateEntity();
                                    row.setId(id);
                                    row.setProjectId(projectId);
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
                                return Mono.error(rateLimited());
                            row.setWindowStart(window);
                            row.setSendCount(count + 1);
                            row.setNextAllowedAt(cooldown ? now + 60_000 : 0L);
                            row.setExpiresAt(Math.max(window + HOUR, row.getNextAllowedAt()));
                            return rates.save(row).then();
                        });
    }

    public Mono<Void> cleanupExpired() {
        // Spring只组装一次调度Publisher；查询和cutoff必须在每次订阅时重新创建。
        return Mono.defer(this::cleanupExpiredBatch);
    }

    private Mono<Void> cleanupExpiredBatch() {
        long now = System.currentTimeMillis();
        return challenges
                .createQuery()
                .select(PrototypeReviewChallengeEntity::getId)
                .lte(PrototypeReviewChallengeEntity::getExpiresAt, now)
                .orderBy(
                        SortOrder.asc(PrototypeReviewChallengeEntity::getExpiresAt),
                        SortOrder.asc(PrototypeReviewChallengeEntity::getId))
                .paging(0, 50)
                .fetch()
                .map(PrototypeReviewChallengeEntity::getId)
                .collectList()
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.empty()
                                        : challenges
                                                .createDelete()
                                                .in(PrototypeReviewChallengeEntity::getId, ids)
                                                .lte(
                                                        PrototypeReviewChallengeEntity
                                                                ::getExpiresAt,
                                                        now)
                                                .execute()
                                                .then())
                .then(
                        sessions.createQuery()
                                .select(PrototypeReviewSessionEntity::getId)
                                .lte(PrototypeReviewSessionEntity::getExpiresAt, now)
                                .orderBy(
                                        SortOrder.asc(PrototypeReviewSessionEntity::getExpiresAt),
                                        SortOrder.asc(PrototypeReviewSessionEntity::getId))
                                .paging(0, 50)
                                .fetch()
                                .map(PrototypeReviewSessionEntity::getId)
                                .collectList()
                                .flatMap(
                                        ids ->
                                                ids.isEmpty()
                                                        ? Mono.empty()
                                                        : sessions.createDelete()
                                                                .in(
                                                                        PrototypeReviewSessionEntity
                                                                                ::getId,
                                                                        ids)
                                                                .lte(
                                                                        PrototypeReviewSessionEntity
                                                                                ::getExpiresAt,
                                                                        now)
                                                                .execute()
                                                                .then()))
                // 单条条件DELETE由数据库串行化：旧候选不能删除已被另一实例续期的限频窗口。
                .then(
                        rates.createQuery()
                                .select(PrototypeReviewRateEntity::getId)
                                .lte(PrototypeReviewRateEntity::getExpiresAt, now)
                                .orderBy(
                                        SortOrder.asc(PrototypeReviewRateEntity::getExpiresAt),
                                        SortOrder.asc(PrototypeReviewRateEntity::getId))
                                .paging(0, 50)
                                .fetch()
                                .map(PrototypeReviewRateEntity::getId)
                                .collectList()
                                .flatMap(
                                        ids ->
                                                ids.isEmpty()
                                                        ? Mono.empty()
                                                        : rates.createDelete()
                                                                .in(
                                                                        PrototypeReviewRateEntity
                                                                                ::getId,
                                                                        ids)
                                                                .lte(
                                                                        PrototypeReviewRateEntity
                                                                                ::getExpiresAt,
                                                                        now)
                                                                .execute()
                                                                .then()))
                .then(cleanupUnverified(now));
    }

    private Mono<Void> cleanupUnverified(long now) {
        // 每次只取固定到期的50行；身份从不续期，残留会话也由自身到期条件和独立批次拒绝/清理。
        return unverifiedGuests
                .createQuery()
                .select(PrototypeReviewUnverifiedGuestEntity::getId)
                .lte(PrototypeReviewUnverifiedGuestEntity::getExpiresAt, now)
                .orderBy(
                        SortOrder.asc(PrototypeReviewUnverifiedGuestEntity::getExpiresAt),
                        SortOrder.asc(PrototypeReviewUnverifiedGuestEntity::getId))
                .paging(0, 50)
                .fetch()
                .map(PrototypeReviewUnverifiedGuestEntity::getId)
                .collectList()
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.empty()
                                        : unverifiedGuests
                                                .createDelete()
                                                .in(
                                                        PrototypeReviewUnverifiedGuestEntity::getId,
                                                        ids)
                                                .lte(
                                                        PrototypeReviewUnverifiedGuestEntity
                                                                ::getExpiresAt,
                                                        now)
                                                .execute()
                                                .then());
    }

    public Mono<Void> purgeVersion(String id) {
        return challenges
                .createDelete()
                .where(PrototypeReviewChallengeEntity::getVersionId, id)
                .execute()
                .then(
                        sessions.createDelete()
                                .where(PrototypeReviewSessionEntity::getVersionId, id)
                                .execute())
                .then();
    }

    public Mono<Void> purgeProject(String id) {
        return challenges
                .createDelete()
                .where(PrototypeReviewChallengeEntity::getProjectId, id)
                .execute()
                .then(
                        sessions.createDelete()
                                .where(PrototypeReviewSessionEntity::getProjectId, id)
                                .execute())
                .then(
                        rates.createDelete()
                                .where(PrototypeReviewRateEntity::getProjectId, id)
                                .execute())
                .then(
                        guests.createDelete()
                                .where(PrototypeReviewGuestEntity::getProjectId, id)
                                .execute())
                .then(
                        unverifiedGuests
                                .createDelete()
                                .where(PrototypeReviewUnverifiedGuestEntity::getProjectId, id)
                                .execute())
                .then();
    }

    private ReviewContextResponse context(
            PrototypeShareEntity share, String revisionId, String cookie, ReviewIdentity identity) {
        return new ReviewContextResponse(
                revisionId,
                true,
                Boolean.TRUE.equals(share.getReviewAllowUnverified()),
                notifications.available(),
                PrototypeReviewKeys.csrf(cookie),
                identity);
    }

    private static ReviewIdentity identity(PrototypeReviewGuestEntity guest, long expiresAt) {
        return new ReviewIdentity(
                guest.getId(),
                guest.getDisplayName(),
                guest.getCompany(),
                PrototypeReviewKeys.masked(guest.getEmail()),
                "email",
                expiresAt);
    }

    private static ReviewIdentity identity(
            PrototypeReviewUnverifiedGuestEntity guest, long expiresAt) {
        return new ReviewIdentity(
                guest.getId(),
                guest.getDisplayName(),
                guest.getCompany(),
                null,
                "unverified",
                expiresAt);
    }

    private static boolean emailLevel(PrototypeReviewSessionEntity session) {
        return session.getVerificationLevel() == null
                || "email".equals(session.getVerificationLevel());
    }

    private static boolean knownLevel(PrototypeReviewSessionEntity session) {
        return emailLevel(session) || "unverified".equals(session.getVerificationLevel());
    }

    private static long version(PrototypeShareEntity share) {
        return share.getConfigVersion() == null ? 0 : share.getConfigVersion();
    }

    private static boolean bound(
            String shareId,
            String projectId,
            String versionId,
            String revisionId,
            Long configVersion,
            String generation,
            PrototypeShareEntity share,
            String selected) {
        return Objects.equals(shareId, share.getId())
                && Objects.equals(projectId, share.getProjectId())
                && Objects.equals(versionId, share.getVersionId())
                && Objects.equals(revisionId, selected)
                && Objects.equals(configVersion, version(share))
                && PrototypeReviewKeys.matches(
                        generation, PrototypeReviewKeys.digest(share.getAccessKey()));
    }

    static boolean validSession(
            PrototypeReviewSessionEntity session,
            PrototypeShareEntity share,
            String revisionId,
            long now) {
        return knownLevel(session)
                && (emailLevel(session) || Boolean.TRUE.equals(share.getReviewAllowUnverified()))
                && session.getExpiresAt() > now
                && sessionBound(session, share, revisionId);
    }

    private static boolean sessionBound(
            PrototypeReviewSessionEntity session, PrototypeShareEntity share, String revisionId) {
        return bound(
                session.getShareId(),
                session.getProjectId(),
                session.getVersionId(),
                session.getRevisionId(),
                session.getConfigVersion(),
                session.getShareGeneration(),
                share,
                revisionId);
    }

    static boolean validChallenge(
            PrototypeReviewChallengeEntity challenge,
            PrototypeShareEntity share,
            String revisionId,
            String cookie,
            long now) {
        return Boolean.TRUE.equals(challenge.getDelivered())
                && !Boolean.TRUE.equals(challenge.getConsumed())
                && challenge.getAttempts() < 5
                && challenge.getExpiresAt() > now
                && PrototypeReviewKeys.matches(
                        challenge.getCookieHash(), PrototypeReviewKeys.digest(cookie))
                && bound(
                        challenge.getShareId(),
                        challenge.getProjectId(),
                        challenge.getVersionId(),
                        challenge.getRevisionId(),
                        challenge.getConfigVersion(),
                        challenge.getShareGeneration(),
                        share,
                        revisionId);
    }

    public static PrototypeHubException denied() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.review_denied", "PROTOTYPE_HUB_REVIEW_DENIED");
    }

    public static PrototypeHubException codeInvalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.review_code_invalid", "PROTOTYPE_HUB_REVIEW_CODE_INVALID");
    }

    private static PrototypeHubException rateLimited() {
        return new PrototypeHubException(
                "error.prototype-hub.review_rate_limited",
                "PROTOTYPE_HUB_REVIEW_RATE_LIMITED",
                429);
    }

    public record Verified(String cookie, ReviewContextResponse context) {}

    private record PreparedCode(String code, String hash) {}

    private record PreparedChallenge(PrototypeReviewChallengeEntity entity, long resendAfter) {}

    private record ConsumeResult(boolean accepted, Verified verified) {}
}
