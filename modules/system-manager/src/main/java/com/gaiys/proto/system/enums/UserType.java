package com.gaiys.proto.system.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Objects;
import org.hswebframework.web.dict.EnumDict;
import org.hswebframework.web.dict.I18nEnumDict;
import org.hswebframework.web.i18n.LocaleUtils;

/**
 * 用户类型的稳定标识和展示文本。数据库与查询条件仍使用字符串；展示枚举不赋予账号类型写入或授权能力。
 *
 * <p>通过 hsweb 的枚举序列化扩展输出 value、text，同时保留已发布接口的 id 别名。
 */
@Schema(description = "用户类型，标识用于筛选，显示文本随请求语言切换")
public enum UserType implements I18nEnumDict<String> {
    /** 通过用户管理创建的普通账号。 */
    @Schema(description = "普通用户")
    normal("普通用户"),

    /** 由管理员初始化流程创建的账号类型，不代表角色授权。 */
    @Schema(description = "管理员")
    admin("管理员");

    private final String text;

    UserType(String text) {
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
        return Map.of(
                "id", getValue(),
                "value", getValue(),
                "text", getI18nMessage(LocaleUtils.current()));
    }

    /**
     * 将持久化标识转换为展示值。空值沿用普通用户语义；未知历史标识原样返回，不修改数据库，也不阻断用户查询。
     *
     * @param value 数据库中的原始用户类型
     * @return 已知类型枚举，或保留未知标识及文本的兼容展示对象
     */
    public static Object fromPersisted(String value) {
        String id = Objects.toString(value, normal.getValue());
        return EnumDict.findByValue(UserType.class, id)
                .<Object>map(type -> type)
                .orElseGet(() -> Map.of("id", id, "value", id, "text", id));
    }
}
