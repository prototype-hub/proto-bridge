package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests.SourceRef;
import com.gaiys.proto.prototypehub.web.response.DeliveryDocumentResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 只读会话说明书：固定范围批量读真实来源，FileWork后重验来源及当前query∩READ。 无表、缓存或常驻任务；沿用HTTP/SQL追踪，不记录正文，无需新增MBean。 */
@Service
@RequiredArgsConstructor
public class PrototypeDeliveryDocumentService {
    private final ReactiveRepository<PrototypeElementDescriptionEntity, String> descriptions;
    private final ReactiveRepository<PrototypeRevisionMaterialEntity, String> materials;
    private final ReactiveRepository<PrototypeViewportCheckEntity, String> checks;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeRevisionMaterialFileService files;
    private final DeliveryDocumentRenderer renderer;
    private final FileWork work;

    public Mono<DeliveryDocumentResponse> assemble(
            String version, String revision, DeliveryDocumentRequests.Document input) {
        return Mono.defer(
                        () -> {
                            input.validate(false);
                            return access.current()
                                    .flatMap(
                                            actor ->
                                                    load(actor.userId(), version, revision, input)
                                                            .flatMap(
                                                                    loaded ->
                                                                            project(
                                                                                            loaded,
                                                                                            input,
                                                                                            false)
                                                                                    .flatMap(
                                                                                            doc ->
                                                                                                    verify(
                                                                                                                    actor
                                                                                                                            .userId(),
                                                                                                                    version,
                                                                                                                    revision,
                                                                                                                    input,
                                                                                                                    doc)
                                                                                                            .thenReturn(
                                                                                                                    doc))));
                        })
                .checkpoint("prototype-hub/delivery-document/assemble");
    }

    public Mono<byte[]> export(
            String version,
            String revision,
            DeliveryDocumentRequests.Document input,
            String format) {
        return Mono.defer(
                        () -> {
                            input.validate(true);
                            if (!"markdown".equals(format) && !"html".equals(format))
                                return Mono.error(DeliveryDocumentRequests.invalid());
                            return access.current()
                                    .flatMap(
                                            actor ->
                                                    load(actor.userId(), version, revision, input)
                                                            .flatMap(
                                                                    loaded ->
                                                                            project(
                                                                                            loaded,
                                                                                            input,
                                                                                            true)
                                                                                    .flatMap(
                                                                                            doc ->
                                                                                                    imageBytes(
                                                                                                                    loaded)
                                                                                                            .flatMap(
                                                                                                                    images ->
                                                                                                                            work
                                                                                                                                    .call(
                                                                                                                                            () ->
                                                                                                                                                    renderer
                                                                                                                                                            .export(
                                                                                                                                                                    doc,
                                                                                                                                                                    images,
                                                                                                                                                                    format)))
                                                                                                            .flatMap(
                                                                                                                    bytes ->
                                                                                                                            verify(
                                                                                                                                            actor
                                                                                                                                                    .userId(),
                                                                                                                                            version,
                                                                                                                                            revision,
                                                                                                                                            input,
                                                                                                                                            doc)
                                                                                                                                    .thenReturn(
                                                                                                                                            bytes)))));
                        })
                .checkpoint("prototype-hub/delivery-document/export");
    }

    private Mono<PrototypeVersionEntity> authorize(String user, String version) {
        return access.actor(user)
                .flatMap(
                        actor ->
                                actor.authentication()
                                                .hasPermission("prototype-hub-version", "query")
                                        ? access.requireVersion(actor, version, Action.READ, false)
                                        : Mono.error(PrototypeProjectAccessService.roleDenied()));
    }

    private Mono<Loaded> load(
            String user, String version, String revision, DeliveryDocumentRequests.Document input) {
        return authorize(user, version)
                .flatMap(
                        row ->
                                revisions
                                        .require(row, revision)
                                        .flatMap(revisions::pages)
                                        .flatMap(
                                                pages -> {
                                                    var real =
                                                            new HashMap<
                                                                    String, PrototypePageInfo>();
                                                    index(pages, real);
                                                    for (var page : input.getPages())
                                                        if (!real.containsKey(page.getPageId()))
                                                            return Mono.error(
                                                                    PrototypeHubException.missing(
                                                                            "page"));
                                                    var dids =
                                                            ids(
                                                                    input,
                                                                    page -> page.getDescriptions());
                                                    var mids =
                                                            ids(input, page -> page.getMaterials());
                                                    mids.addAll(
                                                            ids(input, page -> page.getImages()));
                                                    var cids = ids(input, page -> page.getChecks());
                                                    var pageIds =
                                                            input.getPages().stream()
                                                                    .map(
                                                                            DeliveryDocumentRequests
                                                                                            .Page
                                                                                    ::getPageId)
                                                                    .toList();
                                                    // selected
                                                    // IDs、项目/版本/修订/页约束先于LIMIT；不读取默认分页后在内存猜测选中来源。
                                                    Mono<List<PrototypeElementDescriptionEntity>>
                                                            ds =
                                                                    dids.isEmpty()
                                                                            ? Mono.just(List.of())
                                                                            : descriptions
                                                                                    .createQuery()
                                                                                    .where(
                                                                                            PrototypeElementDescriptionEntity
                                                                                                    ::getProjectId,
                                                                                            row
                                                                                                    .getProjectId())
                                                                                    .and(
                                                                                            PrototypeElementDescriptionEntity
                                                                                                    ::getVersionId,
                                                                                            version)
                                                                                    .and(
                                                                                            PrototypeElementDescriptionEntity
                                                                                                    ::getRevisionId,
                                                                                            revision)
                                                                                    .in(
                                                                                            PrototypeElementDescriptionEntity
                                                                                                    ::getPageId,
                                                                                            pageIds)
                                                                                    .in(
                                                                                            PrototypeElementDescriptionEntity
                                                                                                    ::getId,
                                                                                            dids)
                                                                                    .paging(0, 200)
                                                                                    .fetch()
                                                                                    .collectList();
                                                    Mono<List<PrototypeRevisionMaterialEntity>> ms =
                                                            mids.isEmpty()
                                                                    ? Mono.just(List.of())
                                                                    : materials
                                                                            .createQuery()
                                                                            .where(
                                                                                    PrototypeRevisionMaterialEntity
                                                                                            ::getProjectId,
                                                                                    row
                                                                                            .getProjectId())
                                                                            .and(
                                                                                    PrototypeRevisionMaterialEntity
                                                                                            ::getVersionId,
                                                                                    version)
                                                                            .and(
                                                                                    PrototypeRevisionMaterialEntity
                                                                                            ::getRevisionId,
                                                                                    revision)
                                                                            .in(
                                                                                    PrototypeRevisionMaterialEntity
                                                                                            ::getId,
                                                                                    mids)
                                                                            .paging(0, 210)
                                                                            .fetch()
                                                                            .collectList();
                                                    Mono<List<PrototypeViewportCheckEntity>> cs =
                                                            cids.isEmpty()
                                                                    ? Mono.just(List.of())
                                                                    : checks.createQuery()
                                                                            .where(
                                                                                    PrototypeViewportCheckEntity
                                                                                            ::getProjectId,
                                                                                    row
                                                                                            .getProjectId())
                                                                            .and(
                                                                                    PrototypeViewportCheckEntity
                                                                                            ::getVersionId,
                                                                                    version)
                                                                            .and(
                                                                                    PrototypeViewportCheckEntity
                                                                                            ::getRevisionId,
                                                                                    revision)
                                                                            .in(
                                                                                    PrototypeViewportCheckEntity
                                                                                            ::getPageId,
                                                                                    pageIds)
                                                                            .in(
                                                                                    PrototypeViewportCheckEntity
                                                                                            ::getId,
                                                                                    cids)
                                                                            .paging(0, 200)
                                                                            .fetch()
                                                                            .collectList();
                                                    return Mono.zip(ds, ms, cs)
                                                            .map(
                                                                    tuple ->
                                                                            sources(
                                                                                    row
                                                                                            .getProjectId(),
                                                                                    version,
                                                                                    revision,
                                                                                    real,
                                                                                    input,
                                                                                    tuple.getT1(),
                                                                                    tuple.getT2(),
                                                                                    tuple.getT3()));
                                                }));
    }

    private Loaded sources(
            String project,
            String version,
            String revision,
            Map<String, PrototypePageInfo> real,
            DeliveryDocumentRequests.Document input,
            List<PrototypeElementDescriptionEntity> ds,
            List<PrototypeRevisionMaterialEntity> ms,
            List<PrototypeViewportCheckEntity> cs) {
        var descriptionMap = map(ds, PrototypeElementDescriptionEntity::getId);
        var materialMap = map(ms, PrototypeRevisionMaterialEntity::getId);
        var checkMap = map(cs, PrototypeViewportCheckEntity::getId);
        var result = new ArrayList<DeliveryDocumentRenderer.Sources>();
        long imageBytes = 0;
        for (var page : input.getPages()) {
            var selectedDescriptions =
                    select(
                            page.getDescriptions(),
                            descriptionMap,
                            PrototypeElementDescriptionEntity::getConfigVersion);
            if (selectedDescriptions.stream()
                    .anyMatch(row -> !page.getPageId().equals(row.getPageId())))
                throw DeliveryDocumentRequests.changed();
            var selectedChecks =
                    select(
                            page.getChecks(),
                            checkMap,
                            PrototypeViewportCheckEntity::getConfigVersion);
            if (selectedChecks.stream().anyMatch(row -> !page.getPageId().equals(row.getPageId())))
                throw DeliveryDocumentRequests.changed();
            var selectedMaterials =
                    select(
                            page.getMaterials(),
                            materialMap,
                            PrototypeRevisionMaterialEntity::getConfigVersion);
            for (int n = 0; n < selectedMaterials.size(); n++) {
                var row = selectedMaterials.get(n);
                if (page.getMaterials().get(n).isIncludeText()
                        && (!"document".equals(row.getMaterialType())
                                || !"READY".equals(row.getTextStatus())
                                || row.getTextContent() == null))
                    throw DeliveryDocumentRequests.changed();
            }
            var selectedImages =
                    select(
                            page.getImages(),
                            materialMap,
                            PrototypeRevisionMaterialEntity::getConfigVersion);
            for (var row : selectedImages) {
                if (!"image".equals(row.getMaterialType())
                        || !("image/png".equals(row.getMediaType())
                                || "image/jpeg".equals(row.getMediaType())))
                    throw DeliveryDocumentRequests.invalid();
                if (row.getFileSize() == null
                        || row.getFileSize() <= 0
                        || row.getFileSize() > 5L * 1024 * 1024)
                    throw DeliveryDocumentRequests.limit();
                imageBytes += row.getFileSize();
            }
            result.add(
                    new DeliveryDocumentRenderer.Sources(
                            real.get(page.getPageId()),
                            page,
                            selectedDescriptions,
                            selectedMaterials,
                            selectedImages,
                            selectedChecks));
        }
        if (imageBytes > DeliveryDocumentRenderer.MAX_IMAGE_BYTES)
            throw DeliveryDocumentRequests.limit();
        return new Loaded(project, version, revision, result);
    }

    private Mono<DeliveryDocumentResponse> project(
            Loaded loaded, DeliveryDocumentRequests.Document input, boolean export) {
        return work.call(
                () -> {
                    var pages = new ArrayList<DeliveryDocumentResponse.Page>();
                    for (var source : loaded.pages())
                        pages.add(
                                renderer.page(
                                        loaded.project(),
                                        loaded.version(),
                                        loaded.revision(),
                                        input.getLocale(),
                                        source,
                                        export));
                    var response =
                            new DeliveryDocumentResponse(
                                    loaded.project(),
                                    loaded.version(),
                                    loaded.revision(),
                                    input.getLocale(),
                                    input.getTitle(),
                                    input.getIntroduction(),
                                    pages);
                    renderer.checkTextBudget(response);
                    return response;
                });
    }

    private Mono<Void> verify(
            String user,
            String version,
            String revision,
            DeliveryDocumentRequests.Document input,
            DeliveryDocumentResponse expected) {
        // 文件读取/压缩不持数据库锁；完成后重新读取来源与当前身份，过期来源不产生部分下载。
        return load(user, version, revision, input)
                .flatMap(loaded -> project(loaded, input, false))
                .flatMap(
                        actual -> {
                            for (int n = 0; n < actual.pages().size(); n++)
                                if (!actual.pages()
                                        .get(n)
                                        .sourceFingerprint()
                                        .equals(expected.pages().get(n).sourceFingerprint()))
                                    return Mono.error(DeliveryDocumentRequests.changed());
                            return authorize(user, version).then();
                        });
    }

    private Mono<Map<String, byte[]>> imageBytes(Loaded loaded) {
        var rows = new LinkedHashMap<String, PrototypeRevisionMaterialEntity>();
        loaded.pages().forEach(page -> page.images().forEach(row -> rows.put(row.getId(), row)));
        return Flux.fromIterable(rows.values())
                .concatMap(
                        row ->
                                files.read(row)
                                        .onErrorMap(
                                                PrototypeHubException.class,
                                                error ->
                                                        error.getStatus() == 404
                                                                ? DeliveryDocumentRequests.changed()
                                                                : error)
                                        .flatMap(
                                                response ->
                                                        work.call(
                                                                () -> {
                                                                    byte[] bytes =
                                                                            response.getBody()
                                                                                    .getContentAsByteArray();
                                                                    boolean png =
                                                                            bytes.length >= 8
                                                                                    && bytes[0]
                                                                                            == (byte)
                                                                                                    0x89
                                                                                    && bytes[1]
                                                                                            == 'P'
                                                                                    && bytes[2]
                                                                                            == 'N'
                                                                                    && bytes[3]
                                                                                            == 'G'
                                                                                    && bytes[4]
                                                                                            == 13
                                                                                    && bytes[5]
                                                                                            == 10
                                                                                    && bytes[6]
                                                                                            == 26
                                                                                    && bytes[7]
                                                                                            == 10;
                                                                    boolean jpeg =
                                                                            bytes.length >= 3
                                                                                    && bytes[0]
                                                                                            == (byte)
                                                                                                    0xff
                                                                                    && bytes[1]
                                                                                            == (byte)
                                                                                                    0xd8
                                                                                    && bytes[2]
                                                                                            == (byte)
                                                                                                    0xff;
                                                                    if (!("image/png"
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getMediaType())
                                                                                    && png
                                                                            || "image/jpeg"
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getMediaType())
                                                                                    && jpeg))
                                                                        throw DeliveryDocumentRequests
                                                                                .changed();
                                                                    return Map.entry(
                                                                            row.getId(), bytes);
                                                                })),
                        1)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private static void index(List<PrototypePageInfo> pages, Map<String, PrototypePageInfo> real) {
        for (var page : pages) {
            if (!page.folder()) real.put(page.pageId(), page);
            index(page.children(), real);
        }
    }

    private static LinkedHashSet<String> ids(
            DeliveryDocumentRequests.Document input,
            Function<DeliveryDocumentRequests.Page, ? extends List<? extends SourceRef>> getter) {
        var ids = new LinkedHashSet<String>();
        for (var page : input.getPages()) for (var ref : getter.apply(page)) ids.add(ref.getId());
        return ids;
    }

    private static <T> Map<String, T> map(List<T> rows, Function<T, String> id) {
        var map = new HashMap<String, T>();
        for (var row : rows) map.put(id.apply(row), row);
        return map;
    }

    private static <T> List<T> select(
            List<? extends SourceRef> refs, Map<String, T> rows, Function<T, Long> version) {
        var selected = new ArrayList<T>();
        for (var ref : refs) {
            var row = rows.get(ref.getId());
            if (row == null || !ref.getExpectedVersion().equals(version.apply(row)))
                throw DeliveryDocumentRequests.changed();
            selected.add(row);
        }
        return selected;
    }

    private record Loaded(
            String project,
            String version,
            String revision,
            List<DeliveryDocumentRenderer.Sources> pages) {}
}
