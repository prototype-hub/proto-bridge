package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.entity.OrganizationEntity;
import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.entity.RoleGroupEntity;
import com.gaiys.proto.system.service.MenuService;
import com.gaiys.proto.system.service.OrganizationService;
import com.gaiys.proto.system.service.PositionService;
import com.gaiys.proto.system.service.RoleGroupService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.crud.events.EntityBeforeCreateEvent;
import org.hswebframework.web.crud.events.EntityBeforeModifyEvent;
import org.hswebframework.web.crud.events.EntityBeforeSaveEvent;
import org.hswebframework.web.crud.service.ReactiveTreeSortEntityService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Runs for all hsweb CRUD entry points, including generic PUT/PATCH requests. */
@Component
@RequiredArgsConstructor
public class ManagementEntityValidation {
    private final OrganizationService organizations;
    private final PositionService positions;
    private final RoleGroupService groups;
    private final MenuService menus;
    private final DefaultDimensionService dimensions;

    private <E extends GenericTreeSortSupportEntity<String>> Mono<Void> trees(
            List<E> rows, ReactiveTreeSortEntityService<E, String> service) {
        Map<String, E> pending = new HashMap<>();
        rows.forEach(e -> pending.put(e.getId(), e));
        return Flux.fromIterable(rows)
                .concatMap(
                        e -> {
                            if (e.getId() == null || !e.getId().matches("[A-Za-z0-9_-]{1,64}"))
                                return Mono.error(ManagementSupport.invalid());
                            return parent(
                                    e,
                                    e.getParentId(),
                                    service,
                                    pending,
                                    new HashSet<>(Set.of(e.getId())),
                                    0);
                        })
                .then();
    }

    private <E extends GenericTreeSortSupportEntity<String>> Mono<Void> parent(
            E child,
            String id,
            ReactiveTreeSortEntityService<E, String> service,
            Map<String, E> pending,
            Set<String> seen,
            int depth) {
        if (id == null || id.isBlank()) return Mono.empty();
        if (depth > 20 || !seen.add(id)) return Mono.error(ManagementSupport.invalid());
        Mono<E> found = pending.containsKey(id) ? Mono.just(pending.get(id)) : service.findById(id);
        return found.switchIfEmpty(Mono.error(ManagementSupport.missing()))
                .flatMap(
                        p -> {
                            if (child instanceof PositionEntity c
                                    && p instanceof PositionEntity other
                                    && !Objects.equals(c.getOrgId(), other.getOrgId()))
                                return Mono.error(ManagementSupport.invalid());
                            return parent(
                                    child, p.getParentId(), service, pending, seen, depth + 1);
                        });
    }

    private Mono<Void> position(List<PositionEntity> rows) {
        return trees(rows, positions)
                .thenMany(Flux.fromIterable(rows))
                .concatMap(
                        e ->
                                organizations
                                        .findById(e.getOrgId())
                                        .switchIfEmpty(Mono.error(ManagementSupport.missing()))
                                        .thenMany(
                                                Flux.fromIterable(
                                                                ManagementSupport.ids(e.getRoles()))
                                                        .concatMap(
                                                                id ->
                                                                        dimensions
                                                                                .findById(id)
                                                                                .filter(
                                                                                        d ->
                                                                                                "role"
                                                                                                        .equals(
                                                                                                                d
                                                                                                                        .getTypeId()))
                                                                                .switchIfEmpty(
                                                                                        Mono.error(
                                                                                                ManagementSupport
                                                                                                        .missing()))))
                                        .then())
                .then();
    }

    @EventListener
    public void orgCreate(EntityBeforeCreateEvent<OrganizationEntity> e) {
        e.async(trees(e.getEntity(), organizations));
    }

    @EventListener
    public void orgSave(EntityBeforeSaveEvent<OrganizationEntity> e) {
        e.async(trees(e.getEntity(), organizations));
    }

    @EventListener
    public void orgModify(EntityBeforeModifyEvent<OrganizationEntity> e) {
        e.async(trees(e.getAfter(), organizations));
    }

    @EventListener
    public void posCreate(EntityBeforeCreateEvent<PositionEntity> e) {
        e.async(position(e.getEntity()));
    }

    @EventListener
    public void posSave(EntityBeforeSaveEvent<PositionEntity> e) {
        e.async(position(e.getEntity()));
    }

    @EventListener
    public void posModify(EntityBeforeModifyEvent<PositionEntity> e) {
        e.async(position(e.getAfter()));
    }

    @EventListener
    public void groupCreate(EntityBeforeCreateEvent<RoleGroupEntity> e) {
        e.async(trees(e.getEntity(), groups));
    }

    @EventListener
    public void groupSave(EntityBeforeSaveEvent<RoleGroupEntity> e) {
        e.async(trees(e.getEntity(), groups));
    }

    @EventListener
    public void groupModify(EntityBeforeModifyEvent<RoleGroupEntity> e) {
        e.async(trees(e.getAfter(), groups));
    }

    @EventListener
    public void menuCreate(EntityBeforeCreateEvent<MenuEntity> e) {
        e.async(trees(e.getEntity(), menus));
    }

    @EventListener
    public void menuSave(EntityBeforeSaveEvent<MenuEntity> e) {
        e.async(trees(e.getEntity(), menus));
    }

    @EventListener
    public void menuModify(EntityBeforeModifyEvent<MenuEntity> e) {
        e.async(trees(e.getAfter(), menus));
    }
}
