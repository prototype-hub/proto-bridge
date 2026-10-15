package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.entity.RoleMenuGrantEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import com.gaiys.proto.system.service.tree.MenuControllerTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.TreeSupportEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.event.ClearUserAuthorizationCacheEvent;
import org.hswebframework.web.system.authorization.defaults.service.DefaultAuthorizationSettingService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Translates trusted menu metadata into hsweb role grants and builds personal menu trees.
 *
 * <p>Role and data permission resolution remains owned by hsweb; menu selection only controls the
 * visible menus and buttons and never grants actions beyond the authenticated operator.
 */
@Service
@RequiredArgsConstructor
public class MenuGrantService {
    private final MenuService menus;
    private final RoleMenuGrantService grants;
    private final SystemManagementService management;
    private final DefaultAuthorizationSettingService settings;
    private final DefaultDimensionService dimensions;
    private static final int MENU_GRANT_PRIORITY = 10;
    private final ApplicationEventPublisher events;

    public static Map<String, String> actions() {
        return Map.ofEntries(
                Map.entry("add", "save"),
                Map.entry("update", "save"),
                Map.entry("action", "save"),
                Map.entry("bind", "save"),
                Map.entry("delete", "delete"),
                Map.entry("query", "query"),
                Map.entry("grant", "grant"),
                Map.entry("export", "query"),
                Map.entry("down", "query"),
                Map.entry("import", "save"),
                Map.entry("groupUpdate", "save"),
                Map.entry("groupDelete", "delete"));
    }

    public static Map<String, Set<String>> permissions(MenuEntity menu, List<String> buttons) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (menu.getPermission() != null && !menu.getPermission().isBlank())
            result.computeIfAbsent(menu.getPermission(), key -> new LinkedHashSet<>())
                    .add(menu.getAction() == null ? "query" : menu.getAction());
        add(result, menu.getPermissions());
        if (menu.getButtons() != null)
            for (Map<String, Object> button : menu.getButtons())
                if (buttons.contains(Objects.toString(button.get("id"), ""))) {
                    if (menu.getPermission() != null)
                        result.computeIfAbsent(menu.getPermission(), key -> new LinkedHashSet<>())
                                .add(
                                        actions()
                                                .getOrDefault(
                                                        Objects.toString(button.get("id")),
                                                        "query"));
                    Object extra = button.get("permissions");
                    if (extra instanceof List<?> list) {
                        for (Object item : list)
                            if (item instanceof Map<?, ?> entry) addOne(result, entry);
                    }
                }
        return result;
    }

    private static void add(Map<String, Set<String>> result, List<Map<String, Object>> values) {
        if (values != null) values.forEach(value -> addOne(result, value));
    }

    private static void addOne(Map<String, Set<String>> result, Map<?, ?> value) {
        Object key = value.containsKey("permission") ? value.get("permission") : value.get("id");
        if (key == null) return;
        Object actions = value.get("actions");
        if (actions instanceof Collection<?> list)
            for (Object action : list)
                result.computeIfAbsent(key.toString(), id -> new LinkedHashSet<>())
                        .add(action.toString());
    }

    @Transactional
    public Mono<Void> grant(String roleId, List<Map<String, Object>> request) {
        if (request == null || request.size() > 500) return Mono.error(ManagementSupport.invalid());
        Map<String, List<String>> selected = new LinkedHashMap<>();
        for (Map<String, Object> input : request) {
            if (input == null
                    || !(input.get("id") instanceof String id)
                    || !id.matches("[A-Za-z0-9_-]{1,64}"))
                return Mono.error(ManagementSupport.invalid());
            List<String> buttons = new ArrayList<>();
            Object inputButtons = input.get("buttons");
            if (inputButtons != null && !(inputButtons instanceof List<?>))
                return Mono.error(ManagementSupport.invalid());
            if (inputButtons instanceof List<?> list) {
                for (Object button : list) {
                    if (!(button instanceof Map<?, ?> b)
                            || !(b.get("id") instanceof String buttonId)
                            || buttonId.isBlank()
                            || (b.get("granted") != null && !(b.get("granted") instanceof Boolean)))
                        return Mono.error(ManagementSupport.invalid());
                    if (!Boolean.FALSE.equals(b.get("granted"))) buttons.add(buttonId);
                }
            }
            selected.put(id, buttons);
        }
        return management
                .dimension("role", roleId)
                .then(
                        (selected.isEmpty()
                                        ? Flux.<MenuEntity>empty()
                                        : menus.findById(new ArrayList<>(selected.keySet())))
                                .collectList())
                .flatMap(
                        found -> {
                            if (found.size() != selected.size())
                                return Mono.error(ManagementSupport.missing());
                            Map<String, Set<String>> desired = new LinkedHashMap<>();
                            for (MenuEntity menu : found) {
                                Set<String> known = new HashSet<>();
                                if (menu.getButtons() != null)
                                    menu.getButtons()
                                            .forEach(
                                                    button ->
                                                            known.add(
                                                                    Objects.toString(
                                                                            button.get("id"), "")));
                                if (!known.containsAll(selected.get(menu.getId())))
                                    return Mono.error(ManagementSupport.invalid());
                            }
                            for (MenuEntity menu : found)
                                permissions(menu, selected.get(menu.getId()))
                                        .forEach(
                                                (id, actions) ->
                                                        desired.computeIfAbsent(
                                                                        id,
                                                                        key ->
                                                                                new LinkedHashSet<>())
                                                                .addAll(actions));
                            return Authentication.currentReactive()
                                    .flatMap(
                                            auth -> {
                                                for (var entry : desired.entrySet())
                                                    for (String action : entry.getValue())
                                                        if (!auth.hasPermission(
                                                                entry.getKey(), action))
                                                            return Mono.error(
                                                                    new AccessDenyException());
                                                RoleMenuGrantEntity empty =
                                                        new RoleMenuGrantEntity();
                                                empty.setId(roleId);
                                                return grants.findById(roleId)
                                                        .defaultIfEmpty(empty)
                                                        .flatMap(
                                                                old -> {
                                                                    Set<String> previous =
                                                                            old
                                                                                                    .getManagedPermissions()
                                                                                            == null
                                                                                    ? Set.of()
                                                                                    : old
                                                                                            .getManagedPermissions();
                                                                    Set<String> removed =
                                                                            new HashSet<>(previous);
                                                                    removed.removeAll(
                                                                            desired.keySet());
                                                                    Mono<Void> remove =
                                                                            removed.isEmpty()
                                                                                    ? Mono.empty()
                                                                                    : settings.createDelete()
                                                                                            .where(
                                                                                                    AuthorizationSettingEntity
                                                                                                            ::getDimensionType,
                                                                                                    "role")
                                                                                            .and(
                                                                                                    AuthorizationSettingEntity
                                                                                                            ::getDimensionTarget,
                                                                                                    roleId)
                                                                                            .in(
                                                                                                    AuthorizationSettingEntity
                                                                                                            ::getPermission,
                                                                                                    removed)
                                                                                            .execute()
                                                                                            .then();
                                                                    List<AuthorizationSettingEntity>
                                                                            values =
                                                                                    new ArrayList<>();
                                                                    desired.forEach(
                                                                            (id, actions) -> {
                                                                                AuthorizationSettingEntity
                                                                                        value =
                                                                                                new AuthorizationSettingEntity();
                                                                                value.setPermission(
                                                                                        id);
                                                                                value
                                                                                        .setDimensionType(
                                                                                                "role");
                                                                                value
                                                                                        .setDimensionTarget(
                                                                                                roleId);
                                                                                value.setState(
                                                                                        (byte) 1);
                                                                                value.setActions(
                                                                                        actions);
                                                                                // Menu grants
                                                                                // compose across
                                                                                // roles; explicit
                                                                                // higher-priority
                                                                                // hsweb settings
                                                                                // still apply.
                                                                                value.setPriority(
                                                                                        MENU_GRANT_PRIORITY);
                                                                                value.setMerge(
                                                                                        true);
                                                                                values.add(value);
                                                                            });
                                                                    old.setMenus(selected);
                                                                    old.setManagedPermissions(
                                                                            desired.keySet());
                                                                    return remove.then(
                                                                                    values.isEmpty()
                                                                                            ? Mono
                                                                                                    .empty()
                                                                                            : saveSettings(
                                                                                                    roleId,
                                                                                                    values))
                                                                            .then(grants.save(old))
                                                                            .then(
                                                                                    ClearUserAuthorizationCacheEvent
                                                                                            .all()
                                                                                            .publish(
                                                                                                    events));
                                                                });
                                            });
                        });
    }

    private Mono<Void> saveSettings(String roleId, List<AuthorizationSettingEntity> values) {
        return settings.createQuery()
                .where(AuthorizationSettingEntity::getDimensionType, "role")
                .and(AuthorizationSettingEntity::getDimensionTarget, roleId)
                .fetch()
                .collectMap(AuthorizationSettingEntity::getPermission)
                .flatMap(
                        existing -> {
                            for (AuthorizationSettingEntity value : values) {
                                AuthorizationSettingEntity old =
                                        existing.get(value.getPermission());
                                if (old != null) {
                                    value.setId(old.getId());
                                    value.setDataAccesses(old.getDataAccesses());
                                    // Editing menu actions must retain an explicit authorization
                                    // priority and data boundary.
                                    value.setPriority(
                                            Math.max(
                                                    MENU_GRANT_PRIORITY,
                                                    old.getPriority() == null
                                                            ? 0
                                                            : old.getPriority()));
                                }
                            }
                            return settings.save(values).then();
                        });
    }

    public Mono<List<MenuEntity>> roleTree(String roleId) {
        return roleTree(roleId, new QueryParamEntity());
    }

    public Mono<List<MenuEntity>> roleTree(String roleId, QueryParamEntity source) {
        QueryParamEntity query = menus.treeQuery(source);
        return management
                .dimension("role", roleId)
                .then(menus.query(query).collectList())
                .zipWith(grants.findById(roleId).defaultIfEmpty(new RoleMenuGrantEntity()))
                .flatMap(
                        tuple -> {
                            List<MenuEntity> found = tuple.getT1();
                            RoleMenuGrantEntity stored = tuple.getT2();
                            Mono<Map<String, List<String>>> selection =
                                    stored.getId() == null
                                            ? legacySelection(roleId, found)
                                            : Mono.just(
                                                    stored.getMenus() == null
                                                            ? Map.of()
                                                            : stored.getMenus());
                            return selection.map(
                                    selected -> {
                                        for (MenuEntity menu : found) {
                                            menu.setGranted(selected.containsKey(menu.getId()));
                                            if (menu.getButtons() != null)
                                                menu.getButtons()
                                                        .forEach(
                                                                button ->
                                                                        button.put(
                                                                                "granted",
                                                                                selected.getOrDefault(
                                                                                                menu
                                                                                                        .getId(),
                                                                                                List
                                                                                                        .of())
                                                                                        .contains(
                                                                                                Objects
                                                                                                        .toString(
                                                                                                                button
                                                                                                                        .get(
                                                                                                                                "id"),
                                                                                                                ""))));
                                        }
                                        return TreeSupportEntity.list2tree(
                                                found,
                                                menus::setChildren,
                                                menus::createRootNodePredicate);
                                    });
                        });
    }

    // Roles created through the official authorization API have no menu selection record yet.
    private Mono<Map<String, List<String>>> legacySelection(String roleId, List<MenuEntity> found) {
        return settings.createQuery()
                .where(AuthorizationSettingEntity::getDimensionType, "role")
                .and(AuthorizationSettingEntity::getDimensionTarget, roleId)
                .and(AuthorizationSettingEntity::getState, 1)
                .fetch()
                .collectMap(
                        AuthorizationSettingEntity::getPermission,
                        value -> value.getActions() == null ? Set.<String>of() : value.getActions())
                .map(
                        available -> {
                            Map<String, List<String>> selected = new LinkedHashMap<>();
                            Map<String, MenuEntity> all = new HashMap<>();
                            found.forEach(menu -> all.put(menu.getId(), menu));
                            for (MenuEntity menu : found) {
                                Map<String, Set<String>> required = permissions(menu, List.of());
                                if (required.isEmpty() || !containsActions(available, required))
                                    continue;
                                List<String> buttons = new ArrayList<>();
                                if (menu.getButtons() != null)
                                    for (Map<String, Object> button : menu.getButtons()) {
                                        String id = Objects.toString(button.get("id"), "");
                                        if (containsActions(
                                                available, permissions(menu, List.of(id))))
                                            buttons.add(id);
                                    }
                                selected.put(menu.getId(), buttons);
                            }
                            for (String id : new ArrayList<>(selected.keySet())) {
                                Set<String> visited = new HashSet<>();
                                MenuEntity child = all.get(id);
                                while (child != null
                                        && child.getParentId() != null
                                        && !child.getParentId().isBlank()) {
                                    if (!visited.add(child.getId()))
                                        throw ManagementSupport.invalid();
                                    MenuEntity parent = all.get(child.getParentId());
                                    if (parent == null) break;
                                    selected.putIfAbsent(parent.getId(), List.of());
                                    child = parent;
                                }
                            }
                            return selected;
                        });
    }

    private static boolean containsActions(
            Map<String, Set<String>> available, Map<String, Set<String>> required) {
        return required.entrySet().stream()
                .allMatch(
                        entry ->
                                available
                                        .getOrDefault(entry.getKey(), Set.of())
                                        .containsAll(entry.getValue()));
    }

    public Mono<List<MenuEntity>> own(Authentication auth) {
        List<String> roleIds = auth.getDimensions("role").stream().map(d -> d.getId()).toList();
        if (roleIds.isEmpty()) return Mono.just(List.of());
        // hsweb retains dimensions for disabled roles while excluding their settings. Apply
        // the same state boundary before unioning menu selection or enabling legacy fallback.
        return dimensions
                .createQuery()
                .where(DimensionEntity::getTypeId, "role")
                .in(DimensionEntity::getId, roleIds)
                .fetch()
                .filter(
                        role ->
                                role.getProperties() == null
                                        || !"disabled".equals(role.getProperties().get("state")))
                .map(DimensionEntity::getId)
                .collectList()
                .flatMap(activeRoles -> own(auth, activeRoles));
    }

    private Mono<List<MenuEntity>> own(Authentication auth, List<String> roleIds) {
        return (roleIds.isEmpty() ? Flux.<RoleMenuGrantEntity>empty() : grants.findById(roleIds))
                .collectList()
                .zipWith(menus.createQuery().fetch().collectList())
                .map(
                        tuple -> {
                            Map<String, Set<String>> selected = new HashMap<>();
                            Set<String> managed = new HashSet<>();
                            for (RoleMenuGrantEntity grant : tuple.getT1()) {
                                managed.add(grant.getId());
                                if (grant.getMenus() != null)
                                    grant.getMenus()
                                            .forEach(
                                                    (id, buttons) ->
                                                            selected.computeIfAbsent(
                                                                            id,
                                                                            key -> new HashSet<>())
                                                                    .addAll(buttons));
                            }
                            boolean legacy = roleIds.stream().anyMatch(id -> !managed.contains(id));
                            Map<String, MenuEntity> all = new LinkedHashMap<>();
                            tuple.getT2().forEach(menu -> all.put(menu.getId(), menu));
                            Set<String> visible = new HashSet<>();
                            for (MenuEntity menu : all.values()) {
                                boolean allowed =
                                        Boolean.TRUE.equals(menu.getEnabled())
                                                && (legacy || selected.containsKey(menu.getId()))
                                                && allowed(auth, permissions(menu, List.of()));
                                menu.setGranted(allowed);
                                if (allowed) visible.add(menu.getId());
                                if (menu.getButtons() != null)
                                    for (Map<String, Object> button : menu.getButtons()) {
                                        String id = Objects.toString(button.get("id"), "");
                                        button.put(
                                                "granted",
                                                allowed
                                                        && (legacy
                                                                || selected.getOrDefault(
                                                                                menu.getId(),
                                                                                Set.of())
                                                                        .contains(id))
                                                        && allowed(
                                                                auth,
                                                                permissions(menu, List.of(id))));
                                    }
                            }
                            for (String id : new ArrayList<>(visible)) {
                                Set<String> path = new HashSet<>();
                                MenuEntity child = all.get(id);
                                while (child != null
                                        && child.getParentId() != null
                                        && !child.getParentId().isBlank()) {
                                    if (!path.add(child.getId())) throw ManagementSupport.invalid();
                                    MenuEntity parent = all.get(child.getParentId());
                                    if (parent == null || !Boolean.TRUE.equals(parent.getEnabled()))
                                        break;
                                    visible.add(parent.getId());
                                    parent.setGranted(true);
                                    child = parent;
                                }
                            }
                            return MenuControllerTree.tree(
                                    all.values().stream()
                                            .filter(menu -> visible.contains(menu.getId()))
                                            .toList(),
                                    null);
                        });
    }

    private static boolean allowed(Authentication auth, Map<String, Set<String>> permissions) {
        return permissions.entrySet().stream()
                .allMatch(
                        entry ->
                                entry.getValue().stream()
                                        .allMatch(
                                                action ->
                                                        auth.hasPermission(
                                                                entry.getKey(), action)));
    }
}
