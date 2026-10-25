package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 固定30天回收站保留期；先事务登记文件清理并移除到期图，再由持久队列清理文件。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrototypeRetentionService {
    public static final long RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static final int BATCH_SIZE = 50;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final ReactiveRepository<PrototypeVersionEntity, String> versions;
    private final ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    private final ReactiveRepository<PrototypeShareEntity, String> shares;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;
    private final ReactiveRepository<PrototypeCreationReceiptEntity, String> receipts;
    private final ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    private final PrototypeProjectAccessService access;
    private final PrototypeInstallationService installations;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;
    private final ObservationRegistry observations;
    private final QueryHelper queries;
    private final PrototypeReviewService review;
    private final PrototypeReviewThreadService reviewThreads;

    /** 恰好30天即到期；null仍为活动行，未来时间不会提前清理。 */
    public static boolean expired(Long deletedTime, long now) {
        return deletedTime != null && deletedTime <= now - RETENTION_MILLIS;
    }

    /** 沿用现有Spring调度和周期，不新增线程池、内存队列或运维MBean。 */
    @Scheduled(fixedDelayString = "${prototype-hub.cleanup.interval-ms:30000}")
    public Mono<Void> maintain() {
        return purgeExpired()
                .onErrorResume(
                        error -> {
                            log.warn(
                                    "Prototype retention batch pending: errorType={}",
                                    error.getClass().getSimpleName());
                            return Mono.empty();
                        })
                .then(
                        review.cleanupExpired()
                                .onErrorResume(
                                        error -> {
                                            log.warn(
                                                    "Prototype review cleanup pending: errorType={}",
                                                    error.getClass().getSimpleName());
                                            return Mono.empty();
                                        }))
                .then(
                        reviewThreads
                                .cleanupEvents()
                                .onErrorResume(
                                        error -> {
                                            log.warn(
                                                    "Prototype review event cleanup pending: errorType={}",
                                                    error.getClass().getSimpleName());
                                            return Mono.empty();
                                        }))
                .then(cleanup.retryPending());
    }

    /** 每轮最多处理50个资源；先版本后项目，父项目不提前级联仍保留的子版本。 */
    public Mono<Void> purgeExpired() {
        return Mono.defer(
                () -> {
                    Observation observation =
                            Observation.createNotStarted("prototype.retention", observations)
                                    .start();
                    long cutoff = System.currentTimeMillis() - RETENTION_MILLIS;
                    return claimVersionBatch(cutoff)
                            .flatMap(
                                    ids ->
                                            purgeVersions(ids)
                                                    .flatMap(
                                                            count ->
                                                                    purgeProjects(
                                                                                    BATCH_SIZE
                                                                                            - ids
                                                                                                    .size(),
                                                                                    cutoff)
                                                                            .map(
                                                                                    projectCount ->
                                                                                            new Purged(
                                                                                                    count,
                                                                                                    projectCount))))
                            .doOnNext(
                                    count -> {
                                        if (count.versions() + count.projects() > 0)
                                            log.info(
                                                    "Prototype retention purged: versions={}, projects={}",
                                                    count.versions(),
                                                    count.projects());
                                    })
                            .doOnError(observation::error)
                            .doFinally(signal -> observation.stop())
                            .then();
                });
    }

    /** 安装锁只领取有界扫描页并持久化游标；提交释放后才取项目锁，避免与创建/恢复倒序。 */
    private Mono<List<String>> claimVersionBatch(long cutoff) {
        return installations
                .lock()
                .then(installations.current())
                .flatMap(
                        installation -> {
                            var query =
                                    versions.createQuery()
                                            .select(
                                                    PrototypeVersionEntity::getId,
                                                    PrototypeVersionEntity::getDeletedTime)
                                            .lte(PrototypeVersionEntity::getDeletedTime, cutoff)
                                            .orderBy(
                                                    SortOrder.asc(
                                                            PrototypeVersionEntity::getDeletedTime),
                                                    SortOrder.asc(PrototypeVersionEntity::getId));
                            if (installation.getRetentionDeletedTime() != null
                                    && installation.getRetentionVersionId() != null)
                                query.nest()
                                        .gt(
                                                PrototypeVersionEntity::getDeletedTime,
                                                installation.getRetentionDeletedTime())
                                        .orNest()
                                        .is(
                                                PrototypeVersionEntity::getDeletedTime,
                                                installation.getRetentionDeletedTime())
                                        .gt(
                                                PrototypeVersionEntity::getId,
                                                installation.getRetentionVersionId())
                                        .end()
                                        .end();
                            return query.paging(0, BATCH_SIZE)
                                    .fetch()
                                    .collectList()
                                    .flatMap(
                                            page -> {
                                                var update =
                                                        installations
                                                                .createUpdate()
                                                                .where(
                                                                        PrototypeInstallationEntity
                                                                                ::getId,
                                                                        installation.getId());
                                                if (page.size() < BATCH_SIZE)
                                                    update.setNull(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionDeletedTime)
                                                            .setNull(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionVersionId);
                                                else {
                                                    var last = page.getLast();
                                                    update.set(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionDeletedTime,
                                                                    last.getDeletedTime())
                                                            .set(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionVersionId,
                                                                    last.getId());
                                                }
                                                return update.execute()
                                                        .thenReturn(
                                                                page.stream()
                                                                        .map(
                                                                                PrototypeVersionEntity
                                                                                        ::getId)
                                                                        .toList());
                                            });
                        })
                .as(transactions::transactional);
    }

    private Mono<Integer> purgeVersions(List<String> ids) {
        return Flux.fromIterable(ids)
                .concatMap(
                        id ->
                                purgeVersion(id)
                                        .onErrorResume(error -> pending("version", id, error)),
                        1)
                .map(purged -> purged ? 1 : 0)
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> purgeProjects(int limit, long cutoff) {
        if (limit == 0) return Mono.just(0);
        return claimProjectBatch(limit, cutoff)
                .flatMapMany(Flux::fromIterable)
                .concatMap(
                        id ->
                                purgeProject(id)
                                        .onErrorResume(error -> pending("project", id, error)),
                        1)
                .map(purged -> purged ? 1 : 0)
                .reduce(0, Integer::sum);
    }

    /** 原始子行先排除，失败空项目再用独立持久游标轮转；这里也不持安装锁等待项目锁。 */
    private Mono<List<String>> claimProjectBatch(int limit, long cutoff) {
        QueryParamEntity page = new QueryParamEntity();
        page.doPaging(0, limit);
        return installations
                .lock()
                .then(installations.current())
                .flatMap(
                        installation -> {
                            var query =
                                    queries.select(PrototypeProjectEntity.class)
                                            .all(PrototypeProjectEntity.class)
                                            .from(PrototypeProjectEntity.class)
                                            .leftJoin(
                                                    PrototypeVersionEntity.class,
                                                    join ->
                                                            join.is(
                                                                    PrototypeVersionEntity
                                                                            ::getProjectId,
                                                                    PrototypeProjectEntity::getId));
                            query.where(page);
                            query.where(
                                    condition ->
                                            condition
                                                    .lte(
                                                            PrototypeProjectEntity::getDeletedTime,
                                                            cutoff)
                                                    .isNull(PrototypeVersionEntity::getId));
                            if (installation.getRetentionProjectTime() != null
                                    && installation.getRetentionProjectId() != null)
                                query.where(
                                        condition ->
                                                condition
                                                        .nest()
                                                        .gt(
                                                                PrototypeProjectEntity
                                                                        ::getDeletedTime,
                                                                installation
                                                                        .getRetentionProjectTime())
                                                        .orNest()
                                                        .is(
                                                                PrototypeProjectEntity
                                                                        ::getDeletedTime,
                                                                installation
                                                                        .getRetentionProjectTime())
                                                        .gt(
                                                                PrototypeProjectEntity::getId,
                                                                installation
                                                                        .getRetentionProjectId())
                                                        .end()
                                                        .end());
                            return query.orderByAsc(PrototypeProjectEntity::getDeletedTime)
                                    .orderByAsc(PrototypeProjectEntity::getId)
                                    .fetch()
                                    .collectList()
                                    .flatMap(
                                            rows -> {
                                                var update =
                                                        installations
                                                                .createUpdate()
                                                                .where(
                                                                        PrototypeInstallationEntity
                                                                                ::getId,
                                                                        installation.getId());
                                                if (rows.size() < limit)
                                                    update.setNull(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionProjectTime)
                                                            .setNull(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionProjectId);
                                                else {
                                                    var last = rows.getLast();
                                                    update.set(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionProjectTime,
                                                                    last.getDeletedTime())
                                                            .set(
                                                                    PrototypeInstallationEntity
                                                                            ::getRetentionProjectId,
                                                                    last.getId());
                                                }
                                                return update.execute()
                                                        .thenReturn(
                                                                rows.stream()
                                                                        .map(
                                                                                PrototypeProjectEntity
                                                                                        ::getId)
                                                                        .toList());
                                            });
                        })
                .as(transactions::transactional);
    }

    private static Mono<Boolean> pending(String kind, String id, Throwable error) {
        log.warn(
                "Prototype retention pending: kind={}, resource={}, errorType={}",
                kind,
                id,
                error.getClass().getSimpleName());
        return Mono.just(false);
    }

    /** 维护操作不要求父项目活动或存在；取得同样锁序后重读，恢复赢得锁时绝不删除其文件。 */
    public Mono<Boolean> purgeVersion(String id) {
        return versions.findById(id)
                .flatMap(
                        found ->
                                access.tryLockProject(found.getProjectId())
                                        .then(
                                                versions.createQuery()
                                                        .where(PrototypeVersionEntity::getId, id)
                                                        .forUpdate()
                                                        .fetchOne()))
                .filter(version -> expired(version.getDeletedTime(), System.currentTimeMillis()))
                .flatMap(
                        version ->
                                cleanup.enqueueExpired(
                                                version.getProjectId(),
                                                id,
                                                version.getStoragePath())
                                        .then(enqueueHistory(version))
                                        .then(deleteVersionGraph(id))
                                        .thenReturn(true))
                .defaultIfEmpty(false)
                .as(transactions::transactional);
    }

    /** 每页释放读游标后登记路径；历史数量不限，但不把清单或无界路径列表装入内存。 */
    private Mono<Void> enqueueHistory(PrototypeVersionEntity version) {
        return Mono.just("")
                .expand(
                        after ->
                                historyPage(version.getId(), after)
                                        .flatMap(
                                                page -> {
                                                    if (page.isEmpty()) return Mono.empty();
                                                    return Flux.fromIterable(page)
                                                            .concatMap(
                                                                    revision ->
                                                                            cleanup.enqueueExpired(
                                                                                    version
                                                                                            .getProjectId(),
                                                                                    version.getId(),
                                                                                    revision
                                                                                            .getStoragePath()),
                                                                    1)
                                                            .then(
                                                                    Mono.just(
                                                                            page.getLast()
                                                                                    .getId()));
                                                }),
                        1)
                .then();
    }

    private Mono<List<PrototypeRevisionEntity>> historyPage(String version, String after) {
        return revisions
                .createQuery()
                .select(PrototypeRevisionEntity::getId, PrototypeRevisionEntity::getStoragePath)
                .where(PrototypeRevisionEntity::getVersionId, version)
                .gt(PrototypeRevisionEntity::getId, after)
                .orderBy(SortOrder.asc(PrototypeRevisionEntity::getId))
                .paging(0, BATCH_SIZE)
                .fetch()
                .collectList();
    }

    private Mono<Void> deleteVersionGraph(String id) {
        return reviewThreads
                .purgeVersion(id)
                .then(review.purgeVersion(id))
                .then(
                        sessions.createDelete()
                                .where(PrototypePreviewSessionEntity::getVersionId, id)
                                .execute())
                .then(shares.createDelete().where(PrototypeShareEntity::getVersionId, id).execute())
                .then(
                        receipts.createDelete()
                                .where(PrototypeCreationReceiptEntity::getResourceId, id)
                                .execute())
                .then(
                        revisions
                                .createDelete()
                                .where(PrototypeRevisionEntity::getVersionId, id)
                                .execute())
                .then(versions.deleteById(id))
                .then();
    }

    /** 任意状态子行都阻止父行清理；年轻或异常活动子版本不会因父到期而提前丢失。 */
    public Mono<Boolean> purgeProject(String id) {
        return projects.createQuery()
                .where(PrototypeProjectEntity::getId, id)
                .forUpdate()
                .fetchOne()
                .filter(project -> expired(project.getDeletedTime(), System.currentTimeMillis()))
                .flatMap(
                        project ->
                                versions.createQuery()
                                        .select(PrototypeVersionEntity::getId)
                                        .where(PrototypeVersionEntity::getProjectId, id)
                                        .paging(0, 1)
                                        .fetchOne()
                                        .hasElement())
                .filter(hasChildren -> !hasChildren)
                .flatMap(
                        ignored ->
                                sessions.createDelete()
                                        .where(PrototypePreviewSessionEntity::getProjectId, id)
                                        .execute()
                                        .then(
                                                shares.createDelete()
                                                        .where(
                                                                PrototypeShareEntity::getProjectId,
                                                                id)
                                                        .execute())
                                        .then(
                                                members.createDelete()
                                                        .where(
                                                                PrototypeProjectMemberEntity
                                                                        ::getProjectId,
                                                                id)
                                                        .execute())
                                        .then(
                                                receipts.createDelete()
                                                        .where(
                                                                PrototypeCreationReceiptEntity
                                                                        ::getResourceId,
                                                                id)
                                                        .execute())
                                        .then(reviewThreads.purgeProject(id))
                                        .then(review.purgeProject(id))
                                        .then(projects.deleteById(id))
                                        .thenReturn(true))
                .defaultIfEmpty(false)
                .as(transactions::transactional);
    }

    private record Purged(int versions, int projects) {}
}
