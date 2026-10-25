package com.gaiys.proto.prototypehub.web.info;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 根文档CSS像素位置快照；仅描述几何，不作为页面身份或授权依据。 */
@Schema(description = "不可变根文档CSS像素锚点，动态布局可能使位置偏移")
public record ReviewAnchor(
        @Schema(description = "固定坐标空间root-document-css-px-v1") String space,
        @Schema(description = "形状：point、rect或ellipse") String shape,
        @Schema(description = "文档横坐标，整数CSS像素") int x,
        @Schema(description = "文档纵坐标，整数CSS像素") int y,
        @Schema(description = "宽度，点位为零") int width,
        @Schema(description = "高度，点位为零") int height,
        @Schema(description = "捕获时文档宽度，1至1000000") int documentWidth,
        @Schema(description = "捕获时文档高度，1至1000000") int documentHeight,
        @Schema(description = "捕获时视口宽度，1至16384") int viewportWidth,
        @Schema(description = "捕获时视口高度，1至16384") int viewportHeight) {
    private static final Set<String> FIELDS =
            Set.of(
                    "space",
                    "shape",
                    "x",
                    "y",
                    "width",
                    "height",
                    "documentWidth",
                    "documentHeight",
                    "viewportWidth",
                    "viewportHeight");

    public ReviewAnchor {
        if (!"root-document-css-px-v1".equals(space)
                || !Set.of("point", "rect", "ellipse").contains(shape == null ? "" : shape)
                || x < 0
                || y < 0
                || width < 0
                || height < 0
                || documentWidth < 1
                || documentWidth > 1_000_000
                || documentHeight < 1
                || documentHeight > 1_000_000
                || viewportWidth < 1
                || viewportWidth > 16_384
                || viewportHeight < 1
                || viewportHeight > 16_384
                || (long) x + width > documentWidth
                || (long) y + height > documentHeight
                || ("point".equals(shape) ? width != 0 || height != 0 : width < 1 || height < 1)) {
            throw invalid();
        }
    }

    /** 局部严格解析，禁止Jackson默认将字符串或小数转换成整数；官方JsonCodec复用同一入口。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReviewAnchor from(JsonNode value) {
        if (!value.isObject() || value.size() != FIELDS.size()) throw invalid();
        var names = value.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
        if (!value.path("space").isTextual() || !value.path("shape").isTextual()) throw invalid();
        return new ReviewAnchor(
                value.get("space").textValue(),
                value.get("shape").textValue(),
                integer(value, "x"),
                integer(value, "y"),
                integer(value, "width"),
                integer(value, "height"),
                integer(value, "documentWidth"),
                integer(value, "documentHeight"),
                integer(value, "viewportWidth"),
                integer(value, "viewportHeight"));
    }

    private static int integer(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (!number.isIntegralNumber() || !number.canConvertToInt()) throw invalid();
        return number.intValue();
    }

    /** 命名且固定顺序的语义值，与客户端JSON字段顺序无关。 */
    public String canonical() {
        return "anchor:space="
                + space
                + ";shape="
                + shape
                + ";x="
                + x
                + ";y="
                + y
                + ";width="
                + width
                + ";height="
                + height
                + ";documentWidth="
                + documentWidth
                + ";documentHeight="
                + documentHeight
                + ";viewportWidth="
                + viewportWidth
                + ";viewportHeight="
                + viewportHeight;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid root document anchor");
    }
}
