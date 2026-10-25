package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.SharePasswordAction;
import com.gaiys.proto.prototypehub.enums.ShareRevisionMode;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeShareKeys;
import com.gaiys.proto.prototypehub.service.notification.NotificationGateway;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.ReviewPolicyRequest;
import com.gaiys.proto.prototypehub.web.request.ShareCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ShareEnabledRequest;
import com.gaiys.proto.prototypehub.web.request.ShareUpdateRequest;
import com.gaiys.proto.prototypehub.web.response.PublicShareResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewPolicyResponse;
import com.gaiys.proto.prototypehub.web.urls.PrototypeUrls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.AllArgsConstructor;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 分享配置、生命周期和绑定单个快照的公开授权。
 *
 * <p>管理写入按项目、版本顺序持锁并再次授权，配置版本执行 CAS；BCrypt 在 FileWork 隔离线程计算。 每次管理变更轮换服务端密钥，已授予的快照凭据不会因恢复分享而复活。
 */
@Service
@AllArgsConstructor
public class PrototypeShareService
        extends GenericReactiveCrudService<PrototypeShareEntity, String> {
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);
    private final PrototypeProjectService projects;
    private final PrototypeVersionService versions;
    private final FileWork work;
    private final TransactionalOperator transactions;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final NotificationGateway notifications;

    public Mono<ReviewPolicyResponse> reviewPolicy(String id) {
        return access.requireShare(id, PrototypeProjectAccessService.Action.SHARE)
                .map(this::reviewPolicy);
    }

    public Mono<ReviewPolicyResponse> reviewPolicy(String id, ReviewPolicyRequest input) {
        return mutate(
                        id,
                        input.expectedVersion(),
                        share -> {
                            share.setReviewEnabled(input.enabled());
                            // 已发布的旧客户端只传enabled；缺失新字段不得关闭管理员已开启的策略。
                            if (input.allowUnverified() != null)
                                share.setReviewAllowUnverified(input.allowUnverified());
                        })
                .map(this::reviewPolicy);
    }

    private ReviewPolicyResponse reviewPolicy(PrototypeShareEntity share) {
        return new ReviewPolicyResponse(
                Boolean.TRUE.equals(share.getReviewEnabled()),
                Boolean.TRUE.equals(share.getReviewAllowUnverified()),
                notifications.available(),
                share.getConfigVersion() == null ? 0 : share.getConfigVersion());
    }

    public Flux<PrototypeShareEntity> findByVersionId(String id) {
        return createQuery().where(PrototypeShareEntity::getVersionId, id).fetch();
    }

    /** 父资源软删保留分享引用，但终态撤销并更换密钥，后续不能通过恢复父行复活旧链接。 */
    public Mono<Integer> revokeByVersionId(String id) {
        return createUpdate()
                .set(PrototypeShareEntity::getEnabled, false)
                .set(PrototypeShareEntity::getRevoked, true)
                .set(PrototypeShareEntity::getAccessKey, random())
                .where(PrototypeShareEntity::getVersionId, id)
                .execute();
    }

    public Mono<Integer> revokeByProjectId(String id) {
        return createUpdate()
                .set(PrototypeShareEntity::getEnabled, false)
                .set(PrototypeShareEntity::getRevoked, true)
                .set(PrototypeShareEntity::getAccessKey, random())
                .where(PrototypeShareEntity::getProjectId, id)
                .execute();
    }

    /** 管理侧仅校验已知密码，不授予公开访问凭据；启停和有效期不影响密码确认。 */
    public Mono<Boolean> verifyPassword(String id, String password) {
        return access.requireShare(id, PrototypeProjectAccessService.Action.SHARE)
                .flatMap(
                        share -> {
                            if (share.getPasswordHash() == null) return Mono.just(true);
                            if (password == null || password.isEmpty()) return Mono.just(false);
                            return work.call(
                                    () -> PASSWORDS.matches(password, share.getPasswordHash()));
                        });
    }

    public Mono<PrototypeShareEntity> createShare(
            String versionId, ShareCreateRequest input, String actor) {
        return access.requireVersion(versionId, PrototypeProjectAccessService.Action.SHARE)
                .flatMap(
                        version ->
                                revisions
                                        .current(version)
                                        .then(work.call(() -> buildShare(version, input, actor))))
                .flatMap(share -> persistShare(share, input.revisionId()))
                .checkpoint("prototype-hub/share/create");
    }

    private PrototypeShareEntity buildShare(
            PrototypeVersionEntity version, ShareCreateRequest input, String actor) {
        ShareRevisionMode mode =
                input.revisionMode() == null ? ShareRevisionMode.fixed : input.revisionMode();
        validateConfig(input.description(), input.expiresAt(), mode, input.revisionId());
        PrototypeShareEntity share = new PrototypeShareEntity();
        share.setId(random());
        share.setProjectId(version.getProjectId());
        share.setVersionId(version.getId());
        share.setToken(random());
        share.setAccessKey(random());
        share.setEnabled(true);
        share.setReviewEnabled(false);
        share.setReviewAllowUnverified(false);
        share.setRevoked(false);
        share.setConfigVersion(1L);
        share.setRevisionMode(mode.name());
        share.setCreatorId(actor);
        share.setCreateTime(System.currentTimeMillis());
        share.setExpiresAt(input.expiresAt());
        share.setDescription(input.description());
        if (input.password() != null && !input.password().isBlank())
            share.setPasswordHash(encodePassword(input.password()));
        return share;
    }

    private Mono<PrototypeShareEntity> persistShare(PrototypeShareEntity share, String revisionId) {
        return access.lockProject(share.getProjectId())
                .then(access.lockVersion(share.getVersionId()))
                .then(
                        access.requireVersion(
                                share.getVersionId(), PrototypeProjectAccessService.Action.SHARE))
                .filter(version -> Objects.equals(version.getProjectId(), share.getProjectId()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")))
                .flatMap(
                        version -> {
                            validateConfig(
                                    share.getDescription(),
                                    share.getExpiresAt(),
                                    mode(share),
                                    revisionId);
                            // 锁内解析 current；密码计算前的指针不能作为提交时的最新内容。
                            return targetRevision(version, mode(share), revisionId);
                        })
                .flatMap(
                        revision -> {
                            share.setRevisionId(
                                    mode(share) == ShareRevisionMode.fixed
                                            ? revision.getId()
                                            : null);
                            return insert(share).thenReturn(share);
                        })
                .as(transactions::transactional);
    }

    /** 完整配置替换；先计算新密码，再在锁内核对范围、版本与所有待写配置。 */
    public Mono<PrototypeShareEntity> updateShare(String id, ShareUpdateRequest input) {
        return access.requireShare(id, PrototypeProjectAccessService.Action.SHARE)
                .then(work.call(() -> preparePassword(input)))
                .flatMap(
                        password ->
                                lockedShare(id)
                                        .flatMap(share -> applyUpdate(share, input, password))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/share/update");
    }

    private Mono<PrototypeShareEntity> applyUpdate(
            PrototypeShareEntity share, ShareUpdateRequest input, PreparedPassword password) {
        checkMutable(share, input.expectedVersion());
        validateConfig(
                input.description(), input.expiresAt(), input.revisionMode(), input.revisionId());
        String revisionId = input.revisionId();
        if (input.revisionMode() == ShareRevisionMode.fixed && revisionId == null) {
            // 说明、密码等编辑不会隐式移动既有固定快照；latest 改 fixed 必须显式指定。
            if (mode(share) == ShareRevisionMode.latest) return Mono.error(configInvalid());
            revisionId = share.getRevisionId();
            if (revisionId == null) return Mono.error(PrototypeRevisionService.migrationRequired());
        }
        String targetId = revisionId;
        return access.correlatedVersion(share)
                .flatMap(version -> targetRevision(version, input.revisionMode(), targetId))
                .flatMap(
                        revision -> {
                            share.setDescription(input.description());
                            share.setExpiresAt(input.expiresAt());
                            share.setRevisionMode(input.revisionMode().name());
                            share.setRevisionId(
                                    input.revisionMode() == ShareRevisionMode.fixed
                                            ? revision.getId()
                                            : null);
                            if (input.passwordAction() != SharePasswordAction.keep)
                                share.setPasswordHash(password.hash());
                            return persistMutation(share);
                        });
    }

    private PreparedPassword preparePassword(ShareUpdateRequest input) {
        validateConfig(
                input.description(), input.expiresAt(), input.revisionMode(), input.revisionId());
        if (input.passwordAction() == null) throw configInvalid();
        if (input.passwordAction() != SharePasswordAction.replace) {
            if (input.password() != null) throw configInvalid();
            return new PreparedPassword(null);
        }
        return new PreparedPassword(encodePassword(input.password()));
    }

    public Mono<PrototypeShareEntity> setEnabled(String id, ShareEnabledRequest input) {
        return Mono.defer(
                        () -> {
                            if (input.enabled() == null) return Mono.error(configInvalid());
                            return mutate(
                                    id,
                                    input.expectedVersion(),
                                    share -> share.setEnabled(input.enabled()));
                        })
                .checkpoint("prototype-hub/share/enabled");
    }

    public Mono<PrototypeShareEntity> revoke(String id, Long expectedVersion) {
        return mutate(
                        id,
                        expectedVersion,
                        share -> {
                            share.setRevoked(true);
                            share.setEnabled(false);
                        })
                .checkpoint("prototype-hub/share/revoke");
    }

    public Mono<PrototypeShareEntity> rotateLink(String id, Long expectedVersion) {
        return mutate(id, expectedVersion, share -> share.setToken(random()))
                .checkpoint("prototype-hub/share/rotate-link");
    }

    private Mono<PrototypeShareEntity> mutate(
            String id, Long expectedVersion, Consumer<PrototypeShareEntity> change) {
        return lockedShare(id)
                .flatMap(
                        share -> {
                            checkMutable(share, expectedVersion);
                            change.accept(share);
                            return persistMutation(share);
                        })
                .as(transactions::transactional);
    }

    /** 初读只用于定位父锁；持锁后重新读取分享和项目范围，禁止用旧授权结果执行写入。 */
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
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "share"))));
    }

    private static void checkMutable(PrototypeShareEntity share, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw configInvalid();
        long version = share.getConfigVersion() == null ? 0 : share.getConfigVersion();
        if (version != expectedVersion || version == Long.MAX_VALUE) throw conflict();
        if (Boolean.TRUE.equals(share.getRevoked()))
            throw new PrototypeHubException(
                    "error.prototype-hub.share_revoked", "PROTOTYPE_HUB_SHARE_REVOKED", 409);
    }

    /** 父锁维持跨实例串行化，额外版本条件使陈旧客户端不能覆盖更新；不修改创建审计字段。 */
    private Mono<PrototypeShareEntity> persistMutation(PrototypeShareEntity share) {
        Long previous = share.getConfigVersion();
        share.setConfigVersion(previous == null ? 1 : previous + 1);
        share.setAccessKey(random());
        var update =
                createUpdate()
                        .set(PrototypeShareEntity::getToken, share.getToken())
                        .set(PrototypeShareEntity::getAccessKey, share.getAccessKey())
                        .set(PrototypeShareEntity::getEnabled, share.getEnabled())
                        .set(
                                PrototypeShareEntity::getReviewEnabled,
                                Boolean.TRUE.equals(share.getReviewEnabled()))
                        .set(
                                PrototypeShareEntity::getReviewAllowUnverified,
                                Boolean.TRUE.equals(share.getReviewAllowUnverified()))
                        .set(
                                PrototypeShareEntity::getRevoked,
                                Boolean.TRUE.equals(share.getRevoked()))
                        .set(PrototypeShareEntity::getConfigVersion, share.getConfigVersion())
                        .set(PrototypeShareEntity::getRevisionMode, mode(share).name());
        if (share.getDescription() == null) update.setNull(PrototypeShareEntity::getDescription);
        else update.set(PrototypeShareEntity::getDescription, share.getDescription());
        if (share.getExpiresAt() == null) update.setNull(PrototypeShareEntity::getExpiresAt);
        else update.set(PrototypeShareEntity::getExpiresAt, share.getExpiresAt());
        if (share.getPasswordHash() == null) update.setNull(PrototypeShareEntity::getPasswordHash);
        else update.set(PrototypeShareEntity::getPasswordHash, share.getPasswordHash());
        if (share.getRevisionId() == null) update.setNull(PrototypeShareEntity::getRevisionId);
        else update.set(PrototypeShareEntity::getRevisionId, share.getRevisionId());
        var condition =
                update.where(PrototypeShareEntity::getId, share.getId())
                        .and(PrototypeShareEntity::getProjectId, share.getProjectId())
                        .and(PrototypeShareEntity::getVersionId, share.getVersionId());
        if (previous == null) condition.isNull(PrototypeShareEntity::getConfigVersion);
        else condition.and(PrototypeShareEntity::getConfigVersion, previous);
        return condition
                .execute()
                .flatMap(rows -> rows == 1 ? Mono.just(share) : Mono.error(conflict()));
    }

    private Mono<PrototypeRevisionEntity> targetRevision(
            PrototypeVersionEntity version, ShareRevisionMode mode, String revisionId) {
        return mode == ShareRevisionMode.latest || revisionId == null
                ? revisions.current(version)
                : revisions.require(version, revisionId);
    }

    public Mono<PrototypeShareEntity> requireActive(String token) {
        return createQuery()
                .where(PrototypeShareEntity::getToken, token)
                // 后续可能进入邮箱BCrypt或文件工作，等待正常完成并归还查询连接。
                .fetch()
                .singleOrEmpty()
                .filter(
                        s ->
                                Boolean.TRUE.equals(s.getEnabled())
                                        && !Boolean.TRUE.equals(s.getRevoked())
                                        && s.getAccessKey() != null
                                        && !s.getAccessKey().isBlank()
                                        && ((mode(s) == ShareRevisionMode.fixed
                                                        && s.getRevisionId() != null)
                                                || (mode(s) == ShareRevisionMode.latest
                                                        && s.getRevisionId() == null))
                                        && (s.getExpiresAt() == null
                                                || s.getExpiresAt() > System.currentTimeMillis()))
                .switchIfEmpty(Mono.error(PrototypeShareService::unavailable));
    }

    /** 无快照旧 URL 仅服务固定模式，兼容旧原始密钥和新固定快照凭据；latest 必须使用显式快照 URL。 */
    public Mono<PrototypeShareEntity> requireAccess(String token, String key) {
        return requireActive(token)
                .filter(
                        share ->
                                mode(share) == ShareRevisionMode.fixed
                                        && (matches(share.getAccessKey(), key)
                                                || matches(
                                                        PrototypeShareKeys.derive(
                                                                share, share.getRevisionId()),
                                                        key)))
                .switchIfEmpty(Mono.error(PrototypeShareService::accessDenied))
                .flatMap(share -> requireRevision(share).thenReturn(share));
    }

    /** 每个资源请求校验当前分享状态和同一快照凭据；latest 不重新解析 current，避免混用不同发布的资源。 */
    public Mono<PrototypeRevisionEntity> requireRevisionAccess(
            String token, String key, String revisionId) {
        return requireActive(token)
                .filter(
                        share ->
                                revisionId != null
                                        && (mode(share) == ShareRevisionMode.latest
                                                || Objects.equals(
                                                        share.getRevisionId(), revisionId))
                                        && matches(
                                                PrototypeShareKeys.derive(share, revisionId), key))
                .switchIfEmpty(Mono.error(PrototypeShareService::accessDenied))
                .flatMap(
                        share ->
                                access.correlatedVersion(share)
                                        .flatMap(version -> revisions.require(version, revisionId)))
                .onErrorMap(
                        PrototypeHubException.class,
                        error -> error.getStatus() == 404 ? unavailable() : error);
    }

    public Mono<PublicShareResponse> getPublicInfo(String token, String password) {
        return requireActive(token)
                .flatMap(
                        share ->
                                requireRevision(share)
                                        .flatMap(
                                                revision -> {
                                                    if (share.getPasswordHash() == null)
                                                        return info(share, revision, true);
                                                    if (password == null)
                                                        return info(share, revision, false);
                                                    // BCrypt 验证和凭据派生必须使用同一次读取的
                                                    // hash/secret；不得验证旧密码后重读新密钥。
                                                    return work.call(
                                                                    () ->
                                                                            PASSWORDS.matches(
                                                                                    password,
                                                                                    share
                                                                                            .getPasswordHash()))
                                                            .flatMap(
                                                                    valid ->
                                                                            valid
                                                                                    ? info(
                                                                                            share,
                                                                                            revision,
                                                                                            true)
                                                                                    : Mono.error(
                                                                                            PrototypeHubException
                                                                                                    .forbidden(
                                                                                                            "error.prototype-hub.share_password_invalid",
                                                                                                            "PROTOTYPE_HUB_SHARE_PASSWORD_INVALID")));
                                                }));
    }

    private Mono<PublicShareResponse> info(
            PrototypeShareEntity share, PrototypeRevisionEntity revision, boolean grant) {
        return projects.requireById(share.getProjectId())
                .map(
                        project -> {
                            String key =
                                    grant
                                            ? PrototypeShareKeys.derive(share, revision.getId())
                                            : null;
                            String prefix =
                                    grant
                                            ? "/api/prototype-hub/public/shares/"
                                                    + share.getToken()
                                                    + "/"
                                                    + key
                                                    + "/revisions/"
                                                    + revision.getId()
                                            : null;
                            String base = grant ? prefix + "/runtime" : null;
                            return new PublicShareResponse(
                                    share.getToken(),
                                    project.getName(),
                                    revision.getVersionName(),
                                    share.getPasswordHash() != null,
                                    share.getExpiresAt(),
                                    key,
                                    base,
                                    grant
                                            ? PrototypeUrls.preview(base, revision.getEntryFile())
                                            : null,
                                    revision.getId(),
                                    mode(share).name(),
                                    grant ? prefix + "/pages" : null,
                                    grant && Boolean.TRUE.equals(share.getReviewEnabled()),
                                    grant
                                            && Boolean.TRUE.equals(share.getReviewEnabled())
                                            && notifications.available(),
                                    grant
                                            && Boolean.TRUE.equals(share.getReviewEnabled())
                                            && Boolean.TRUE.equals(
                                                    share.getReviewAllowUnverified()));
                        });
    }

    /** 公开授权只解析一次快照；未迁移的旧固定分享仍拒绝访问，绝不隐式改绑 current。 */
    public Mono<PrototypeRevisionEntity> requireRevision(PrototypeShareEntity share) {
        return access.correlatedVersion(share)
                .flatMap(
                        version ->
                                mode(share) == ShareRevisionMode.latest
                                        ? revisions.current(version)
                                        : revisions.require(version, share.getRevisionId()))
                .onErrorMap(PrototypeHubException.class, error -> unavailable());
    }

    public Mono<PrototypeShareEntity> deleteShare(String id) {
        return lockedShare(id)
                .flatMap(share -> deleteById(id).thenReturn(share))
                .as(transactions::transactional)
                .checkpoint("prototype-hub/share/delete");
    }

    private static ShareRevisionMode mode(PrototypeShareEntity share) {
        if (share.getRevisionMode() == null) return ShareRevisionMode.fixed;
        try {
            return ShareRevisionMode.valueOf(share.getRevisionMode());
        } catch (IllegalArgumentException error) {
            throw unavailable();
        }
    }

    private static void validateConfig(
            String description, Long expiresAt, ShareRevisionMode mode, String revisionId) {
        if (mode == null
                || (description != null && description.length() > 2000)
                || (revisionId != null && (revisionId.isBlank() || revisionId.length() > 64))
                || (mode == ShareRevisionMode.latest && revisionId != null)) throw configInvalid();
        if (expiresAt != null && expiresAt <= System.currentTimeMillis())
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.share_expired_at_invalid",
                    "PROTOTYPE_HUB_SHARE_EXPIRES_AT_INVALID");
    }

    private static String encodePassword(String password) {
        if (password == null || password.isBlank())
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.share_password_required",
                    "PROTOTYPE_HUB_SHARE_PASSWORD_REQUIRED");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.share_password_too_long",
                    "PROTOTYPE_HUB_SHARE_PASSWORD_TOO_LONG");
        return PASSWORDS.encode(password);
    }

    private static PrototypeHubException configInvalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.share_config_invalid", "PROTOTYPE_HUB_SHARE_CONFIG_INVALID");
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.share_config_conflict",
                "PROTOTYPE_HUB_SHARE_CONFIG_CONFLICT",
                409);
    }

    private static PrototypeHubException unavailable() {
        return PrototypeHubException.notFound(
                "error.prototype-hub.share_unavailable", "PROTOTYPE_HUB_SHARE_UNAVAILABLE");
    }

    private static PrototypeHubException accessDenied() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.share_access_denied", "PROTOTYPE_HUB_SHARE_ACCESS_DENIED");
    }

    public static boolean matches(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8));
    }

    static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record PreparedPassword(String hash) {}
}
