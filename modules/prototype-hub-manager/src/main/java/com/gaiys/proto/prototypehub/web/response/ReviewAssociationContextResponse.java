package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 管理员选定线程的服务端来源范围及授权状态；不返回公开分享凭据。 */
public record ReviewAssociationContextResponse(
        @Schema(description = "历史来源线程的成员投影") ReviewThreadResponse sourceThread,
        @Schema(description = "所属逻辑版本标识") String versionId,
        @Schema(description = "原始分享标识") String shareId,
        @Schema(description = "固定不可变修订标识") String revisionId,
        @Schema(description = "授权时分享配置版本") Long shareConfigVersion,
        @Schema(description = "当前来源是否允许创建新授权") boolean eligible,
        @Schema(description = "来源不可用原因码，可为空") String unavailableReason,
        @Schema(description = "当前未撤销授权，可已失效或为空") ReviewAssociationResponse activeAssociation) {}
