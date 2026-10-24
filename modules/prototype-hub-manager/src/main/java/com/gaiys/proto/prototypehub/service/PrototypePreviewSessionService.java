package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.response.PreviewSessionResponse;
import com.gaiys.proto.prototypehub.web.urls.PrototypeUrls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 签发及验证 5 分钟内部预览能力，不向上传原型暴露平台登录凭证。
 *
 * <p>会话只存随机 secret 的摘要。每次资源请求重新核验身份、通用读动作、项目成员代次及固定快照和文件路径； 不缓存授权，也不保证收回浏览器已经接收的内容。
 */
@Service
@RequiredArgsConstructor
public class PrototypePreviewSessionService {
    public static final long TTL_MILLIS = 5 * 60 * 1000L;
    public static final int MAX_SESSIONS = 8;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypeVersionEntity, String> versions;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;
    private final TransactionalOperator transactions;
    private final PrototypeRevisionService revisions;

    public Mono<PreviewSessionResponse> create(String versionId) {
        return create(versionId, null);
    }

    /** 未指定快照时在项目锁内解析一次 current；指定时验证其确属本逻辑版本。 */
    public Mono<PreviewSessionResponse> create(String versionId, String revisionId) {
        return access.current()
                .flatMap(
                        actor ->
                                access.lockActiveUsers(actor.userId())
                                        .then(versions.findById(versionId))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "version")))
                                        .flatMap(
                                                version ->
                                                        createLocked(actor, version, revisionId)))
                .as(transactions::transactional);
    }

    private Mono<PreviewSessionResponse> createLocked(
            Actor actor, PrototypeVersionEntity found, String revisionId) {
        return access.lockProject(found.getProjectId())
                .then(access.requireProject(actor, found.getProjectId(), Action.READ, true))
                .flatMap(
                        grant ->
                                versions.findById(found.getId())
                                        .filter(version -> version.getDeletedTime() == null)
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "version")))
                                        .flatMap(
                                                version ->
                                                        selected(version, revisionId)
                                                                .flatMap(
                                                                        revision ->
                                                                                persist(
                                                                                        actor
                                                                                                .userId(),
                                                                                        version,
                                                                                        revision,
                                                                                        grant.project()
                                                                                                .getAccessEpoch()))));
    }

    private Mono<PrototypeRevisionEntity> selected(
            PrototypeVersionEntity version, String revisionId) {
        return revisionId == null
                ? revisions.current(version)
                : revisions.require(version, revisionId);
    }

    private Mono<PreviewSessionResponse> persist(
            String userId,
            PrototypeVersionEntity version,
            PrototypeRevisionEntity revision,
            String epoch) {
        if (epoch == null || epoch.isBlank()) return Mono.error(denied());
        String token =
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + TTL_MILLIS;
        String base = "/api/prototype-hub/runtime/" + version.getId() + "/" + token;
        // 只清理已持锁项目的最多 256 个过期行，避免跨项目 GC 与账号删除的有序项目锁形成锁环。
        // 当前 user/version 保留最近 8 个会话；冷项目过期行仅在后续签发或绑定清理时回收。
        PrototypePreviewSessionEntity session = new PrototypePreviewSessionEntity();
        session.setId(digest(token));
        session.setProjectId(version.getProjectId());
        session.setVersionId(version.getId());
        session.setRevisionId(revision.getId());
        session.setUserId(userId);
        session.setStoragePath(revision.getStoragePath());
        session.setAccessEpoch(epoch);
        session.setCreateTime(createdAt);
        session.setExpiresAt(expiresAt);
        return expired(version.getProjectId(), createdAt)
                .then(prune(userId, version.getId()))
                .then(sessions.insert(session))
                .thenReturn(
                        new PreviewSessionResponse(
                                base,
                                PrototypeUrls.preview(base, revision.getEntryFile()),
                                expiresAt));
    }

    private Mono<Void> expired(String projectId, long now) {
        return sessions.createQuery()
                .select(PrototypePreviewSessionEntity::getId)
                .where(PrototypePreviewSessionEntity::getProjectId, projectId)
                .lte(PrototypePreviewSessionEntity::getExpiresAt, now)
                .orderBy(
                        SortOrder.asc(PrototypePreviewSessionEntity::getExpiresAt),
                        SortOrder.asc(PrototypePreviewSessionEntity::getId))
                .paging(0, 256)
                .fetch()
                .map(PrototypePreviewSessionEntity::getId)
                .collectList()
                .flatMap(ids -> ids.isEmpty() ? Mono.empty() : sessions.deleteById(ids).then());
    }

    private Mono<Void> prune(String userId, String versionId) {
        // 最多收集 7 个保留标识；删除其余记录由数据库执行，不将无界旧会话装入内存。
        return sessions.createQuery()
                .select(PrototypePreviewSessionEntity::getId)
                .where(PrototypePreviewSessionEntity::getUserId, userId)
                .and(PrototypePreviewSessionEntity::getVersionId, versionId)
                .orderBy(
                        SortOrder.desc(PrototypePreviewSessionEntity::getCreateTime),
                        SortOrder.desc(PrototypePreviewSessionEntity::getId))
                .paging(0, MAX_SESSIONS - 1)
                .fetch()
                .map(PrototypePreviewSessionEntity::getId)
                .collectList()
                .flatMap(
                        ids -> {
                            var deletion =
                                    sessions.createDelete()
                                            .where(PrototypePreviewSessionEntity::getUserId, userId)
                                            .and(
                                                    PrototypePreviewSessionEntity::getVersionId,
                                                    versionId);
                            if (!ids.isEmpty())
                                deletion.notIn(PrototypePreviewSessionEntity::getId, ids);
                            return deletion.execute().then();
                        });
    }

    public Mono<PrototypeRevisionEntity> require(String versionId, String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) return Mono.error(denied());
        return sessions.findById(digest(token))
                .filter(
                        session ->
                                versionId.equals(session.getVersionId())
                                        && session.getRevisionId() != null
                                        && session.getExpiresAt() > System.currentTimeMillis())
                .flatMap(
                        session ->
                                access.actor(session.getUserId())
                                        .filter(
                                                actor ->
                                                        actor.authentication()
                                                                .hasPermission(
                                                                        "prototype-hub-version",
                                                                        "query"))
                                        .flatMap(
                                                actor ->
                                                        access.requireProject(
                                                                actor,
                                                                session.getProjectId(),
                                                                Action.READ,
                                                                false))
                                        .filter(
                                                grant ->
                                                        Objects.equals(
                                                                session.getAccessEpoch(),
                                                                grant.project().getAccessEpoch()))
                                        .flatMap(
                                                grant ->
                                                        versions.findById(versionId)
                                                                .filter(
                                                                        version ->
                                                                                version
                                                                                                        .getDeletedTime()
                                                                                                == null
                                                                                        && session.getProjectId()
                                                                                                .equals(
                                                                                                        version
                                                                                                                .getProjectId()))
                                                                .flatMap(
                                                                        version ->
                                                                                revisions.require(
                                                                                        version,
                                                                                        session
                                                                                                .getRevisionId()))
                                                                .filter(
                                                                        revision ->
                                                                                Objects.equals(
                                                                                        session
                                                                                                .getStoragePath(),
                                                                                        revision
                                                                                                .getStoragePath()))))
                .switchIfEmpty(Mono.error(PrototypePreviewSessionService::denied))
                // 固定 revision 不再比较 current；替换仍保留本会话内容，撤权代次和停用继续逐请求校验。
                .onErrorMap(
                        error ->
                                error instanceof PrototypeHubException
                                        || error instanceof UnAuthorizedException,
                        error -> denied());
    }

    private static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static PrototypeHubException denied() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.preview_access_denied", "PROTOTYPE_HUB_PREVIEW_ACCESS_DENIED");
    }
}
