package com.gaiys.proto.prototypehub.web.info;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 仅保存来源身份及成员确认的版本，不复制正文，也不阻拦来源删除。 */
@Schema(description = "固定修订页面的人工来源指针")
public record PageTraceSourceRef(String kind, String id, long expectedVersion) {
    public static final Set<String> KINDS = Set.of("work-item", "material", "description", "check");
    private static final Set<String> FIELDS = Set.of("kind", "id", "expectedVersion");

    public PageTraceSourceRef {
        if (kind == null
                || !KINDS.contains(kind)
                || id == null
                || id.isBlank()
                || id.length() > 128
                || id.indexOf('\0') >= 0
                || expectedVersion < 0
                || expectedVersion > 9007199254740991L) throw invalid();
    }

    /** HTTP和官方JsonCodec共用严格字段/标量，不接受客户端附加权限、路径或来源正文。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PageTraceSourceRef from(JsonNode node) {
        if (!node.isObject()) throw invalid();
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw invalid();
        var kind = node.get("kind");
        var id = node.get("id");
        var version = node.get("expectedVersion");
        if (kind == null
                || !kind.isTextual()
                || id == null
                || !id.isTextual()
                || version == null
                || !version.isIntegralNumber()
                || !version.canConvertToLong()) throw invalid();
        return new PageTraceSourceRef(kind.textValue(), id.textValue(), version.longValue());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid page trace source reference");
    }
}
