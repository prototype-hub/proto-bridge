package com.gaiys.proto.prototypehub.service.query;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareVisitEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.response.PrototypeStatisticsResponse.Ranking;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 权限、聚合与排序均在数据库执行；QueryHelper没有分组API，因此使用已有jOOQ结构化查询和EasyORM执行器。
 * 不接收SQL片段或客户端列名，标识符来自实体元数据，值全部绑定；不缓存账号授权或加载全量项目。
 */
@Component
@RequiredArgsConstructor
public class PrototypeStatisticsQuery {
    private final DatabaseOperator database;
    private final EntityTableMetadataResolver metadata;

    public Mono<Long> projects(Actor actor) {
        return count(
                context().select(DSL.count().as("n")).from(projects()).where(scope(actor, false)));
    }

    public Mono<Long> versions(Actor actor) {
        return count(
                context()
                        .select(DSL.count().as("n"))
                        .from(versions())
                        .join(projects())
                        .on(text("v", "project_id").eq(text("p", "id")))
                        .where(
                                scope(actor, false)
                                        .and(field("v", "deleted_time", Long.class).isNull())));
    }

    public Mono<Long> shares(Actor actor, long now) {
        var revision =
                context()
                        .selectOne()
                        .from(table(PrototypeRevisionEntity.class, "r"))
                        .where(text("r", "project_id").eq(text("p", "id")))
                        .and(text("r", "version_id").eq(text("v", "id")))
                        .and(
                                text("s", "revision_mode")
                                        .eq("latest")
                                        .and(text("r", "id").eq(text("v", "current_revision_id")))
                                        .or(
                                                text("s", "revision_mode")
                                                        .isNull()
                                                        .or(text("s", "revision_mode").eq("fixed"))
                                                        .and(
                                                                text("r", "id")
                                                                        .eq(
                                                                                text(
                                                                                        "s",
                                                                                        "revision_id")))));
        Condition active =
                field("s", "enabled", Boolean.class)
                        .eq(true)
                        .and(
                                field("s", "revoked", Boolean.class)
                                        .isNull()
                                        .or(field("s", "revoked", Boolean.class).eq(false)))
                        .and(
                                field("s", "expires_at", Long.class)
                                        .isNull()
                                        .or(field("s", "expires_at", Long.class).gt(now)))
                        .and(PrototypeTextTermFragmentBuilder.ownerPresent(text("s", "access_key")))
                        .and(
                                text("s", "revision_mode")
                                        .eq("latest")
                                        .and(text("s", "revision_id").isNull())
                                        .or(
                                                text("s", "revision_mode")
                                                        .isNull()
                                                        .or(text("s", "revision_mode").eq("fixed"))
                                                        .and(text("s", "revision_id").isNotNull())))
                        .andExists(revision);
        return count(
                context()
                        .select(DSL.count().as("n"))
                        .from(table(PrototypeShareEntity.class, "s"))
                        .join(versions())
                        .on(text("s", "version_id").eq(text("v", "id")))
                        .join(projects())
                        .on(text("s", "project_id").eq(text("p", "id")))
                        .where(
                                scope(actor, true)
                                        .and(text("v", "project_id").eq(text("p", "id")))
                                        .and(field("v", "deleted_time", Long.class).isNull())
                                        .and(active)));
    }

    public Mono<Map<String, Long>> trend(Actor actor, String from, String to) {
        return rows(context()
                        .select(text("e", "visit_day").as("day"), DSL.count().as("n"))
                        .from(events())
                        .join(projects())
                        .on(text("e", "project_id").eq(text("p", "id")))
                        .join(versions())
                        .on(text("e", "version_id").eq(text("v", "id")))
                        .where(eventScope(actor, from, to))
                        .groupBy(text("e", "visit_day")))
                .collectMap(
                        row -> (String) row.get("day"), row -> ((Number) row.get("n")).longValue());
    }

    public Mono<List<Ranking>> ranking(Actor actor, String from, String to) {
        return rows(context()
                        .select(
                                text("p", "id").as("project_id"),
                                text("p", "name").as("project_name"),
                                DSL.count().as("n"))
                        .from(events())
                        .join(projects())
                        .on(text("e", "project_id").eq(text("p", "id")))
                        .join(versions())
                        .on(text("e", "version_id").eq(text("v", "id")))
                        .where(eventScope(actor, from, to))
                        .groupBy(text("p", "id"), text("p", "name"))
                        .orderBy(DSL.count().desc(), text("p", "id").asc())
                        .limit(10))
                .map(
                        row ->
                                new Ranking(
                                        (String) row.get("project_id"),
                                        (String) row.get("project_name"),
                                        ((Number) row.get("n")).longValue()))
                .collectList();
    }

    private Condition eventScope(Actor actor, String from, String to) {
        return scope(actor, true)
                .and(field("v", "deleted_time", Long.class).isNull())
                .and(text("v", "project_id").eq(text("p", "id")))
                .and(text("e", "visit_day").between(from, to));
    }

    /** 沿当前项目角色语义；viewer不能读取分享管理或其访问统计，即使有通用share查询动作。 */
    private Condition scope(Actor actor, boolean shares) {
        Condition active = field("p", "deleted_time", Long.class).isNull();
        if (actor.administrator()) return active;
        var member =
                context()
                        .selectOne()
                        .from(table(PrototypeProjectMemberEntity.class, "m"))
                        .where(text("m", "project_id").eq(text("p", "id")))
                        .and(text("m", "user_id").eq(actor.userId()))
                        .and(
                                text("m", "role")
                                        .in(
                                                shares
                                                        ? List.of(ProjectRole.editor.name())
                                                        : List.of(
                                                                ProjectRole.editor.name(),
                                                                ProjectRole.viewer.name())));
        return active.and(PrototypeTextTermFragmentBuilder.ownerPresent(text("p", "owner_id")))
                .and(text("p", "owner_id").eq(actor.userId()).orExists(member));
    }

    private Mono<Long> count(Select<?> query) {
        return rows(query).single().map(row -> ((Number) row.get("n")).longValue());
    }

    private Flux<Map<String, Object>> rows(Select<?> query) {
        return database.sql()
                .reactive()
                .select(
                        SqlRequests.of(query.getSQL(), query.getBindValues().toArray()),
                        ResultWrappers.map());
    }

    private DSLContext context() {
        String dialect = metadata.resolve(PrototypeProjectEntity.class).getDialect().getId();
        return DSL.using(
                switch (dialect) {
                    case "PostgreSQL" -> SQLDialect.POSTGRES;
                    case "h2database" -> SQLDialect.H2;
                    default ->
                            throw new UnsupportedOperationException(
                                    "Statistics dialect is unsupported: " + dialect);
                });
    }

    private Table<?> projects() {
        return table(PrototypeProjectEntity.class, "p");
    }

    private Table<?> versions() {
        return table(PrototypeVersionEntity.class, "v");
    }

    private Table<?> events() {
        return table(PrototypeShareVisitEntity.class, "e");
    }

    private Table<?> table(Class<?> type, String alias) {
        var mapped = metadata.resolve(type);
        return DSL.table(DSL.name(mapped.getSchema().getName(), mapped.getName())).as(alias);
    }

    private static Field<String> text(String alias, String column) {
        return field(alias, column, String.class);
    }

    private static <T> Field<T> field(String alias, String column, Class<T> type) {
        return DSL.field(DSL.name(alias, column), type);
    }
}
