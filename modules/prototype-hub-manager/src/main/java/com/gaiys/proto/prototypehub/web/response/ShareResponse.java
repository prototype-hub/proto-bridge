package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已登录用户使用的分享管理视图，只返回密码是否设置及公开分享链接。 */
@Schema(description = "原型分享管理响应信息")
public record ShareResponse(
        @Schema(description = "分享记录标识") String id,
        @Schema(description = "分享所属的原型项目标识") String projectId,
        @Schema(description = "分享对应的原型版本标识") String versionId,
        @Schema(description = "分享说明") String description,
        @Schema(description = "公开分享链接标识") String token,
        @Schema(description = "是否已设置分享访问密码") boolean passwordRequired,
        @Schema(description = "分享过期时间（Unix 毫秒），为空时不限制有效期") Long expiresAt,
        @Schema(description = "分享是否启用") boolean enabled,
        @Schema(description = "分享创建者用户标识") String createdBy,
        @Schema(description = "分享创建时间（Unix 毫秒）") Long createdAt,
        @Schema(description = "前端公开分享页面路径") String sharePath,
        @Schema(description = "快照模式：fixed 或 latest") String revisionMode,
        @Schema(description = "固定模式绑定的快照标识，latest 为空") String revisionId,
        @Schema(description = "是否永久撤销") boolean revoked,
        @Schema(description = "配置并发版本") long configVersion) {
    public static ShareResponse from(PrototypeShareEntity s) {
        return new ShareResponse(
                s.getId(),
                s.getProjectId(),
                s.getVersionId(),
                s.getDescription(),
                s.getToken(),
                s.getPasswordHash() != null,
                s.getExpiresAt(),
                Boolean.TRUE.equals(s.getEnabled()),
                s.getCreatorId(),
                s.getCreateTime(),
                "/#/share/" + s.getToken(),
                s.getRevisionMode() == null ? "fixed" : s.getRevisionMode(),
                s.getRevisionId(),
                Boolean.TRUE.equals(s.getRevoked()),
                s.getConfigVersion() == null ? 0 : s.getConfigVersion());
    }
}
