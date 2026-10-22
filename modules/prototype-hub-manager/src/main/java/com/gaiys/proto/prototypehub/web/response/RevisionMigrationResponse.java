package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单逻辑版本旧内容及旧分享和回执的迁移检查结果")
public record RevisionMigrationResponse(
        @Schema(description = "逻辑版本标识") String versionId,
        @Schema(description = "本操作完成后是否仍需迁移内容或旧绑定") boolean migrationRequired,
        @Schema(description = "已存在或本次计划发布的快照标识") String revisionId,
        @Schema(description = "清单 SHA-256") String manifestSha256,
        @Schema(description = "普通文件数量") int fileCount,
        @Schema(description = "注入后总字节数") long totalBytes,
        @Schema(description = "待绑定的旧分享数量") long sharesToBind,
        @Schema(description = "待绑定的旧创建回执数量") long receiptsToBind,
        @Schema(description = "是否已提交；重跑已完成的数据仍返回 true") boolean applied) {}
