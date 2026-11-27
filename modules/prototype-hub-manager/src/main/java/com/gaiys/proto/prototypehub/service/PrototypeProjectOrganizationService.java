package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectFavoriteEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectTagEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Grant;
import com.gaiys.proto.prototypehub.web.request.ProjectOrganizationRequests;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared organization and private favorites have separate CAS counters and common durable receipts.
 */
@Service
@RequiredArgsConstructor
public class PrototypeProjectOrganizationService {
    private static final long MAX_VERSION = 9007199254740991L;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final ReactiveRepository<PrototypeProjectTagEntity, String> tags;
    private final ReactiveRepository<PrototypeProjectFavoriteEntity, String> favorites;
    private final PrototypeProjectAccessService access;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;

    public Mono<ProjectResponse> organization(
            String id, ProjectOrganizationRequests.Organization input) {
        return locked(id, Action.EDIT)
                .flatMap(scope -> organizationLocked(id, input, scope))
                .as(transactions::transactional)
                .checkpoint("prototype-hub/project/organization");
    }

    private Mono<ProjectResponse> organizationLocked(
            String id, ProjectOrganizationRequests.Organization input, Locked scope) {
        var labels = input.normalizedTags();
        String group = input.getGroupName();
        if (group != null && group.isEmpty()) group = null;
        var values =
                new ArrayList<>(
                        Arrays.asList(
                                id,
                                group,
                                input.getArchived().toString(),
                                input.getExpectedVersion().toString()));
        values.addAll(labels);
        var request =
                receipts.request(
                        scope.actor().userId(),
                        "project-organization-update",
                        id,
                        input.getClientRequestId(),
                        values.toArray(String[]::new));
        String nextGroup = group;
        return receipts.replay(request, ignored -> response(id, scope.actor(), Action.EDIT))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        persistOrganization(
                                                id, input, scope, nextGroup, labels, request)));
    }

    private Mono<ProjectResponse> persistOrganization(
            String id,
            ProjectOrganizationRequests.Organization input,
            Locked scope,
            String group,
            java.util.List<String> labels,
            PrototypeCreationReceiptService.Request request) {
        var row = scope.grant().project();
        long previous = row.getOrganizationVersion() == null ? 0 : row.getOrganizationVersion();
        requireVersion(previous, input.getExpectedVersion());
        Long archived =
                input.getArchived()
                        ? row.getArchivedTime() == null
                                ? System.currentTimeMillis()
                                : row.getArchivedTime()
                        : null;
        var update =
                projects.createUpdate()
                        .set(PrototypeProjectEntity::getOrganizationVersion, previous + 1)
                        .set(PrototypeProjectEntity::getModifierId, scope.actor().userId())
                        .set(PrototypeProjectEntity::getModifyTime, System.currentTimeMillis())
                        .where(PrototypeProjectEntity::getId, id);
        if (group == null) update.setNull(PrototypeProjectEntity::getGroupName);
        else update.set(PrototypeProjectEntity::getGroupName, group);
        if (archived == null) update.setNull(PrototypeProjectEntity::getArchivedTime);
        else update.set(PrototypeProjectEntity::getArchivedTime, archived);
        // Legacy NULL is version zero, but the database CAS still matches the original physical
        // value.
        if (row.getOrganizationVersion() == null)
            update.isNull(PrototypeProjectEntity::getOrganizationVersion);
        else update.and(PrototypeProjectEntity::getOrganizationVersion, previous);
        return update.execute()
                .flatMap(changed -> changed == 1 ? Mono.empty() : Mono.error(conflict()))
                .then(replaceTags(id, labels))
                .then(receipts.record(request, id))
                .then(response(id, scope.actor(), Action.EDIT));
    }

    private Mono<Void> replaceTags(String id, java.util.List<String> labels) {
        return tags.createDelete()
                .where(PrototypeProjectTagEntity::getProjectId, id)
                .execute()
                .thenMany(
                        Flux.fromIterable(labels)
                                .map(
                                        label -> {
                                            var tag = new PrototypeProjectTagEntity();
                                            tag.setId(
                                                    UUID.randomUUID().toString().replace("-", ""));
                                            tag.setProjectId(id);
                                            tag.setTagName(label);
                                            return tag;
                                        }))
                .as(rows -> tags.insert(rows).then());
    }

    public Mono<ProjectResponse> favorite(String id, ProjectOrganizationRequests.Favorite input) {
        return locked(id, Action.READ)
                .flatMap(scope -> favoriteLocked(id, input, scope.actor()))
                .as(transactions::transactional)
                .checkpoint("prototype-hub/project/favorite");
    }

    private Mono<ProjectResponse> favoriteLocked(
            String id, ProjectOrganizationRequests.Favorite input, Actor actor) {
        var request =
                receipts.request(
                        actor.userId(),
                        "project-favorite-update",
                        id,
                        input.getClientRequestId(),
                        id,
                        input.getFavorite().toString(),
                        input.getExpectedVersion().toString());
        return receipts.replay(request, ignored -> response(id, actor, Action.READ))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        favorites
                                                .createQuery()
                                                .where(
                                                        PrototypeProjectFavoriteEntity
                                                                ::getProjectId,
                                                        id)
                                                .and(
                                                        PrototypeProjectFavoriteEntity::getUserId,
                                                        actor.userId())
                                                .fetchOne()
                                                .defaultIfEmpty(newFavorite(id, actor.userId()))
                                                .flatMap(
                                                        row ->
                                                                persistFavorite(
                                                                        id, input, actor, row,
                                                                        request))));
    }

    private Mono<ProjectResponse> persistFavorite(
            String id,
            ProjectOrganizationRequests.Favorite input,
            Actor actor,
            PrototypeProjectFavoriteEntity row,
            PrototypeCreationReceiptService.Request request) {
        long previous = row.getConfigVersion();
        requireVersion(previous, input.getExpectedVersion());
        boolean insert = row.getCreatedAt() == null;
        long now = System.currentTimeMillis();
        row.setFavorite(input.getFavorite());
        row.setConfigVersion(previous + 1);
        row.setUpdatedAt(now);
        Mono<?> write;
        if (insert) {
            row.setCreatedAt(now);
            write = favorites.insert(row);
        } else {
            write =
                    favorites
                            .createUpdate()
                            .set(PrototypeProjectFavoriteEntity::getFavorite, row.getFavorite())
                            .set(
                                    PrototypeProjectFavoriteEntity::getConfigVersion,
                                    row.getConfigVersion())
                            .set(PrototypeProjectFavoriteEntity::getUpdatedAt, now)
                            .where(PrototypeProjectFavoriteEntity::getId, row.getId())
                            .and(PrototypeProjectFavoriteEntity::getConfigVersion, previous)
                            .execute()
                            .flatMap(
                                    changed ->
                                            changed == 1 ? Mono.empty() : Mono.error(conflict()));
        }
        // False rows and original receipts survive until account/project permanent cleanup.
        return write.then(receipts.record(request, id)).then(response(id, actor, Action.READ));
    }

    private Mono<Locked> locked(String id, Action action) {
        return access.current()
                .flatMap(
                        actor ->
                                access.lockActiveUsers(actor.userId())
                                        .then(access.lockProject(id))
                                        .then(access.actor(actor.userId()))
                                        .flatMap(
                                                current ->
                                                        authorize(id, current, action)
                                                                .map(
                                                                        grant ->
                                                                                new Locked(
                                                                                        current,
                                                                                        grant))));
    }

    private Mono<Grant> authorize(String id, Actor actor, Action action) {
        if (!actor.authentication()
                .hasPermission("prototype-hub-project", action == Action.READ ? "query" : "save"))
            return Mono.error(PrototypeProjectAccessService.roleDenied());
        return access.requireProject(actor, id, action, true);
    }

    private Mono<ProjectResponse> response(String id, Actor actor, Action action) {
        return authorize(id, actor, action).flatMap(access::response);
    }

    private static PrototypeProjectFavoriteEntity newFavorite(String id, String user) {
        var row = new PrototypeProjectFavoriteEntity();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setProjectId(id);
        row.setUserId(user);
        row.setConfigVersion(0L);
        row.setFavorite(false);
        return row;
    }

    private static void requireVersion(long current, long expected) {
        if (current != expected || current >= MAX_VERSION) throw conflict();
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.project_organization_conflict",
                "PROTOTYPE_HUB_PROJECT_ORGANIZATION_CONFLICT",
                409);
    }

    private record Locked(Actor actor, Grant grant) {}
}
