package com.gaiys.proto.prototypehub.web.info;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 原型页面目录节点；构造时复制子节点列表，供解析结果和页面目录接口共用。 */
@Schema(description = "原型页面目录节点")
public record PrototypePageInfo(
        @Schema(description = "页面或目录名称") String name,
        @Schema(description = "页面相对路径，目录节点为空") String path,
        @Schema(description = "是否为目录节点") boolean folder,
        @Schema(description = "目录子节点，页面节点为空列表") List<PrototypePageInfo> children) {
    public PrototypePageInfo {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static PrototypePageInfo page(String name, String path) {
        return new PrototypePageInfo(name, path, false, List.of());
    }

    public static PrototypePageInfo folder(String name, List<PrototypePageInfo> children) {
        return new PrototypePageInfo(name, null, true, children);
    }
}
