package com.gaiys.proto.prototypehub.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/** 完整配置编辑时必须明确密码处理方式，避免空密码字段意外清除既有保护。 */
@Schema(description = "分享密码编辑动作")
public enum SharePasswordAction {
    @Schema(description = "保留原密码")
    keep,
    @Schema(description = "设置新密码")
    replace,
    @Schema(description = "移除密码保护")
    remove
}
