package com.gaiys.proto.system.service.reconciliation;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.entity.RoleMenuGrantEntity;
import com.gaiys.proto.system.service.MenuGrantService;
import com.gaiys.proto.system.service.MenuService;
import com.gaiys.proto.system.service.RoleMenuGrantService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.event.ClearUserAuthorizationCacheEvent;
import org.hswebframework.web.system.authorization.defaults.service.DefaultAuthorizationSettingService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reduces managed role grants in the same transaction as menu edits and deletions. */
@Component
@RequiredArgsConstructor
public class MenuGrantReconciliation {
    public static final String REPLACEMENT =
            MenuGrantReconciliation.class.getName() + ".replacement";
    private final MenuService menus;
    private final RoleMenuGrantService grants;
    private final DefaultAuthorizationSettingService settings;
    private final ApplicationEventPublisher events;

    @EventListener
    public void saved(EntitySavedEvent<MenuEntity> event) {
        event.async(after(event.getEntity()));
    }

    @EventListener
    public void modified(EntityModifyEvent<MenuEntity> event) {
        event.async(after(event.getAfter()));
    }

    @EventListener
    public void deleted(EntityDeletedEvent<MenuEntity> event) {
        event.async(after(event.getEntity()));
    }

    private Mono<Void> after(List<MenuEntity> changed) {
        Set<String> ids = new HashSet<>();
        changed.forEach(menu -> ids.add(menu.getId()));
        return Mono.deferContextual(
                context ->
                        context.getOrDefault(REPLACEMENT, false) ? Mono.empty() : reconcile(ids));
    }

    public Mono<Void> reconcile(Set<String> changed) {
        if (changed.isEmpty()) return Mono.empty();
        // Load each table once per event batch; updating role selections never emits a menu event.
        return grants.createQuery()
                .fetch()
                .filter(
                        record ->
                                record.getMenus() != null
                                        && record.getMenus().keySet().stream()
                                                .anyMatch(changed::contains))
                .collectList()
                .flatMap(
                        records -> {
                            if (records.isEmpty()) return Mono.empty();
                            List<String> roleIds =
                                    records.stream().map(RoleMenuGrantEntity::getId).toList();
                            return menus.createQuery()
                                    .fetch()
                                    .collectMap(MenuEntity::getId)
                                    .zipWith(
                                            settings.createQuery()
                                                    .where(
                                                            AuthorizationSettingEntity
                                                                    ::getDimensionType,
                                                            "role")
                                                    .in(
                                                            AuthorizationSettingEntity
                                                                    ::getDimensionTarget,
                                                            roleIds)
                                                    .fetch()
                                                    .collectList())
                                    .flatMap(
                                            tuple -> reduce(records, tuple.getT1(), tuple.getT2()));
                        });
    }

    private Mono<Void> reduce(
            List<RoleMenuGrantEntity> records,
            Map<String, MenuEntity> current,
            List<AuthorizationSettingEntity> existing) {
        Map<String, Map<String, AuthorizationSettingEntity>> byRole = new HashMap<>();
        for (AuthorizationSettingEntity value : existing)
            byRole.computeIfAbsent(value.getDimensionTarget(), key -> new HashMap<>())
                    .put(value.getPermission(), value);
        List<String> deleted = new ArrayList<>();
        List<AuthorizationSettingEntity> updated = new ArrayList<>();
        for (RoleMenuGrantEntity record : records) {
            Set<String> managed =
                    record.getManagedPermissions() == null
                            ? Set.of()
                            : record.getManagedPermissions();
            Map<String, AuthorizationSettingEntity> role =
                    byRole.getOrDefault(record.getId(), Map.of());
            Map<String, List<String>> selected = selections(record, current);
            Map<String, Set<String>> desired = permissions(selected, current);
            Map<String, Set<String>> available = new HashMap<>();
            for (String resource : managed) {
                AuthorizationSettingEntity old = role.get(resource);
                if (old == null) continue;
                Set<String> intersection =
                        new HashSet<>(old.getActions() == null ? Set.of() : old.getActions());
                intersection.retainAll(desired.getOrDefault(resource, Set.of()));
                if (!intersection.isEmpty()) available.put(resource, intersection);
            }
            // A changed permission definition cannot convert menu/save into a new role grant.
            selected.entrySet()
                    .removeIf(
                            entry ->
                                    !contains(
                                            available,
                                            MenuGrantService.permissions(
                                                    current.get(entry.getKey()), List.of())));
            selected.replaceAll(
                    (id, buttons) ->
                            buttons.stream()
                                    .filter(
                                            button ->
                                                    contains(
                                                            available,
                                                            MenuGrantService.permissions(
                                                                    current.get(id),
                                                                    List.of(button))))
                                    .toList());
            Map<String, Set<String>> retained = permissions(selected, current);
            Set<String> retainedResources = new HashSet<>();
            for (String resource : managed) {
                AuthorizationSettingEntity old = role.get(resource);
                if (old == null) continue;
                Set<String> intersection =
                        new HashSet<>(old.getActions() == null ? Set.of() : old.getActions());
                intersection.retainAll(retained.getOrDefault(resource, Set.of()));
                if (intersection.isEmpty()) deleted.add(old.getId());
                else {
                    // Keep explicit data access rules, merge and priority; only actions shrink.
                    old.setActions(intersection);
                    updated.add(old);
                    retainedResources.add(resource);
                }
            }
            record.setMenus(selected);
            record.setManagedPermissions(retainedResources);
        }
        Mono<Void> remove =
                deleted.isEmpty()
                        ? Mono.empty()
                        : settings.createDelete()
                                .in(AuthorizationSettingEntity::getId, deleted)
                                .execute()
                                .then();
        Mono<Void> save = updated.isEmpty() ? Mono.empty() : settings.save(updated).then();
        return remove.then(save)
                .then(grants.save(records))
                .then(ClearUserAuthorizationCacheEvent.all().publish(events));
    }

    private static Map<String, List<String>> selections(
            RoleMenuGrantEntity record, Map<String, MenuEntity> current) {
        Map<String, List<String>> selected = new LinkedHashMap<>();
        record.getMenus()
                .forEach(
                        (id, buttons) -> {
                            MenuEntity menu = current.get(id);
                            if (menu == null || Boolean.FALSE.equals(menu.getEnabled())) return;
                            Set<String> known = new HashSet<>();
                            if (menu.getButtons() != null)
                                menu.getButtons()
                                        .forEach(
                                                button ->
                                                        known.add(
                                                                Objects.toString(
                                                                        button.get("id"), "")));
                            selected.put(
                                    id,
                                    buttons == null
                                            ? List.of()
                                            : buttons.stream().filter(known::contains).toList());
                        });
        return selected;
    }

    private static Map<String, Set<String>> permissions(
            Map<String, List<String>> selected, Map<String, MenuEntity> current) {
        Map<String, Set<String>> desired = new HashMap<>();
        selected.forEach(
                (id, buttons) ->
                        MenuGrantService.permissions(current.get(id), buttons)
                                .forEach(
                                        (resource, actions) ->
                                                desired.computeIfAbsent(
                                                                resource, key -> new HashSet<>())
                                                        .addAll(actions)));
        return desired;
    }

    private static boolean contains(
            Map<String, Set<String>> available, Map<String, Set<String>> required) {
        return required.entrySet().stream()
                .allMatch(
                        entry ->
                                available
                                        .getOrDefault(entry.getKey(), Set.of())
                                        .containsAll(entry.getValue()));
    }
}
