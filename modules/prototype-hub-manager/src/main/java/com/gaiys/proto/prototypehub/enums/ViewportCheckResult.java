package com.gaiys.proto.prototypehub.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.hswebframework.web.dict.I18nEnumDict;
import org.hswebframework.web.i18n.LocaleUtils;

/** 人工观察结论；未测不会自动变为通过。 */
@Schema(description = "人工视口自测结论")
public enum ViewportCheckResult implements I18nEnumDict<String> {
    @Schema(description = "未测")
    untested("未测"),
    @Schema(description = "通过")
    passed("通过"),
    @Schema(description = "不通过")
    failed("不通过");

    private final String text;

    ViewportCheckResult(String text) {
        this.text = text;
    }

    @Override
    public String getValue() {
        return name();
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    @JsonValue
    public Map<String, String> getWriteJSONObject() {
        return Map.of("value", getValue(), "text", getI18nMessage(LocaleUtils.current()));
    }
}
