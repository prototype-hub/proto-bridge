package com.gaiys.proto.prototypehub.web.info;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 原型页面目录节点；构造时复制子节点列表，供解析结果和页面目录接口共用。 */
@JsonPropertyOrder({"name", "pageId", "path", "folder", "children"})
@Schema(description = "原型页面目录节点")
public record PrototypePageInfo(
        @Schema(description = "页面或目录名称") String name,
        @Schema(description = "页面相对路径 SHA-256；与 revisionId 共同构成页面身份，目录为空") String pageId,
        @Schema(description = "页面相对路径，目录节点为空") String path,
        @Schema(description = "是否为目录节点") boolean folder,
        @Schema(description = "目录子节点，页面节点为空列表") List<PrototypePageInfo> children) {
    public PrototypePageInfo {
        children = children == null ? List.of() : List.copyOf(children);
    }

    private static String pageId(String path) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static PrototypePageInfo page(String name, String path) {
        return page(name, path, List.of());
    }

    public static PrototypePageInfo page(
            String name, String path, List<PrototypePageInfo> children) {
        return new PrototypePageInfo(name, pageId(path), path, false, children);
    }

    public static PrototypePageInfo folder(String name, List<PrototypePageInfo> children) {
        return new PrototypePageInfo(name, null, null, true, children);
    }
}
