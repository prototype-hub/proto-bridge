package com.gaiys.proto.prototypehub.web.request;

import com.gaiys.proto.prototypehub.enums.ShareRevisionMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 原型分享创建请求；过期时间的有效性与密码散列由分享服务处理。 */
@Schema(description = "原型分享创建请求")
public record ShareCreateRequest(
        @Size(max = 128) @Schema(description = "可选的分享访问密码；非空白密码最多72个UTF-8字节") String password,
        @Schema(description = "分享过期时间（Unix 毫秒），为空时不限制有效期") Long expiresAt,
        @Size(max = 2000) @Schema(description = "可选的分享说明") String description,
        @Schema(description = "快照模式，省略时为 fixed") ShareRevisionMode revisionMode,
        @Size(max = 64) @Schema(description = "固定模式的快照标识；省略时绑定提交时的当前快照，latest 禁止传入")
                String revisionId) {
    /** 保留既有调用方的固定当前快照创建方式。 */
    public ShareCreateRequest(String password, Long expiresAt, String description) {
        this(password, expiresAt, description, null, null);
    }
}
