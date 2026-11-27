package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectFavoriteEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectTagEntity;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Bounded response enrichment; callers own project access and pass at most one page/chunk. */
@Component
@RequiredArgsConstructor
public class PrototypeProjectOrganizationData {
    private final ReactiveRepository<PrototypeProjectTagEntity, String> tags;
    private final ReactiveRepository<PrototypeProjectFavoriteEntity, String> favorites;

    public Mono<List<ProjectResponse>> enrich(List<ProjectResponse> page, String user) {
        if (page.isEmpty()) return Mono.just(List.of());
        var ids = page.stream().map(ProjectResponse::id).toList();
        return tags.createQuery()
                .in(PrototypeProjectTagEntity::getProjectId, ids)
                .orderBy(
                        org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(
                                PrototypeProjectTagEntity::getTagName))
                .fetch()
                .collectMultimap(
                        PrototypeProjectTagEntity::getProjectId,
                        PrototypeProjectTagEntity::getTagName)
                .flatMap(
                        labels ->
                                favorites
                                        .createQuery()
                                        .where(PrototypeProjectFavoriteEntity::getUserId, user)
                                        .in(PrototypeProjectFavoriteEntity::getProjectId, ids)
                                        .fetch()
                                        .collectMap(PrototypeProjectFavoriteEntity::getProjectId)
                                        .map(
                                                saved ->
                                                        page.stream()
                                                                .map(
                                                                        row -> {
                                                                            var favorite =
                                                                                    saved.get(
                                                                                            row
                                                                                                    .id());
                                                                            return row
                                                                                    .withPersonalOrganization(
                                                                                            List
                                                                                                    .copyOf(
                                                                                                            labels
                                                                                                                    .getOrDefault(
                                                                                                                            row
                                                                                                                                    .id(),
                                                                                                                            List
                                                                                                                                    .of())),
                                                                                            favorite
                                                                                                            != null
                                                                                                    && Boolean
                                                                                                            .TRUE
                                                                                                            .equals(
                                                                                                                    favorite
                                                                                                                            .getFavorite()),
                                                                                            favorite
                                                                                                            == null
                                                                                                    ? 0
                                                                                                    : favorite
                                                                                                            .getConfigVersion());
                                                                        })
                                                                .toList()));
    }
}
