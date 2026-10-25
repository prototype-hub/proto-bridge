package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 页面评审的显式投影视图，不公开作者ID、邮箱或凭据。 */
@Schema(description = "页面评审投影视图")
public record ReviewMessageResponse(
        @Schema(description = "消息标识") String id,
        @Schema(description = "所属线程") String threadId,
        @Schema(description = "纯文本正文") String text,
        @Schema(description = "guest或member") String authorType,
        @Schema(description = "作者显示名快照") String authorName,
        @Schema(description = "访客自述公司，成员为空") String authorCompany,
        @Schema(description = "访客身份等级，成员为空") String verificationLevel,
        @Schema(description = "public或internal") String visibility,
        @Schema(description = "创建Unix毫秒") long createdAt,
        @Schema(description = "单张私有图片的安全元数据，纯文本消息为空") Image image) {
    /** 图片仅由当前消息授权下载，响应不包含路径、摘要或永久URL。 */
    @Schema(description = "回复图片元数据")
    public record Image(
            @Schema(description = "实际图片媒体类型") String contentType,
            @Schema(description = "原始图片字节数") long size,
            @Schema(description = "像素宽度") int width,
            @Schema(description = "像素高度") int height) {}
}
