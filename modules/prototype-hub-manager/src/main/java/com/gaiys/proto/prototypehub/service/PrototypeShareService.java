package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.ShareCreateRequest;
import com.gaiys.proto.prototypehub.web.response.PublicShareResponse;
import com.gaiys.proto.prototypehub.web.urls.PrototypeUrls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 管理原型版本分享链接及访问授权。
 *
 * <p>BCrypt 运算沿用 FileWork 隔离；分享创建的事务内维持项目、版本行锁顺序， 公开访问根据有效期和密码校验结果授予运行文件访问密钥。
 */
@Service
@AllArgsConstructor
public class PrototypeShareService
        extends GenericReactiveCrudService<PrototypeShareEntity, String> {
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);
    private final PrototypeProjectService projects;
    private final PrototypeVersionService versions;
    private final FileWork work;
    private final DatabaseClient database;
    private final TransactionalOperator transactions;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;

    public Flux<PrototypeShareEntity> findByVersionId(String id) {
        return createQuery().where(PrototypeShareEntity::getVersionId, id).fetch();
    }

    public Mono<Integer> deleteByVersionId(String id) {
        return createDelete().where(PrototypeShareEntity::getVersionId, id).execute();
    }

    public Mono<Integer> deleteByProjectId(String id) {
        return createDelete().where(PrototypeShareEntity::getProjectId, id).execute();
    }

    /**
     * 管理侧复制前校验已知分享密码，只返回校验结果，不授予公开访问密钥。
     *
     * <p>分享启停和有效期不影响已登录用户确认密码；沿用既存 BCrypt 验证，不裁剪空格或限制旧密码字节数。
     */
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
                                        .then(
                                                work.call(
                                                        () ->
                                                                buildShare(
                                                                        version, versionId, input,
                                                                        actor))))
                .flatMap(share -> persistShare(versionId, share));
    }

    private PrototypeShareEntity buildShare(
            PrototypeVersionEntity version,
            String versionId,
            ShareCreateRequest input,
            String actor) {
        if (input.expiresAt() != null && input.expiresAt() <= System.currentTimeMillis())
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.share_expired_at_invalid",
                    "PROTOTYPE_HUB_SHARE_EXPIRES_AT_INVALID");
        // 新密码遵循 BCrypt 的字节上限；既存分享仍沿用原 matches 验证兼容行为。
        if (input.password() != null
                && !input.password().isBlank()
                && input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.share_password_too_long",
                    "PROTOTYPE_HUB_SHARE_PASSWORD_TOO_LONG");
        PrototypeShareEntity share = new PrototypeShareEntity();
        share.setId(random());
        share.setProjectId(version.getProjectId());
        share.setVersionId(versionId);
        share.setToken(random());
        share.setAccessKey(random());
        share.setEnabled(true);
        share.setCreatorId(actor);
        share.setCreateTime(System.currentTimeMillis());
        share.setExpiresAt(input.expiresAt());
        share.setDescription(input.description());
        if (input.password() != null && !input.password().isBlank())
            share.setPasswordHash(PASSWORDS.encode(input.password()));
        return share;
    }

    private Mono<PrototypeShareEntity> persistShare(String versionId, PrototypeShareEntity share) {
        // 沿用项目、版本的锁顺序，父级存在性检查与分享插入处于同一事务。
        return database.sql("select id from p_project where id=:id for update")
                .bind("id", share.getProjectId())
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")))
                .then(
                        database.sql("select id from p_version where id=:id for update")
                                .bind("id", versionId)
                                .fetch()
                                .one()
                                .switchIfEmpty(
                                        Mono.error(() -> PrototypeHubException.missing("version"))))
                .then(access.requireVersion(versionId, PrototypeProjectAccessService.Action.SHARE))
                .flatMap(version -> revisions.current(version))
                .flatMap(
                        revision -> {
                            // 必须在项目/版本锁内取 current，不能把密码计算前读到的指针当作发布时指针。
                            share.setRevisionId(revision.getId());
                            return insert(share).thenReturn(share);
                        })
                .as(transactions::transactional);
    }

    public Mono<PrototypeShareEntity> requireActive(String token) {
        return createQuery()
                .where(PrototypeShareEntity::getToken, token)
                .fetchOne()
                .filter(
                        s ->
                                Boolean.TRUE.equals(s.getEnabled())
                                        && s.getRevisionId() != null
                                        && (s.getExpiresAt() == null
                                                || s.getExpiresAt() > System.currentTimeMillis()))
                .switchIfEmpty(
                        Mono.error(
                                () ->
                                        PrototypeHubException.notFound(
                                                "error.prototype-hub.share_unavailable",
                                                "PROTOTYPE_HUB_SHARE_UNAVAILABLE")));
    }

    public Mono<PrototypeShareEntity> requireAccess(String token, String key) {
        return requireActive(token)
                .flatMap(share -> requireRevision(share).thenReturn(share))
                .filter(s -> matches(s.getAccessKey(), key))
                .switchIfEmpty(
                        Mono.error(
                                () ->
                                        PrototypeHubException.forbidden(
                                                "error.prototype-hub.share_access_denied",
                                                "PROTOTYPE_HUB_SHARE_ACCESS_DENIED")));
    }

    public Mono<PublicShareResponse> getPublicInfo(String token, String password) {
        return requireActive(token)
                .flatMap(share -> requireRevision(share).thenReturn(share))
                .flatMap(
                        share -> {
                            if (share.getPasswordHash() == null) return info(share, true);
                            if (password == null) return info(share, false);
                            return work.call(
                                            () ->
                                                    PASSWORDS.matches(
                                                            password, share.getPasswordHash()))
                                    .flatMap(
                                            valid ->
                                                    valid
                                                            ? info(share, true)
                                                            : Mono.error(
                                                                    PrototypeHubException.forbidden(
                                                                            "error.prototype-hub.share_password_invalid",
                                                                            "PROTOTYPE_HUB_SHARE_PASSWORD_INVALID")));
                        });
    }

    private Mono<PublicShareResponse> info(PrototypeShareEntity share, boolean grant) {
        return Mono.zip(projects.requireById(share.getProjectId()), requireRevision(share))
                .map(
                        pair -> {
                            String key = grant ? share.getAccessKey() : null;
                            String base =
                                    grant
                                            ? "/api/prototype-hub/public/shares/"
                                                    + share.getToken()
                                                    + "/"
                                                    + key
                                                    + "/runtime"
                                            : null;
                            return new PublicShareResponse(
                                    share.getToken(),
                                    pair.getT1().getName(),
                                    pair.getT2().getVersionName(),
                                    share.getPasswordHash() != null,
                                    share.getExpiresAt(),
                                    key,
                                    base,
                                    grant
                                            ? PrototypeUrls.preview(
                                                    base, pair.getT2().getEntryFile())
                                            : null,
                                    share.getRevisionId());
                        });
    }

    /** 公开访问固定快照；旧分享必须显式迁移，不允许退回逻辑版本当前内容。 */
    public Mono<PrototypeRevisionEntity> requireRevision(PrototypeShareEntity share) {
        return access.correlatedVersion(share)
                .flatMap(version -> revisions.require(version, share.getRevisionId()))
                .onErrorMap(
                        PrototypeHubException.class,
                        error ->
                                PrototypeHubException.notFound(
                                        "error.prototype-hub.share_unavailable",
                                        "PROTOTYPE_HUB_SHARE_UNAVAILABLE"));
    }

    public Mono<PrototypeShareEntity> deleteShare(String id) {
        return findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("share")))
                .flatMap(
                        found ->
                                access.lockProject(found.getProjectId())
                                        .then(
                                                access.requireShare(
                                                        id,
                                                        PrototypeProjectAccessService.Action
                                                                .SHARE)))
                .flatMap(share -> deleteById(id).thenReturn(share))
                .as(transactions::transactional);
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
}
