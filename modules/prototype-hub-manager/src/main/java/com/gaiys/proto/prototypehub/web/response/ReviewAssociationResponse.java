package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 仅管理端可见的授权与撤销审计；目标邮箱仅复用脱敏视图，无原始凭据或来源访客ID。 */
public record ReviewAssociationResponse(
        @Schema(description = "授权记录标识") String id,
        @Schema(description = "人工选定的历史来源线程标识") String sourceThreadId,
        @Schema(description = "所属逻辑版本标识") String versionId,
        @Schema(description = "原始分享标识") String shareId,
        @Schema(description = "固定不可变修订标识") String revisionId,
        @Schema(description = "目标邮箱访客标识") String targetGuestId,
        @Schema(description = "目标访客脱敏视图，可为空") ReviewGuestResponse targetGuest,
        @Schema(description = "active、invalidated或revoked") String status,
        @Schema(description = "不可用原因码，可为空") String invalidReason,
        @Schema(description = "独立授权状态并发版本") long configVersion,
        @Schema(description = "授权时分享配置版本") long shareConfigVersion,
        @Schema(description = "授权时目标访客配置版本") long targetGuestConfigVersion,
        @Schema(description = "操作人记录的线下核实依据或记录编号") String evidenceReference,
        @Schema(description = "授权操作人标识") String createdBy,
        @Schema(description = "授权操作人显示名称快照") String createdByName,
        @Schema(description = "授权时间，Unix毫秒") long createdAt,
        @Schema(description = "撤销操作人标识") String revokedBy,
        @Schema(description = "撤销操作人显示名称快照") String revokedByName,
        @Schema(description = "撤销时间，Unix毫秒") Long revokedAt,
        @Schema(description = "撤销原因") String revokeReason) {}
