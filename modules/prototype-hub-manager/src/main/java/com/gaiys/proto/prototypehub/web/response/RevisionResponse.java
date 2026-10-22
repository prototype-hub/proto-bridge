package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "不可变原型快照元数据，不公开存储路径及完整文件清单")
public record RevisionResponse(
        @Schema(description = "不可变快照标识") String id,
        @Schema(description = "所属项目标识") String projectId,
        @Schema(description = "所属逻辑版本标识") String versionId,
        @Schema(description = "发布时版本名称") String versionName,
        @Schema(description = "发布时版本说明") String description,
        @Schema(description = "入口文件相对路径") String entryFile,
        @Schema(description = "规范化文件清单 SHA-256") String manifestSha256,
        @Schema(description = "普通文件数量") int fileCount,
        @Schema(description = "注入后文件总字节数") long totalBytes,
        @Schema(description = "发布用户标识") String createdBy,
        @Schema(description = "发布时间（Unix 毫秒）") long createdAt) {
    public static RevisionResponse from(PrototypeRevisionEntity r) {
        return new RevisionResponse(
                r.getId(),
                r.getProjectId(),
                r.getVersionId(),
                r.getVersionName(),
                r.getDescription(),
                r.getEntryFile(),
                r.getManifestSha256(),
                r.getFileCount(),
                r.getTotalBytes(),
                r.getCreatorId(),
                r.getCreateTime());
    }
}
