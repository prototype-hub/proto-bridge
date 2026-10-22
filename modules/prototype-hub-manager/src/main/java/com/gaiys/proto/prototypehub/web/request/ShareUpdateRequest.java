package com.gaiys.proto.prototypehub.web.request;

import com.gaiys.proto.prototypehub.enums.SharePasswordAction;
import com.gaiys.proto.prototypehub.enums.ShareRevisionMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 分享配置完整替换请求；启停与撤销使用独立入口。 */
@Schema(description = "原型分享配置编辑请求")
public record ShareUpdateRequest(
        @NotNull @PositiveOrZero @Schema(description = "客户端读取到的配置版本，旧记录为0") Long expectedVersion,
        @Size(max = 2000) @Schema(description = "分享说明，null 清空") String description,
        @Schema(description = "过期时间（Unix 毫秒），null 取消时间限制") Long expiresAt,
        @NotNull @Schema(description = "目标快照模式") ShareRevisionMode revisionMode,
        @Size(max = 64) @Schema(description = "固定模式快照；原固定分享省略时保留，latest 改 fixed 时必须提供")
                String revisionId,
        @NotNull @Schema(description = "保留、替换或移除密码") SharePasswordAction passwordAction,
        @Size(max = 128) @Schema(description = "仅 replace 时提供的新密码，非空白且最多72个UTF-8字节")
                String password) {}
