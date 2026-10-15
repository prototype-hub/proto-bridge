package com.gaiys.proto.system.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.i18n.MultipleI18nSupportEntity;

/**
 * Stores menu metadata and permissions with a stable default display name.
 *
 * <p>Localized display is resolved by the existing hsweb request locale at serialization time. The
 * read-only i18nName is not persisted and cannot alter the default name or translation map.
 */
@Getter
@Setter
@Table(
        name = "s_menu",
        indexes = {
            @Index(name = "proto_menu_owner_idx", columnList = "owner"),
            @Index(name = "proto_menu_code_idx", columnList = "code", unique = true)
        })
@EnableEntityEvent
@Schema(description = "系统菜单及按钮权限配置")
public class MenuEntity extends GenericTreeSortSupportEntity<String>
        implements MultipleI18nSupportEntity {
    @Column(length = 128, nullable = false)
    @NotBlank
    @Schema(description = "菜单名称")
    private String name;

    @Column(length = 255)
    @Schema(description = "菜单路由地址")
    private String url;

    @Column(length = 64)
    @Schema(description = "单一权限资源标识（兼容字段）")
    private String permission;

    @Column(length = 32)
    @Schema(description = "单一权限操作标识（兼容字段）")
    private String action;

    @Column(nullable = false)
    @Schema(description = "菜单是否启用")
    private Boolean enabled = true;

    @Column(length = 128)
    @Schema(description = "菜单功能编码")
    private String code;

    @Column(length = 128)
    @Schema(description = "菜单图标标识")
    private String icon;

    @Column(length = 128)
    @Schema(description = "菜单对应的前端组件编码")
    private String componentCode;

    @Column(length = 64)
    @Schema(description = "菜单关联的应用标识")
    private String appId;

    @Column(length = 64, nullable = false, updatable = false)
    @Schema(description = "菜单所属命名空间（默认 iot，创建后不可变）")
    private String owner;

    @Column(length = 1024)
    @Schema(description = "菜单说明")
    private String describe;

    @Column(length = 64)
    @Schema(description = "资产权限类型标识（描述元数据）")
    private String assetType;

    // These are asset permission descriptors; they do not grant menu visibility or actions.
    @Column(length = 32, nullable = false)
    @JsonDeserialize(using = AccessSupportDeserializer.class)
    @Schema(description = "资产权限描述方式：unsupported 不支持、support 直接、indirect 间接")
    private String accessSupport;

    @Column
    @org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec
    @org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType(
            jdbcType = java.sql.JDBCType.LONGVARCHAR,
            javaType = String.class)
    @Schema(description = "间接资产权限引用的菜单标识列表")
    private List<String> indirectMenus;

    @Column
    @org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec
    @org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType(
            jdbcType = java.sql.JDBCType.LONGVARCHAR,
            javaType = String.class)
    @Schema(description = "菜单展示与路由扩展配置")
    private java.util.Map<String, Object> options;

    @Column
    @org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec
    @org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType(
            jdbcType = java.sql.JDBCType.LONGVARCHAR,
            javaType = String.class)
    @Schema(description = "菜单访问所需的权限资源与操作列表")
    private java.util.List<java.util.Map<String, Object>> permissions;

    @Column
    @org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec
    @org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType(
            jdbcType = java.sql.JDBCType.LONGVARCHAR,
            javaType = String.class)
    @Schema(description = "菜单按钮及其权限资源与操作配置")
    private java.util.List<java.util.Map<String, Object>> buttons;

    @Column
    @org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec
    @org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType(
            jdbcType = java.sql.JDBCType.LONGVARCHAR,
            javaType = String.class)
    @Schema(description = "菜单多语言文本，按字段和语言保存翻译")
    private java.util.Map<String, java.util.Map<String, String>> i18nMessages;

    @Schema(description = "当前授权上下文中的菜单授权状态")
    private Boolean granted;

    @Schema(description = "子菜单列表（树形返回结果）")
    private List<MenuEntity> children;

    /** 使用 hsweb 的请求语言解析显示文本，持久化 name 始终保留默认中文。 */
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "当前请求语言的菜单名称，缺少翻译时返回默认名称", accessMode = Schema.AccessMode.READ_ONLY)
    public String getI18nName() {
        return getI18nMessage("name", name);
    }

    /** 接收旧前端的枚举对象；存储与返回继续使用资产权限描述字符串。 */
    public static final class AccessSupportDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode input = parser.getCodec().readTree(parser);
            JsonNode value = input.isObject() ? input.get("value") : input;
            if (value == null
                    || !value.isTextual()
                    || !Set.of("unsupported", "support", "indirect").contains(value.textValue()))
                throw context.weirdStringException(
                        input.toString(), String.class, "validation.management_invalid");
            return value.textValue();
        }
    }
}
