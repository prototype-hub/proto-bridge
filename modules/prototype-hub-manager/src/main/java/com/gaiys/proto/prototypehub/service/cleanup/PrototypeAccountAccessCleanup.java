package com.gaiys.proto.prototypehub.service.cleanup;

import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectMemberService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.hswebframework.web.system.authorization.api.event.UserDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 账号删除时同步撤销原型资产绑定，防止 hsweb 同名账号重建复用用户 ID 后继承先前的项目权限。
 *
 * <p>使用官方异步事件的事务内链路；数据库清理失败会使账号删除失败，不在提交后尽力删除。
 */
@Component
@RequiredArgsConstructor
public class PrototypeAccountAccessCleanup {
    private static final int BATCH_SIZE = 100;
    private final PrototypeProjectAccessService access;
    private final PrototypeProjectMemberService members;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final ReactiveRepository<PrototypeProjectMemberEntity, String> bindings;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;

    @EventListener
    public void deleted(UserDeletedEvent event) {
        event.async(revoke(event.getUser().getId()));
    }

    private Mono<Void> revoke(String userId) {
        return Mono.just("")
                .expand(after -> revokeBatch(userId, after), 1)
                .then()
                .then(
                        sessions.createDelete()
                                .where(PrototypePreviewSessionEntity::getUserId, userId)
                                .execute())
                .then();
    }

    private Mono<String> revokeBatch(String userId, String after) {
        // 删除事务已锁用户行；绑定生产者也先锁用户，再锁项目，不会插入遗漏的新绑定。
        // 有界读取释放游标后再串行锁项目，避免同事务连接上交叠查询，并始终推进扫描页的最后 ID。
        return batch(after)
                .flatMap(
                        page -> {
                            if (page.isEmpty()) return Mono.empty();
                            List<String> ids =
                                    page.stream().map(PrototypeProjectEntity::getId).toList();
                            return bindings.createQuery()
                                    .select(PrototypeProjectMemberEntity::getProjectId)
                                    .where(PrototypeProjectMemberEntity::getUserId, userId)
                                    .in(PrototypeProjectMemberEntity::getProjectId, ids)
                                    .fetch()
                                    .map(PrototypeProjectMemberEntity::getProjectId)
                                    .collect(Collectors.toSet())
                                    .flatMap(
                                            bound ->
                                                    sessions.createQuery()
                                                            .select(
                                                                    PrototypePreviewSessionEntity
                                                                            ::getProjectId)
                                                            .where(
                                                                    PrototypePreviewSessionEntity
                                                                            ::getUserId,
                                                                    userId)
                                                            .in(
                                                                    PrototypePreviewSessionEntity
                                                                            ::getProjectId,
                                                                    ids)
                                                            .fetch()
                                                            .map(
                                                                    PrototypePreviewSessionEntity
                                                                            ::getProjectId)
                                                            .collect(Collectors.toSet())
                                                            .map(
                                                                    previewed ->
                                                                            page.stream()
                                                                                    .filter(
                                                                                            project ->
                                                                                                    affected(
                                                                                                            project,
                                                                                                            userId,
                                                                                                            bound,
                                                                                                            previewed))
                                                                                    .map(
                                                                                            PrototypeProjectEntity
                                                                                                    ::getId)
                                                                                    .toList()))
                                    .flatMap(
                                            affected ->
                                                    Flux.fromIterable(affected)
                                                            .concatMap(
                                                                    id -> revokeProject(id, userId),
                                                                    1)
                                                            .then())
                                    .thenReturn(page.getLast().getId());
                        });
    }

    /** 账号撤权只维护授权字段，官方标记保留项目编辑审计；其他实体事件照常执行。 */
    private Mono<Void> revokeProject(String projectId, String userId) {
        return access.lockProject(projectId)
                .then(
                        projects.createUpdate()
                                .setNull(PrototypeProjectEntity::getOwnerId)
                                .where(PrototypeProjectEntity::getId, projectId)
                                .and(PrototypeProjectEntity::getOwnerId, userId)
                                .execute()
                                .contextWrite(RecordModifierEntity::markDoNotUpdate))
                .then(
                        bindings.createDelete()
                                .where(PrototypeProjectMemberEntity::getProjectId, projectId)
                                .and(PrototypeProjectMemberEntity::getUserId, userId)
                                .execute())
                // 非空 epoch 表示已分类的授权谱系；显式迁移也不能给同名新账号复活旧所有权。
                .then(members.rotate(projectId));
    }

    private static boolean affected(
            PrototypeProjectEntity project,
            String userId,
            Set<String> bound,
            Set<String> previewed) {
        return userId.equals(project.getOwnerId())
                || bound.contains(project.getId())
                || previewed.contains(project.getId())
                || (userId.equals(project.getCreatorId())
                        && !hasText(project.getOwnerId())
                        && !hasText(project.getAccessEpoch()));
    }

    private Mono<List<PrototypeProjectEntity>> batch(String after) {
        return projects.createQuery()
                .select(
                        PrototypeProjectEntity::getId,
                        PrototypeProjectEntity::getOwnerId,
                        PrototypeProjectEntity::getCreatorId,
                        PrototypeProjectEntity::getAccessEpoch)
                .where()
                .gt(PrototypeProjectEntity::getId, after)
                .orderBy(SortOrder.asc(PrototypeProjectEntity::getId))
                .paging(0, BATCH_SIZE)
                .fetch()
                .collectList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
