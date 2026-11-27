package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeWorkItemEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.info.PageTraceSourceRef;
import com.gaiys.proto.prototypehub.web.request.PageTraceRequests;
import com.gaiys.proto.prototypehub.web.response.PageTraceResponse.ResolvedSource;
import com.gaiys.proto.prototypehub.web.response.PageTraceResponse.SourceCandidate;
import com.gaiys.proto.prototypehub.web.response.PageTraceResponse.SourceDetail;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** F46授权边界内的来源投影；调用方先验证当前成员与精确页面，写入时持有版本锁。 四类来源仅分批读取指定ID，不复制内容、不反向修改来源，也不暴露资料存储路径。 */
@Service
@RequiredArgsConstructor
public class PrototypePageTraceSourceService {
    private final ReactiveRepository<PrototypeWorkItemEntity, String> workItems;
    private final ReactiveRepository<PrototypeRevisionMaterialEntity, String> materials;
    private final ReactiveRepository<PrototypeElementDescriptionEntity, String> descriptions;
    private final ReactiveRepository<PrototypeViewportCheckEntity, String> checks;

    Mono<PagerResult<SourceCandidate>> query(
            PrototypeRevisionEntity fixed, String page, PageTraceRequests.SourceQuery input) {
        var param = scope(fixed, page, input.getKind());
        param.doPaging(input.getPageIndex(), input.getPageSize());
        param.setParallelPager(true);
        return switch (input.getKind()) {
            case "work-item" ->
                    QueryHelper.queryPager(param, this::workQuery, row -> work(row).candidate());
            case "material" ->
                    QueryHelper.queryPager(
                            param, this::materialQuery, row -> material(row).candidate());
            case "description" ->
                    QueryHelper.queryPager(
                            param, this::descriptionQuery, row -> description(row).candidate());
            case "check" ->
                    QueryHelper.queryPager(param, this::checkQuery, row -> check(row).candidate());
            default -> Mono.error(PrototypePageTraceService.invalid());
        };
    }

    Mono<SourceDetail> detail(PrototypeRevisionEntity fixed, String page, String kind, String id) {
        if (!PageTraceSourceRef.KINDS.contains(kind))
            return Mono.error(PrototypePageTraceService.invalid());
        return load(fixed, page, kind, List.of(id))
                .singleOrEmpty()
                .switchIfEmpty(
                        Mono.error(() -> PrototypeHubException.missing("page_trace_source")));
    }

    /** 每种来源最多40个ID；范围条件和ID集合都进入SQL，然后才LIMIT和有界装配。 */
    Mono<Map<String, SourceDetail>> load(
            PrototypeRevisionEntity fixed, String page, List<PageTraceSourceRef> refs) {
        return Flux.fromIterable(PageTraceSourceRef.KINDS)
                .concatMap(
                        kind -> {
                            var ids =
                                    refs.stream()
                                            .filter(ref -> ref.kind().equals(kind))
                                            .map(PageTraceSourceRef::id)
                                            .toList();
                            return ids.isEmpty() ? Flux.empty() : load(fixed, page, kind, ids);
                        })
                .collectMap(row -> key(row.kind(), row.id()));
    }

    private Flux<SourceDetail> load(
            PrototypeRevisionEntity fixed, String page, String kind, List<String> ids) {
        var param = scope(fixed, page, kind);
        // IDs来自经验证的<=40指针；只返回当前授权范围，其他页或修订的同ID视为缺失。
        param.toQuery().in("id", ids);
        param.doPaging(0, 40);
        return switch (kind) {
            case "work-item" ->
                    workQuery().setParam(param).fetch().map(PrototypePageTraceSourceService::work);
            case "material" ->
                    materialQuery()
                            .setParam(param)
                            .fetch()
                            .map(PrototypePageTraceSourceService::material);
            case "description" ->
                    descriptionQuery()
                            .setParam(param)
                            .fetch()
                            .map(PrototypePageTraceSourceService::description);
            case "check" ->
                    checkQuery()
                            .setParam(param)
                            .fetch()
                            .map(PrototypePageTraceSourceService::check);
            default -> Flux.error(PrototypePageTraceService.invalid());
        };
    }

    private QueryParamEntity scope(PrototypeRevisionEntity fixed, String page, String kind) {
        // 四个既有实体共享这些固定范围字段，不能接受任意外部QueryParam。
        var query =
                QueryParamEntity.newQuery()
                        .where("projectId", fixed.getProjectId())
                        .and("versionId", fixed.getVersionId())
                        .and("revisionId", fixed.getId());
        if (!"material".equals(kind)) query.and("pageId", page);
        return query.orderByDesc("createdAt").orderByAsc("id").getParam();
    }

    static List<ResolvedSource> resolve(
            List<PageTraceSourceRef> refs, Map<String, SourceDetail> live) {
        return refs.stream()
                .map(
                        ref -> {
                            var row = live.get(key(ref.kind(), ref.id()));
                            return new ResolvedSource(
                                    ref.kind(),
                                    ref.id(),
                                    ref.expectedVersion(),
                                    row == null
                                            ? "missing"
                                            : row.configVersion() == ref.expectedVersion()
                                                    ? "current"
                                                    : "changed",
                                    row == null ? null : row.configVersion(),
                                    row == null ? null : row.title());
                        })
                .toList();
    }

    static void validateAttach(
            List<PageTraceSourceRef> before,
            List<PageTraceSourceRef> next,
            Map<String, SourceDetail> live) {
        Set<PageTraceSourceRef> retained = Set.copyOf(before);
        for (var ref : next) {
            // 未变指针可保持changed/missing，让成员仍能保存入口或移除另一来源；新指针必须在同锁内核版本。
            if (retained.contains(ref)) continue;
            var row = live.get(key(ref.kind(), ref.id()));
            if (row == null) throw PrototypeHubException.missing("page_trace_source");
            if (row.configVersion() != ref.expectedVersion())
                throw new PrototypeHubException(
                        "error.prototype-hub.page_trace_source_changed",
                        "PROTOTYPE_HUB_PAGE_TRACE_SOURCE_CHANGED",
                        409);
        }
    }

    private static String key(String kind, String id) {
        return kind + ":" + id;
    }

    private static String plain(String value) {
        return Objects.toString(value, "");
    }

    private ReactiveQuery<PrototypeWorkItemEntity> workQuery() {
        return workItems
                .createQuery()
                .select(
                        PrototypeWorkItemEntity::getId,
                        PrototypeWorkItemEntity::getUrl,
                        PrototypeWorkItemEntity::getNote,
                        PrototypeWorkItemEntity::getConfigVersion);
    }

    private ReactiveQuery<PrototypeRevisionMaterialEntity> materialQuery() {
        return materials
                .createQuery()
                .select(
                        PrototypeRevisionMaterialEntity::getId,
                        PrototypeRevisionMaterialEntity::getMaterialType,
                        PrototypeRevisionMaterialEntity::getFileName,
                        PrototypeRevisionMaterialEntity::getSourceName,
                        PrototypeRevisionMaterialEntity::getSourceNotes,
                        PrototypeRevisionMaterialEntity::getSourceUrl,
                        PrototypeRevisionMaterialEntity::getConfigVersion);
    }

    private ReactiveQuery<PrototypeElementDescriptionEntity> descriptionQuery() {
        return descriptions
                .createQuery()
                .select(
                        PrototypeElementDescriptionEntity::getId,
                        PrototypeElementDescriptionEntity::getTitle,
                        PrototypeElementDescriptionEntity::getPurpose,
                        PrototypeElementDescriptionEntity::getInputRules,
                        PrototypeElementDescriptionEntity::getPermissionRules,
                        PrototypeElementDescriptionEntity::getNavigationRules,
                        PrototypeElementDescriptionEntity::getNotes,
                        PrototypeElementDescriptionEntity::getConfigVersion);
    }

    private ReactiveQuery<PrototypeViewportCheckEntity> checkQuery() {
        return checks.createQuery()
                .select(
                        PrototypeViewportCheckEntity::getId,
                        PrototypeViewportCheckEntity::getTitle,
                        PrototypeViewportCheckEntity::getCategory,
                        PrototypeViewportCheckEntity::getExpected,
                        PrototypeViewportCheckEntity::getObserved,
                        PrototypeViewportCheckEntity::getEvidence,
                        PrototypeViewportCheckEntity::getResult,
                        PrototypeViewportCheckEntity::getSteps,
                        PrototypeViewportCheckEntity::getConfigVersion);
    }

    private static SourceDetail work(PrototypeWorkItemEntity row) {
        return new SourceDetail(
                "work-item",
                row.getId(),
                row.getNote() == null || row.getNote().isBlank() ? row.getUrl() : row.getNote(),
                row.getConfigVersion(),
                Map.of("url", row.getUrl(), "note", plain(row.getNote())));
    }

    private static SourceDetail material(PrototypeRevisionMaterialEntity row) {
        return new SourceDetail(
                "material",
                row.getId(),
                row.getSourceName() == null || row.getSourceName().isBlank()
                        ? plain(row.getFileName())
                        : row.getSourceName(),
                row.getConfigVersion(),
                Map.of(
                        "type",
                        row.getMaterialType(),
                        "description",
                        plain(row.getSourceNotes()),
                        "url",
                        plain(row.getSourceUrl())));
    }

    private static SourceDetail description(PrototypeElementDescriptionEntity row) {
        return new SourceDetail(
                "description",
                row.getId(),
                row.getTitle(),
                row.getConfigVersion(),
                Map.of(
                        "purpose",
                        plain(row.getPurpose()),
                        "inputRules",
                        plain(row.getInputRules()),
                        "permissionRules",
                        plain(row.getPermissionRules()),
                        "navigationRules",
                        plain(row.getNavigationRules()),
                        "notes",
                        plain(row.getNotes())));
    }

    private static SourceDetail check(PrototypeViewportCheckEntity row) {
        return new SourceDetail(
                "check",
                row.getId(),
                row.getTitle(),
                row.getConfigVersion(),
                Map.of(
                        "category",
                        row.getCategory(),
                        "expected",
                        row.getExpected(),
                        "observed",
                        row.getObserved(),
                        "evidence",
                        plain(row.getEvidence()),
                        "result",
                        row.getResult(),
                        "steps",
                        plain(row.getSteps())));
    }
}
