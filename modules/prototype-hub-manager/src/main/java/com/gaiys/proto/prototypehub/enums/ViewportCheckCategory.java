package com.gaiys.proto.prototypehub.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.hswebframework.web.dict.I18nEnumDict;
import org.hswebframework.web.i18n.LocaleUtils;

/** 成员手工记录的场景分类，不触发自动操作或改变页面权限。 */
@Schema(description = "人工视口自测场景类别")
public enum ViewportCheckCategory implements I18nEnumDict<String> {
    @Schema(description = "正常")
    normal("正常"),
    @Schema(description = "空态")
    empty("空态"),
    @Schema(description = "加载")
    loading("加载"),
    @Schema(description = "权限")
    permission("权限"),
    @Schema(description = "边界")
    boundary("边界"),
    @Schema(description = "错误")
    error("错误");

    private final String text;

    ViewportCheckCategory(String text) {
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
