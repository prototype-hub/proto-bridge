package com.gaiys.proto.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.JDBCType;
import java.util.HexFormat;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** Personal settings are addressed by owner, scope and key; the storage ID is internal. */
@Getter
@Setter
@Table(
        name = "s_user_setting",
        indexes = {
            @Index(
                    name = "proto_user_setting_user_id_type_setting_key_key",
                    columnList = "user_id,type,setting_key",
                    unique = true),
            @Index(
                    name = "proto_user_setting_owner_idx",
                    columnList = "user_id,type,create_time DESC,id")
        })
@Schema(description = "按用户、类型和配置项键隔离的个人设置")
public class UserSettingEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false)
    @Schema(description = "配置所属用户标识")
    private String userId;

    @Column(length = 128, nullable = false, updatable = false)
    @Schema(description = "个人配置类型或页面范围标识")
    private String type;

    @Column(name = "setting_key", length = 64, nullable = false, updatable = false)
    @Schema(description = "配置项键，在同一用户和配置类型内唯一")
    private String key;

    @Column(length = 128, nullable = false)
    @Schema(description = "个人配置显示名称")
    private String name;

    @Column(nullable = false)
    @JsonCodec
    @ColumnType(javaType = String.class, jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "个人配置内容，保留字符串、对象等 JSON 值类型")
    private Object content;

    @Column(nullable = false, updatable = false)
    @Schema(description = "创建时间（Unix 毫秒）")
    private Long createTime;

    public static String storageId(String userId, String type, String key) {
        try {
            // Separators prevent different owner/scope/key combinations sharing an identity.
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (userId + "\0" + type + "\0" + key)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
