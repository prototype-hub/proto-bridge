package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareVisitEntity;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.service.keys.PrototypeVisitTickets;
import com.gaiys.proto.prototypehub.service.query.PrototypeStatisticsQuery;
import com.gaiys.proto.prototypehub.web.response.PrototypeStatisticsResponse;
import com.gaiys.proto.prototypehub.web.response.PrototypeStatisticsResponse.Trend;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 外层分享访问采集和当前授权统计；不是人数、资源请求日志或跨站遥测。 使用数据库唯一事件和现有锁序跨副本去重，不建立内存队列或缓存；失败不影响独立的原型读取请求。 */
@Service
@RequiredArgsConstructor
public class PrototypeStatisticsService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int RETENTION_DAYS = 35;
    private final PrototypeShareService shares;
    private final PrototypeProjectAccessService access;
    private final PrototypeInstallationService installations;
    private final PrototypeStatisticsQuery queries;
    private final ReactiveRepository<PrototypeShareVisitEntity, String> visits;
    private final DatabaseOperator database;
    private final EntityTableMetadataResolver metadata;
    private final TransactionalOperator transactions;
    private final ObservationRegistry observations;

    /** 项目→版本锁后再次鉴权，确保永久清理和分享配置变更不会留下未经授权或孤立事件。 */
    public Mono<Void> record(String token, String key, String revisionId, String ticket) {
        return Mono.defer(
                () -> {
                    Observation observation =
                            Observation.createNotStarted("prototype.share.visit", observations)
                                    .start();
                    return shares.requireActive(token)
                            .flatMap(
                                    before ->
                                            access.lockProject(before.getProjectId())
                                                    .then(access.lockVersion(before.getVersionId()))
                                                    .then(shares.requireActive(token)))
                            .flatMap(
                                    share ->
                                            shares.requireRevisionAccess(token, key, revisionId)
                                                    .then(persistVisit(share, revisionId, ticket)))
                            .as(transactions::transactional)
                            .doOnError(observation::error)
                            .doFinally(signal -> observation.stop());
                });
    }

    private Mono<Void> persistVisit(PrototypeShareEntity share, String revisionId, String ticket) {
        return installations
                .current()
                .flatMap(
                        installation -> {
                            long now = System.currentTimeMillis();
                            String eventId =
                                    PrototypeVisitTickets.verify(
                                            installation.getStatisticsSigningKey(),
                                            share,
                                            revisionId,
                                            ticket,
                                            now);
                            return database.dml()
                                    .upsert(metadata.resolve(PrototypeShareVisitEntity.class))
                                    .columns(
                                            "id",
                                            "projectId",
                                            "versionId",
                                            "revisionId",
                                            "shareId",
                                            "visitDay",
                                            "receivedAt")
                                    .values(
                                            eventId,
                                            share.getProjectId(),
                                            share.getVersionId(),
                                            revisionId,
                                            share.getId(),
                                            day(now).toString(),
                                            now)
                                    .doNothingOnConflict(true)
                                    .execute()
                                    .reactive()
                                    .then();
                        });
    }

    public Mono<PrototypeStatisticsResponse> summary(int days) {
        return access.current().flatMap(actor -> summary(actor, days));
    }

    private Mono<PrototypeStatisticsResponse> summary(Actor actor, int days) {
        long now = System.currentTimeMillis();
        LocalDate to = day(now), from = to.minusDays(days - 1L);
        boolean versionQuery =
                actor.authentication().hasPermission("prototype-hub-version", "query");
        return Mono.zip(
                        installations.current(),
                        queries.projects(actor),
                        versionQuery ? queries.versions(actor) : Mono.just(-1L))
                .map(
                        base ->
                                new PrototypeStatisticsResponse(
                                        ZONE.getId(),
                                        from.toString(),
                                        to.toString(),
                                        base.getT1().getStatisticsStartedAt(),
                                        RETENTION_DAYS,
                                        base.getT2(),
                                        versionQuery ? base.getT3() : null,
                                        null,
                                        null,
                                        null,
                                        null))
                .flatMap(
                        base ->
                                actor.authentication().hasPermission("prototype-hub-share", "query")
                                        ? withVisits(base, actor, now)
                                        : Mono.just(base));
    }

    private Mono<PrototypeStatisticsResponse> withVisits(
            PrototypeStatisticsResponse base, Actor actor, long now) {
        return Mono.zip(
                        queries.shares(actor, now),
                        queries.trend(actor, base.fromDate(), base.toDate()),
                        queries.ranking(actor, base.fromDate(), base.toDate()))
                .map(
                        data ->
                                new PrototypeStatisticsResponse(
                                        base.timeZone(),
                                        base.fromDate(),
                                        base.toDate(),
                                        base.collectedSince(),
                                        base.retentionDays(),
                                        base.projectCount(),
                                        base.versionCount(),
                                        data.getT1(),
                                        data.getT2().values().stream()
                                                .mapToLong(Long::longValue)
                                                .sum(),
                                        trend(base, data.getT2()),
                                        data.getT3()));
    }

    private static List<Trend> trend(PrototypeStatisticsResponse base, Map<String, Long> counts) {
        LocalDate start = day(base.collectedSince());
        return LocalDate.parse(base.fromDate())
                .datesUntil(LocalDate.parse(base.toDate()).plusDays(1))
                .map(
                        date ->
                                new Trend(
                                        date.toString(),
                                        date.isBefore(start)
                                                ? null
                                                : counts.getOrDefault(date.toString(), 0L)))
                .toList();
    }

    /** 沿现有维护周期每次最多删除500条；过期票据无法让已清理事件重新计数。 */
    public Mono<Void> cleanup() {
        return Mono.defer(
                () ->
                        visits.createQuery()
                                .select(PrototypeShareVisitEntity::getId)
                                .lt(
                                        PrototypeShareVisitEntity::getVisitDay,
                                        day(System.currentTimeMillis())
                                                .minusDays(RETENTION_DAYS - 1L)
                                                .toString())
                                .orderBy(
                                        SortOrder.asc(PrototypeShareVisitEntity::getVisitDay),
                                        SortOrder.asc(PrototypeShareVisitEntity::getId))
                                .paging(0, 500)
                                .fetch()
                                .map(PrototypeShareVisitEntity::getId)
                                .collectList()
                                .filter(ids -> !ids.isEmpty())
                                .flatMap(
                                        ids ->
                                                visits.createDelete()
                                                        .in(PrototypeShareVisitEntity::getId, ids)
                                                        .execute())
                                .then());
    }

    public Mono<Void> purgeVersion(String id) {
        return visits.createDelete()
                .where(PrototypeShareVisitEntity::getVersionId, id)
                .execute()
                .then();
    }

    public Mono<Void> purgeProject(String id) {
        return visits.createDelete()
                .where(PrototypeShareVisitEntity::getProjectId, id)
                .execute()
                .then();
    }

    static LocalDate day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate();
    }
}
