package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.web.response.OwnershipMigrationResponse;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 显式所有权回填与待认领项目恢复。EasyORM 仅补结构，绝不在启动时猜测旧项目所有者。
 *
 * <p>只有 ownerId 与 accessEpoch 均为空的旧记录可以按当前有效创建者回填。accessEpoch 非空表示已经 分类过的授权谱系；账号删除或历史回填后不会再次凭相同的创建者
 * ID 复活所有权。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrototypeOwnershipService {
    private static final int BATCH_SIZE = 100;
    private final PrototypeProjectAccessService access;
    private final PrototypeProjectMemberService members;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final DatabaseClient database;
    private final TransactionalOperator transactions;

    public Mono<OwnershipMigrationResponse> migrate(boolean apply) {
        return access.requireAdministrator().then(apply ? applyMigration() : dryRun());
    }

    private Mono<OwnershipMigrationResponse> dryRun() {
        // 只读取，不更新 owner/epoch，也不签发会话；可先在备份副本核对数量后显式 apply。
        return database.sql(
                        """
                select count(*) as scanned,
                count(*) filter(where (p.owner_id is null or p.owner_id !~ '[^[:space:]]') and (p.access_epoch is null or p.access_epoch !~ '[^[:space:]]') and u.id is not null) as assigned,
                count(*) filter(where (p.owner_id is null or p.owner_id !~ '[^[:space:]]') and not ((p.access_epoch is null or p.access_epoch !~ '[^[:space:]]') and u.id is not null)) as pending,
                count(*) filter(where p.owner_id ~ '[^[:space:]]') as unchanged
                from p_project p left join s_user u on u.id=p.creator_id and u.status=1
                """)
                .map(
                        (row, metadata) ->
                                new OwnershipMigrationResponse(
                                        row.get("scanned", Long.class),
                                        row.get("assigned", Long.class),
                                        row.get("pending", Long.class),
                                        row.get("unchanged", Long.class)))
                .one();
    }

    private Mono<OwnershipMigrationResponse> applyMigration() {
        return applyBatch("", new OwnershipMigrationResponse(0, 0, 0, 0))
                .doOnNext(
                        result ->
                                log.info(
                                        "Prototype ownership migration applied: scanned={}, assigned={}, pending={}, unchanged={}",
                                        result.scanned(),
                                        result.assigned(),
                                        result.pending(),
                                        result.unchanged()));
    }

    private Mono<OwnershipMigrationResponse> applyBatch(
            String after, OwnershipMigrationResponse total) {
        // 整批读取并释放连接后逐项目短事务，整批事务结束才取下一批；不通过 expand 预取查询争抢 pool=1。
        return batch(after)
                .flatMap(
                        ids -> {
                            if (ids.isEmpty()) return Mono.just(total);
                            return Flux.fromIterable(ids)
                                    .concatMap(this::migrateOne, 1)
                                    .reduce(
                                            total,
                                            (sum, item) ->
                                                    new OwnershipMigrationResponse(
                                                            sum.scanned() + item.scanned(),
                                                            sum.assigned() + item.assigned(),
                                                            sum.pending() + item.pending(),
                                                            sum.unchanged() + item.unchanged()))
                                    .flatMap(
                                            sum ->
                                                    Mono.defer(
                                                            () ->
                                                                    applyBatch(
                                                                            ids.get(ids.size() - 1),
                                                                            sum)));
                        });
    }

    private Mono<List<String>> batch(String after) {
        return database.sql("select id from p_project where id>:after order by id limit :batchSize")
                .bind("after", after)
                .bind("batchSize", BATCH_SIZE)
                .map((row, metadata) -> row.get("id", String.class))
                .all()
                .collectList();
    }

    private Mono<OwnershipMigrationResponse> migrateOne(String id) {
        // 每项目单独事务：候选前读→creator 用户锁→项目锁→重读。不能持项目锁再等待正在删除的用户。
        return projects.findById(id)
                .flatMap(
                        candidate -> {
                            boolean legacy =
                                    !hasText(candidate.getOwnerId())
                                            && !hasText(candidate.getAccessEpoch());
                            Mono<Boolean> activeCreator =
                                    legacy
                                            ? access.tryLockActiveUser(candidate.getCreatorId())
                                            : Mono.just(false);
                            return activeCreator.flatMap(
                                    active ->
                                            access.lockProject(id)
                                                    .then(projects.findById(id))
                                                    .flatMap(
                                                            current ->
                                                                    classify(
                                                                            current, candidate,
                                                                            active)));
                        })
                .as(transactions::transactional);
    }

    private Mono<OwnershipMigrationResponse> classify(
            PrototypeProjectEntity project,
            PrototypeProjectEntity candidate,
            boolean activeCreator) {
        if (hasText(project.getOwnerId()))
            return Mono.just(new OwnershipMigrationResponse(1, 0, 0, 1));
        if (hasText(project.getAccessEpoch()))
            return Mono.just(new OwnershipMigrationResponse(1, 0, 1, 0));
        if (hasText(candidate.getOwnerId())
                || hasText(candidate.getAccessEpoch())
                || !Objects.equals(project.getCreatorId(), candidate.getCreatorId())) {
            // creator 是历史审计字段，业务 API 不修改它；并发人工修复不能借未锁定的新 creator 隐式授权。
            return Mono.error(
                    new PrototypeHubException(
                            "error.prototype-hub.ownership_conflict",
                            "PROTOTYPE_HUB_OWNERSHIP_CONFLICT",
                            409));
        }
        return activeCreator
                ? assign(project.getId(), project.getCreatorId())
                        .thenReturn(new OwnershipMigrationResponse(1, 1, 0, 0))
                : pending(project.getId());
    }

    private Mono<OwnershipMigrationResponse> pending(String id) {
        // 标记“已分类、等待显式 claim”；将来同名账号复用 hsweb 用户 ID 也不能再按 creator 自动认领。
        return members.rotate(id).thenReturn(new OwnershipMigrationResponse(1, 0, 1, 0));
    }

    public Mono<ProjectResponse> claim(String id, String userId) {
        return access.requireAdministrator()
                .then(access.current())
                .flatMap(actor -> access.lockActiveUsers(actor.userId(), userId))
                .then(access.lockProject(id))
                .then(access.requireProject(id, Action.MEMBERS))
                .flatMap(
                        grant ->
                                access.claimRequired(grant.project())
                                        .flatMap(
                                                pending ->
                                                        pending
                                                                ? access.activeUser(userId)
                                                                        .flatMap(
                                                                                user ->
                                                                                        assign(
                                                                                                id,
                                                                                                user
                                                                                                        .getId()))
                                                                : Mono.error(
                                                                        PrototypeHubException
                                                                                .badRequest(
                                                                                        "error.prototype-hub.project_already_claimed",
                                                                                        "PROTOTYPE_HUB_PROJECT_ALREADY_CLAIMED"))))
                .then(access.requireProject(id, Action.READ))
                .flatMap(access::response)
                .as(transactions::transactional);
    }

    private Mono<Void> assign(String id, String ownerId) {
        return database.sql("update p_project set owner_id=:owner where id=:id")
                .bind("owner", ownerId)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .then(
                        database.sql(
                                        "delete from p_project_member where project_id=:id and user_id=:owner")
                                .bind("id", id)
                                .bind("owner", ownerId)
                                .fetch()
                                .rowsUpdated())
                .then(members.rotate(id));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
