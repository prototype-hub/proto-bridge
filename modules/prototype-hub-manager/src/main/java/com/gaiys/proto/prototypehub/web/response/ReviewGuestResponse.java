package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewUnverifiedGuestEntity;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import io.swagger.v3.oas.annotations.media.Schema;

/** 项目管理侧的访客视图；只暴露脱敏邮箱，姓名和公司是访客自述信息。 */
@Schema(description = "项目访客及保障等级，不属于内部用户")
public record ReviewGuestResponse(
        @Schema(description = "项目访客标识") String id,
        @Schema(description = "访客自述显示名称") String displayName,
        @Schema(description = "访客自述公司") String company,
        @Schema(description = "脱敏邮箱") String maskedEmail,
        @Schema(description = "email仅表示邮箱所有权，unverified表示资料未经核验") String verificationLevel,
        @Schema(description = "是否启用访客身份") boolean enabled,
        @Schema(description = "访客状态配置版本") long configVersion,
        @Schema(description = "首次验证时间（Unix 毫秒）") Long createTime,
        @Schema(description = "最近验证或状态修改时间（Unix 毫秒）") Long modifyTime,
        @Schema(description = "未验证访客的固定到期时间；邮箱身份为空") Long expiresAt) {
    public static ReviewGuestResponse from(PrototypeReviewGuestEntity guest) {
        return new ReviewGuestResponse(
                guest.getId(),
                guest.getDisplayName(),
                guest.getCompany(),
                PrototypeReviewKeys.masked(guest.getEmail()),
                "email",
                !Boolean.FALSE.equals(guest.getEnabled()),
                guest.getConfigVersion() == null ? 0 : guest.getConfigVersion(),
                guest.getCreateTime(),
                guest.getModifyTime(),
                null);
    }

    public static ReviewGuestResponse from(PrototypeReviewUnverifiedGuestEntity guest) {
        return new ReviewGuestResponse(
                guest.getId(),
                guest.getDisplayName(),
                guest.getCompany(),
                null,
                "unverified",
                Boolean.TRUE.equals(guest.getEnabled()),
                guest.getConfigVersion(),
                guest.getCreateTime(),
                guest.getModifyTime(),
                guest.getExpiresAt());
    }
}
