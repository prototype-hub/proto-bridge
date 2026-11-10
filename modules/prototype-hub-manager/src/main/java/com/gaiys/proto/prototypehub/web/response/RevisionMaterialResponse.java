package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 只返回已授权范围内的资料元数据，不暴露存储路径。 */
@Schema(description = "固定修订私有资料，来源由成员手工声明")
public record RevisionMaterialResponse(
        @Schema(description = "资料或来源声明标识，不是修订标识") String id,
        @Schema(description = "所属项目标识") String projectId,
        @Schema(description = "所属逻辑版本标识") String versionId,
        @Schema(description = "固定修订标识，不随当前版本变化") String revisionId,
        @Schema(description = "资料类别prototype、image或document") String materialType,
        @Schema(description = "原始文件名称，仅作显示和下载名称") String fileName,
        @Schema(description = "服务端校验得到的媒体类型") String mediaType,
        @Schema(description = "原始文件字节数") Long fileSize,
        @Schema(description = "原始文件SHA256摘要") String sha256,
        @Schema(description = "成员声明的来源工具，不代表认证来源") String sourceTool,
        @Schema(description = "成员声明的来源名称") String sourceName,
        @Schema(description = "成员声明的HTTP或HTTPS来源地址，服务端不抓取") String sourceUrl,
        @Schema(description = "成员填写的来源备注，纯文本") String sourceNotes,
        @Schema(description = "从零递增的来源维护并发版本") Long configVersion,
        @Schema(description = "创建成员标识") String createdBy,
        @Schema(description = "最近维护成员标识") String updatedBy,
        @Schema(description = "创建Unix毫秒") Long createdAt,
        @Schema(description = "最近维护Unix毫秒") Long updatedAt) {
    public static RevisionMaterialResponse of(PrototypeRevisionMaterialEntity row) {
        return new RevisionMaterialResponse(
                row.getId(),
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getMaterialType(),
                row.getFileName(),
                row.getMediaType(),
                row.getFileSize(),
                row.getSha256(),
                row.getSourceTool(),
                row.getSourceName(),
                row.getSourceUrl(),
                row.getSourceNotes(),
                row.getConfigVersion(),
                row.getCreatedBy(),
                row.getUpdatedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
