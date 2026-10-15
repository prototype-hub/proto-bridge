package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.OrganizationEntity;
import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 职位详情的关联字段查询与显示适配，由职位控制器调用。 使用官方查询解析关联名称，保留原条件分组；角色名称以官方维度实时值为准，不维护独立缓存。 */
@Service
@RequiredArgsConstructor
public class PositionQueryService {
    private final PositionService positions;
    private final OrganizationService organizations;
    private final DefaultDimensionService dimensions;
    private final DefaultDimensionUserService bindings;

    public Mono<QueryParamEntity> resolve(QueryParamEntity source) {
        QueryParamEntity query = QueryParamEntity.of(source);
        return resolveTerms(query.getTerms()).thenReturn(query);
    }

    private Mono<Void> resolveTerms(List<Term> terms) {
        return Flux.fromIterable(terms)
                .concatMap(
                        term ->
                                resolveTerm(term)
                                        .then(Mono.defer(() -> resolveTerms(term.getTerms()))))
                .then();
    }

    private Mono<Void> resolveTerm(Term term) {
        if ("orgName".equals(term.getColumn()) || "parentName".equals(term.getColumn()))
            return resolveRelatedName(term);
        if ("roles".equals(term.getColumn())) return resolveRoleNames(term);
        if ("id".equals(term.getColumn())
                && ("position-role".equals(term.getTermType())
                        || term.getOptions().contains("position"))) return resolveRoleIds(term);
        return Mono.empty();
    }

    private QueryParamEntity predicate(Term original, String column) {
        Term predicate = original.clone();
        predicate.setColumn(column);
        predicate.setType(Term.Type.and);
        predicate.getTerms().clear();
        QueryParamEntity query = QueryParamEntity.of();
        query.addTerm(predicate);
        query.getIncludes().add("id");
        query.noPaging();
        return query;
    }

    private Mono<Void> resolveRelatedName(Term term) {
        boolean organization = "orgName".equals(term.getColumn());
        boolean includeMissing = "isnull".equals(term.getTermType());
        QueryParamEntity query = predicate(term, "name");
        if (includeMissing) {
            query.getTerms().get(0).setTermType("notnull");
            query.getTerms().get(0).getOptions().clear();
        }
        Mono<List<String>> relatedIds =
                organization
                        ? organizations.query(query).map(OrganizationEntity::getId).collectList()
                        : positions.query(query).map(PositionEntity::getId).collectList();
        return relatedIds.flatMap(
                ids -> {
                    if (!includeMissing) {
                        rewrite(term, organization ? "orgId" : "parentId", ids, false);
                        return Mono.empty();
                    }
                    Set<String> nonNullNames = new HashSet<>(ids);
                    // 缺失关联、根职位的空 parentId 均按显示字段 NULL 处理。
                    return positionReferences()
                            .filter(
                                    row ->
                                            !nonNullNames.contains(
                                                    organization
                                                            ? row.getOrgId()
                                                            : row.getParentId()))
                            .map(PositionEntity::getId)
                            .collectList()
                            .doOnNext(matched -> rewrite(term, "id", matched, false))
                            .then();
                });
    }

    private Mono<Void> resolveRoleNames(Term term) {
        boolean noRoles = "isnull".equals(term.getTermType());
        boolean anyRoles = "notnull".equals(term.getTermType());
        Map<String, String> positive =
                Map.of("not", "eq", "nlike", "like", "nin", "in", "nbtw", "btw", "nempty", "empty");
        boolean negativeOperator = positive.containsKey(term.getTermType());
        boolean exclude = (noRoles || negativeOperator) != term.getOptions().contains("not");
        QueryParamEntity query = predicate(term, "name");
        Term name = query.getTerms().get(0);
        if (noRoles || anyRoles) name.setTermType("notnull");
        else if (negativeOperator) name.setTermType(positive.get(term.getTermType()));
        name.getOptions().removeIf("not"::equals);
        query.toNestQuery().and("typeId", "role");
        return dimensions
                .query(query)
                .map(DimensionEntity::getId)
                .collectList()
                .flatMap(ids -> positionsWithRoles(term, ids, exclude));
    }

    private Mono<Void> resolveRoleIds(Term term) {
        List<String> targets =
                ManagementSupport.ids(
                        term.getValue() instanceof Collection<?>
                                ? term.getValue()
                                : List.of(Objects.toString(term.getValue(), "")));
        boolean exclude =
                term.getOptions().contains("not")
                        || Set.of("not", "nin").contains(term.getTermType());
        Mono<List<String>> actual =
                targets.isEmpty()
                        ? Mono.just(List.of())
                        : dimensions
                                .createQuery()
                                .where(DimensionEntity::getTypeId, "role")
                                .in(DimensionEntity::getId, targets)
                                .fetch()
                                .map(DimensionEntity::getId)
                                .collectList();
        return actual.flatMap(ids -> positionsWithRoles(term, ids, exclude));
    }

    private Mono<Void> positionsWithRoles(Term term, List<String> roleIds, boolean exclude) {
        if (roleIds.isEmpty()) {
            rewrite(term, "id", List.of(), exclude);
            return Mono.empty();
        }
        Set<String> matchingRoles = new HashSet<>(roleIds);
        // 多角色的否定条件是“不含匹配角色”，因此也包含未分配角色的职位。
        return positionReferences()
                .filter(
                        row ->
                                row.getRoles() != null
                                        && row.getRoles().stream()
                                                .anyMatch(
                                                        role ->
                                                                matchingRoles.contains(
                                                                        role.get("id"))))
                .map(PositionEntity::getId)
                .collectList()
                .doOnNext(ids -> rewrite(term, "id", ids, exclude))
                .then();
    }

    private Flux<PositionEntity> positionReferences() {
        return positions.createQuery().select("id", "orgId", "parentId", "roles").fetch();
    }

    private void rewrite(Term term, String column, List<String> ids, boolean exclude) {
        term.setColumn(ids.isEmpty() ? "id" : column);
        term.getOptions().clear();
        // 主键非空的恒真/恒假条件防止空 IN 被框架忽略，原 AND/OR 和子条件保持不变。
        term.setTermType(
                ids.isEmpty() ? (exclude ? "notnull" : "isnull") : (exclude ? "nin" : "in"));
        term.setValue(ids.isEmpty() ? "" : ids);
    }

    public Mono<List<PositionEntity>> details(List<PositionEntity> rows) {
        if (rows.isEmpty()) return Mono.just(rows);
        List<String> orgIds =
                rows.stream()
                        .map(PositionEntity::getOrgId)
                        .filter(Objects::nonNull)
                        .filter(id -> !id.isBlank())
                        .distinct()
                        .toList();
        List<String> parentIds =
                rows.stream()
                        .map(PositionEntity::getParentId)
                        .filter(Objects::nonNull)
                        .filter(id -> !id.isBlank())
                        .distinct()
                        .toList();
        List<String> roleIds =
                rows.stream()
                        .filter(row -> row.getRoles() != null)
                        .flatMap(row -> row.getRoles().stream())
                        .map(role -> role.get("id"))
                        .filter(Objects::nonNull)
                        .filter(id -> !id.isBlank())
                        .distinct()
                        .toList();
        Mono<Map<String, OrganizationEntity>> orgs =
                orgIds.isEmpty()
                        ? Mono.just(Map.of())
                        : organizations.findById(orgIds).collectMap(OrganizationEntity::getId);
        Mono<Map<String, PositionEntity>> parents =
                parentIds.isEmpty()
                        ? Mono.just(Map.of())
                        : positions.findById(parentIds).collectMap(PositionEntity::getId);
        Mono<Map<String, DimensionEntity>> roles =
                roleIds.isEmpty()
                        ? Mono.just(Map.of())
                        : dimensions
                                .createQuery()
                                .where(DimensionEntity::getTypeId, "role")
                                .in(DimensionEntity::getId, roleIds)
                                .fetch()
                                .collectMap(DimensionEntity::getId);
        List<String> positionIds =
                rows.stream()
                        .map(PositionEntity::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Mono<Map<String, Collection<String>>> members =
                positionIds.isEmpty()
                        ? Mono.just(Map.of())
                        : bindings.createQuery()
                                .where(DimensionUserEntity::getDimensionTypeId, "position")
                                .in(DimensionUserEntity::getDimensionId, positionIds)
                                .fetch()
                                .collectMultimap(
                                        DimensionUserEntity::getDimensionId,
                                        DimensionUserEntity::getUserId);
        return Mono.zip(orgs, parents, roles, members)
                .map(
                        tuple -> {
                            for (PositionEntity row : rows) {
                                row.setMemberCount(
                                        tuple.getT4().getOrDefault(row.getId(), List.of()).stream()
                                                .distinct()
                                                .count());
                                OrganizationEntity org =
                                        row.getOrgId() == null
                                                ? null
                                                : tuple.getT1().get(row.getOrgId());
                                row.setOrgName(org == null ? "" : org.getName());
                                PositionEntity parent =
                                        row.getParentId() == null
                                                ? null
                                                : tuple.getT2().get(row.getParentId());
                                row.setParentName(parent == null ? "" : parent.getName());
                                row.setRoles(
                                        row.getRoles() == null
                                                ? List.of()
                                                : row.getRoles().stream()
                                                        .map(
                                                                role ->
                                                                        tuple.getT3()
                                                                                .get(
                                                                                        role.get(
                                                                                                "id")))
                                                        .filter(Objects::nonNull)
                                                        .map(
                                                                role ->
                                                                        Map.of(
                                                                                "id",
                                                                                role.getId(),
                                                                                "name",
                                                                                role.getName()))
                                                        .toList());
                            }
                            return rows;
                        });
    }
}
