package com.gaiys.proto.system.service.tree;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 菜单查询结果的树形组装工具。只转换已有菜单集合，菜单可见性和授权由调用方先行确定。 */
public final class MenuControllerTree {
    private MenuControllerTree() {}

    public static List<MenuEntity> tree(List<MenuEntity> all, String parent) {
        return tree(all, parent, new HashSet<>());
    }

    private static List<MenuEntity> tree(List<MenuEntity> all, String parent, Set<String> path) {
        List<MenuEntity> rows = new ArrayList<>();
        for (MenuEntity row : all) {
            if (Objects.equals(parent, row.getParentId())
                    || (parent == null && "".equals(row.getParentId()))) {
                if (!path.add(row.getId())) throw ManagementSupport.invalid();
                row.setChildren(tree(all, row.getId(), path));
                path.remove(row.getId());
                rows.add(row);
            }
        }
        rows.sort(
                Comparator.comparingLong(
                        row -> row.getSortIndex() == null ? 0 : row.getSortIndex()));
        return rows;
    }
}
