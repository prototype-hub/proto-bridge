package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 签发及验证 5 分钟内部预览能力，不向上传原型暴露平台登录凭证。
 *
 * <p>会话只存随机 secret 的摘要。每次资源请求重新核验身份、通用读动作、项目成员代次及具体文件路径； 不缓存授权，也不保证收回浏览器已经接收的内容。
 */
@Service
@RequiredArgsConstructor
public class PrototypePreviewSessionService {
    public static final long TTL_MILLIS = 5 * 60 * 1000L;
    public static final int MAX_SESSIONS = 8;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypeVersionEntity, String> versions;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;
    private final DatabaseClient database;
    private final TransactionalOperator transactions;

    public Mono<PreviewSessionResponse> create(String versionId) {
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
                                                        access.lockProject(version.getProjectId())
                                                                .then(
                                                                        access.requireProject(
                                                                                actor,
                                                                                version
                                                                                        .getProjectId(),
                                                                                Action.READ,
                                                                                true))
                                                                .flatMap(
                                                                        grant ->
                                                                                versions.findById(
                                                                                                versionId)
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .error(
                                                                                                                () ->
                                                                                                                        PrototypeHubException
                                                                                                                                .missing(
                                                                                                                                        "version")))
                                                                                        .flatMap(
                                                                                                current ->
                                                                                                        persist(
                                                                                                                actor
                                                                                                                        .userId(),
                                                                                                                current,
                                                                                                                grant.project()
                                                                                                                        .getAccessEpoch())))))
                .as(transactions::transactional);
    }

    private Mono<PreviewSessionResponse> persist(
            String userId, PrototypeVersionEntity version, String epoch) {
        if (epoch == null || epoch.isBlank()) return Mono.error(denied());
        String token =
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + TTL_MILLIS;
        String base = "/api/prototype-hub/runtime/" + version.getId() + "/" + token;
        // 只清理已持锁项目的最多 256 个过期行，避免跨项目 GC 与账号删除的有序项目锁形成锁环。
        // 当前 user/version 保留最近 8 个会话；冷项目过期行仅在后续签发或绑定清理时回收。
        return database.sql(
                        "delete from p_preview_session where id in (select id from p_preview_session where project_id=:projectId and expires_at<=:now order by expires_at,id limit 256)")
                .bind("projectId", version.getProjectId())
                .bind("now", createdAt)
                .fetch()
                .rowsUpdated()
                .then(
                        database.sql(
                                        "delete from p_preview_session where id in (select id from p_preview_session where user_id=:userId and version_id=:versionId order by create_time desc,id desc offset 7)")
                                .bind("userId", userId)
                                .bind("versionId", version.getId())
                                .fetch()
                                .rowsUpdated())
                .then(
                        database.sql(
                                        """
                        insert into p_preview_session(id,project_id,version_id,user_id,storage_path,access_epoch,create_time,expires_at)
                        values(:id,:projectId,:versionId,:userId,:storagePath,:epoch,:createdAt,:expiresAt)
                        """)
                                .bind("id", digest(token))
                                .bind("projectId", version.getProjectId())
                                .bind("versionId", version.getId())
                                .bind("userId", userId)
                                .bind("storagePath", version.getStoragePath())
                                .bind("epoch", epoch)
                                .bind("createdAt", createdAt)
                                .bind("expiresAt", expiresAt)
                                .fetch()
                                .rowsUpdated())
                .thenReturn(
                        new PreviewSessionResponse(
                                base,
                                PrototypeUrls.preview(base, version.getEntryFile()),
                                expiresAt));
    }

    public Mono<PrototypeVersionEntity> require(String versionId, String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) return Mono.error(denied());
        return sessions.findById(digest(token))
                .filter(
                        session ->
                                versionId.equals(session.getVersionId())
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
                                                                                session.getProjectId()
                                                                                                .equals(
                                                                                                        version
                                                                                                                .getProjectId())
                                                                                        && Objects
                                                                                                .equals(
                                                                                                        session
                                                                                                                .getStoragePath(),
                                                                                                        version
                                                                                                                .getStoragePath()))))
                .switchIfEmpty(Mono.error(PrototypePreviewSessionService::denied))
                // 匿名资源入口只暴露统一拒绝，不泄漏账号/成员/项目是否存在。
                .onErrorMap(
                        error ->
                                error instanceof PrototypeHubException
                                        || error
                                                instanceof
                                                org.hswebframework.web.authorization.exception
                                                        .UnAuthorizedException,
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
