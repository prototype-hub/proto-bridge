package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 原型版本管理视图；内部预览须单独获取短期会话，查询结果不再携带永久能力。 */
@Schema(description = "原型版本响应信息")
public record VersionResponse(
        @Schema(description = "原型版本标识") String id,
        @Schema(description = "版本所属的原型项目标识") String projectId,
        @Schema(description = "原型版本名称") String versionName,
        @Schema(description = "原型版本说明") String description,
        @Schema(description = "原型页面入口文件的相对路径") String entryFile,
        @Schema(description = "原型版本状态") String status,
        @Schema(description = "版本创建者用户标识") String createdBy,
        @Schema(description = "版本创建时间（Unix 毫秒）") Long createdAt,
        @Schema(description = "已停用的永久运行地址，固定为空；请获取短期预览会话") String runtimeBaseUrl,
        @Schema(description = "已停用的永久预览地址，固定为空；请获取短期预览会话") String previewUrl) {
    public static VersionResponse from(PrototypeVersionEntity v) {
        return new VersionResponse(
                v.getId(),
                v.getProjectId(),
                v.getVersionName(),
                v.getDescription(),
                v.getEntryFile(),
                v.getStatus(),
                v.getCreatorId(),
                v.getCreateTime(),
                null,
                null);
    }
}
