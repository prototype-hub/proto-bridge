package com.gaiys.proto.prototypehub.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分享授权时选择内容快照的策略，跟随最新也只授予本次解析出的单个快照。 */
@Schema(description = "分享快照模式")
public enum ShareRevisionMode {
    @Schema(description = "固定指定快照")
    fixed,
    @Schema(description = "每次打开分享时解析最新快照")
    latest
}
