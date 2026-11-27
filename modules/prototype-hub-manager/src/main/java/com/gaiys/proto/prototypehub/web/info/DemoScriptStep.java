package com.gaiys.proto.prototypehub.web.info;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 人工演示的一步；数组顺序决定次序，稳定标识与普通讲解不执行原型操作。 */
@Schema(description = "固定修订真实页面的有序演示步骤")
public record DemoScriptStep(String id, String pageId, String narration) {
    private static final Set<String> FIELDS = Set.of("id", "pageId", "narration");

    public DemoScriptStep {
        if (id == null
                || !id.matches("[A-Za-z0-9_-]{1,64}")
                || pageId == null
                || pageId.isBlank()
                || pageId.length() > 128
                || pageId.indexOf('\0') >= 0) throw invalid();
        narration = narration == null ? "" : narration;
        if (narration.length() > 2000 || narration.indexOf('\0') >= 0) throw invalid();
    }

    /** HTTP与官方JsonCodec共用精确字段和标量类型，不接受调用者附加路径或命令。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DemoScriptStep from(JsonNode value) {
        if (!value.isObject()) throw invalid();
        var names = value.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
        return new DemoScriptStep(
                text(value, "id", false),
                text(value, "pageId", false),
                text(value, "narration", true));
    }

    private static String text(JsonNode value, String field, boolean optional) {
        JsonNode node = value.get(field);
        if (optional && (node == null || node.isNull())) return "";
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid demo script step");
    }
}
