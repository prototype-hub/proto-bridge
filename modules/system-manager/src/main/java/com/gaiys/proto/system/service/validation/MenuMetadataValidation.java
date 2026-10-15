package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.MenuService;
import com.gaiys.proto.system.service.reconciliation.MenuGrantReconciliation;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityBeforeCreateEvent;
import org.hswebframework.web.crud.events.EntityBeforeDeleteEvent;
import org.hswebframework.web.crud.events.EntityBeforeModifyEvent;
import org.hswebframework.web.crud.events.EntityBeforeSaveEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Owns menu metadata and immutable owner checks for every official CRUD entry point. */
@Component
@RequiredArgsConstructor
public class MenuMetadataValidation {
    public static final String REPLACEMENT_SNAPSHOT =
            MenuMetadataValidation.class.getName() + ".replacementSnapshot";
    private final MenuService menus;

    @EventListener
    public void beforeCreate(EntityBeforeCreateEvent<MenuEntity> event) {
        event.async(validate(event.getEntity()));
    }

    @EventListener
    public void beforeSave(EntityBeforeSaveEvent<MenuEntity> event) {
        event.async(validate(event.getEntity()));
    }

    @EventListener
    public void beforeModify(EntityBeforeModifyEvent<MenuEntity> event) {
        event.async(validate(event.getAfter()));
    }

    @EventListener
    public void beforeDelete(EntityBeforeDeleteEvent<MenuEntity> event) {
        event.async(
                Mono.deferContextual(
                        context -> {
                            if (context.getOrDefault(MenuGrantReconciliation.REPLACEMENT, false))
                                return Mono.empty();
                            return menus.createQuery()
                                    .fetch()
                                    .collectMap(MenuEntity::getId)
                                    .flatMap(
                                            current -> {
                                                event.getEntity()
                                                        .forEach(
                                                                menu ->
                                                                        current.remove(
                                                                                menu.getId()));
                                                for (MenuEntity row : current.values())
                                                    metadata(row, current);
                                                return Mono.empty();
                                            });
                        }));
    }

    private Mono<Void> validate(List<MenuEntity> rows) {
        return Mono.deferContextual(
                context -> {
                    // Official tree saves are buffered in batches of 200. Validate forward
                    // references
                    // against the complete preflight snapshot, while retaining all metadata guards.
                    Mono<Map<String, MenuEntity>> snapshot =
                            context.hasKey(REPLACEMENT_SNAPSHOT)
                                    ? Mono.just(context.get(REPLACEMENT_SNAPSHOT))
                                    : menus.createQuery().fetch().collectMap(MenuEntity::getId);
                    return snapshot.flatMap(
                            existing -> {
                                Map<String, MenuEntity> resulting = new LinkedHashMap<>(existing);
                                for (MenuEntity row : rows) {
                                    MenuEntity old = existing.get(row.getId());
                                    if (row.getOwner() == null)
                                        row.setOwner(old == null ? "iot" : old.getOwner());
                                    if (old != null
                                            && !Objects.equals(row.getOwner(), old.getOwner()))
                                        return Mono.error(ManagementSupport.invalid());
                                    if (row.getAccessSupport() == null)
                                        row.setAccessSupport(
                                                old == null
                                                        ? "unsupported"
                                                        : old.getAccessSupport());
                                    if (row.getIndirectMenus() == null && old != null)
                                        row.setIndirectMenus(old.getIndirectMenus());
                                    if (row.getAssetType() == null && old != null)
                                        row.setAssetType(old.getAssetType());
                                    resulting.put(row.getId(), row);
                                }
                                for (MenuEntity row : rows) metadata(row, resulting);
                                return Mono.empty();
                            });
                });
    }

    /** Validate the final owner snapshot before deleting any current row. */
    public Map<String, MenuEntity> replacement(
            String owner, List<MenuEntity> rows, Map<String, MenuEntity> existing) {
        owner(owner);
        Map<String, MenuEntity> resulting = new LinkedHashMap<>(existing);
        resulting.values().removeIf(row -> owner.equals(row.getOwner()));
        for (MenuEntity row : rows) {
            MenuEntity old = existing.get(row.getId());
            if (old != null && !owner.equals(old.getOwner())) throw ManagementSupport.invalid();
            if (row.getOwner() != null && !owner.equals(row.getOwner()))
                throw ManagementSupport.invalid();
            row.setOwner(owner);
            if (row.getAccessSupport() == null) row.setAccessSupport("unsupported");
            resulting.put(row.getId(), row);
        }
        for (MenuEntity row : rows) {
            if (row.getParentId() != null && !row.getParentId().isBlank()) {
                MenuEntity parent = resulting.get(row.getParentId());
                if (parent == null || !owner.equals(parent.getOwner()))
                    throw ManagementSupport.invalid();
            }
        }
        // Existing menus of another owner must not acquire dangling indirect references.
        for (MenuEntity row : resulting.values()) metadata(row, resulting);
        return resulting;
    }

    private static void metadata(MenuEntity row, Map<String, MenuEntity> resulting) {
        owner(row.getOwner());
        if (row.getDescribe() != null && row.getDescribe().length() > 1024)
            throw ManagementSupport.invalid();
        if (row.getAssetType() != null
                && !row.getAssetType().isBlank()
                && !row.getAssetType().matches("[A-Za-z0-9_.:-]{1,64}"))
            throw ManagementSupport.invalid();
        if (!Set.of("unsupported", "support", "indirect").contains(row.getAccessSupport()))
            throw ManagementSupport.invalid();
        if ("support".equals(row.getAccessSupport())
                && (row.getAssetType() == null || row.getAssetType().isBlank()))
            throw ManagementSupport.invalid();
        List<String> references = row.getIndirectMenus();
        if ("indirect".equals(row.getAccessSupport())
                && (references == null || references.isEmpty())) throw ManagementSupport.invalid();
        if (references != null) {
            if (references.size() > 200) throw ManagementSupport.invalid();
            for (String id : references)
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !resulting.containsKey(id))
                    throw ManagementSupport.invalid();
            references(row, resulting, new HashSet<>(), 0);
        }
        if (row.getParentId() != null && !row.getParentId().isBlank()) {
            MenuEntity parent = resulting.get(row.getParentId());
            if (parent == null || !Objects.equals(parent.getOwner(), row.getOwner()))
                throw ManagementSupport.invalid();
        }
    }

    private static void references(
            MenuEntity row, Map<String, MenuEntity> all, Set<String> path, int depth) {
        if (depth > 20 || !path.add(row.getId())) throw ManagementSupport.invalid();
        if (row.getIndirectMenus() != null)
            for (String id : row.getIndirectMenus()) {
                MenuEntity next = all.get(id);
                if (next == null) throw ManagementSupport.invalid();
                references(next, all, new HashSet<>(path), depth + 1);
            }
    }

    public static void owner(String owner) {
        if (owner == null || !owner.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw ManagementSupport.invalid();
    }
}
