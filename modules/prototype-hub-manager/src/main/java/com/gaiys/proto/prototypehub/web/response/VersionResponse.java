package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.web.urls.PrototypeUrls;
import io.swagger.v3.oas.annotations.media.Schema;

/** 原型版本管理响应视图，通过版本预览密钥构造运行文件和入口预览地址。 */
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
        @Schema(description = "运行文件基础地址") String runtimeBaseUrl,
        @Schema(description = "原型预览地址") String previewUrl) {
    public static VersionResponse from(PrototypeVersionEntity v) {
        String base = "/api/prototype-hub/runtime/" + v.getId() + "/" + v.getPreviewKey();
        return new VersionResponse(
                v.getId(),
                v.getProjectId(),
                v.getVersionName(),
                v.getDescription(),
                v.getEntryFile(),
                v.getStatus(),
                v.getCreatorId(),
                v.getCreateTime(),
                base,
                PrototypeUrls.preview(base, v.getEntryFile()));
    }
}
