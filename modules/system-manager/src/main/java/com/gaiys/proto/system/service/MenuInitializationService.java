package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.reconciliation.MenuGrantReconciliation;
import com.gaiys.proto.system.service.support.ManagementSupport;
import com.gaiys.proto.system.service.validation.MenuMetadataValidation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Replaces one owner's menu tree atomically, including managed authorization reductions. */
@Service
@RequiredArgsConstructor
public class MenuInitializationService {
    private final MenuService menus;
    private final MenuMetadataValidation validation;
    private final MenuGrantReconciliation reconciliation;

    @Transactional(
            rollbackFor = Throwable.class,
            transactionManager = TransactionManagers.reactiveTransactionManager)
    public Mono<SaveResult> replace(String owner, List<MenuEntity> input) {
        List<MenuEntity> rows = flatten(input);
        return menus.createQuery()
                .fetch()
                .collectMap(MenuEntity::getId)
                .flatMap(
                        existing -> {
                            Map<String, MenuEntity> resulting =
                                    validation.replacement(owner, rows, existing);
                            Set<String> affected = new HashSet<>();
                            existing.values().stream()
                                    .filter(row -> owner.equals(row.getOwner()))
                                    .forEach(row -> affected.add(row.getId()));
                            rows.forEach(row -> affected.add(row.getId()));
                            // Suppress intermediate delete/save reconciliation; unchanged
                            // selections survive replacement.
                            return menus.getRepository()
                                    .createDelete()
                                    .where(MenuEntity::getOwner, owner)
                                    .execute()
                                    .then(menus.save(Flux.fromIterable(rows)))
                                    .flatMap(
                                            result ->
                                                    reconciliation
                                                            .reconcile(affected)
                                                            .thenReturn(result))
                                    .contextWrite(
                                            context ->
                                                    context.put(
                                                                    MenuGrantReconciliation
                                                                            .REPLACEMENT,
                                                                    true)
                                                            .put(
                                                                    MenuMetadataValidation
                                                                            .REPLACEMENT_SNAPSHOT,
                                                                    resulting));
                        });
    }

    private static List<MenuEntity> flatten(List<MenuEntity> input) {
        if (input == null || input.isEmpty()) throw ManagementSupport.invalid();
        LinkedHashMap<String, MenuEntity> rows = new LinkedHashMap<>();
        for (MenuEntity root : input) flatten(root, null, rows, 0);
        List<MenuEntity> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (MenuEntity row : rows.values()) order(row, rows, ordered, done, new HashSet<>(), 0);
        return ordered;
    }

    private static void flatten(
            MenuEntity row, String parent, Map<String, MenuEntity> rows, int depth) {
        if (row == null || depth > 20 || rows.size() >= 500) throw ManagementSupport.invalid();
        if (row.getId() == null || row.getId().isBlank())
            row.setId(UUID.randomUUID().toString().replace("-", ""));
        if (!row.getId().matches("[A-Za-z0-9_-]{1,64}")
                || rows.putIfAbsent(row.getId(), row) != null) throw ManagementSupport.invalid();
        if (row.getName() == null || row.getName().isBlank() || row.getName().length() > 128)
            throw ManagementSupport.invalid();
        if (parent != null) {
            if (row.getParentId() != null
                    && !row.getParentId().isBlank()
                    && !parent.equals(row.getParentId())) throw ManagementSupport.invalid();
            row.setParentId(parent);
        }
        List<MenuEntity> children = row.getChildren();
        row.setChildren(null);
        row.setPath(null);
        if (children != null)
            for (MenuEntity child : children) flatten(child, row.getId(), rows, depth + 1);
    }

    private static void order(
            MenuEntity row,
            Map<String, MenuEntity> rows,
            List<MenuEntity> ordered,
            Set<String> done,
            Set<String> path,
            int depth) {
        if (done.contains(row.getId())) return;
        if (depth > 20 || !path.add(row.getId())) throw ManagementSupport.invalid();
        if (row.getParentId() != null && !row.getParentId().isBlank()) {
            MenuEntity parent = rows.get(row.getParentId());
            if (parent == null) throw ManagementSupport.invalid();
            order(parent, rows, ordered, done, path, depth + 1);
        }
        done.add(row.getId());
        ordered.add(row);
    }
}
