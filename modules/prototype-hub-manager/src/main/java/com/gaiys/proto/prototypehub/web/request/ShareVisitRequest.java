package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 外层预览加载成功后确认访问；不接受客户端提供的归属或发生时间。 */
@Schema(description = "确认一次外层分享访问")
public record ShareVisitRequest(
        @NotBlank @Size(max = 128) @Schema(description = "当前授权返回的24小时事件票据") String ticket) {}
