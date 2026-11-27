package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionApprovalEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 内部当前正式状态及不可变修订摘要；批准、发布和原型文件当前指针互相独立。 */
@Schema(description = "固定修订正式批准与发布状态")
public record ApprovalResponse(
        String projectId,
        String versionId,
        String revisionId,
        long configVersion,
        boolean approved,
        boolean published,
        Decision approval,
        Decision publication,
        Revision revision) {
    /** 内部可读的决策快照；姓名与备注不进入公开分享响应。 */
    public record Decision(
            String id,
            String action,
            String actorId,
            String actorName,
            Long createTime,
            String note) {
        public static Decision of(PrototypeRevisionApprovalEntity row) {
            return new Decision(
                    row.getId(),
                    row.getAction(),
                    row.getActorId(),
                    row.getActorName(),
                    row.getCreateTime(),
                    row.getNote());
        }
    }

    /** 已存在的内容摘要，不返回存储路径或完整清单，不创建新计费版本。 */
    public record Revision(
            String versionName,
            String description,
            String entryFile,
            String manifestSha256,
            Integer fileCount,
            Long totalBytes,
            Long createTime) {
        public static Revision of(PrototypeRevisionEntity row) {
            return new Revision(
                    row.getVersionName(),
                    row.getDescription(),
                    row.getEntryFile(),
                    row.getManifestSha256(),
                    row.getFileCount(),
                    row.getTotalBytes(),
                    row.getCreateTime());
        }
    }

    /** 分页只追加历史；结果指针保留该次决策生效时的状态。 */
    public record History(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            long configVersion,
            String action,
            boolean approved,
            boolean published,
            String approvalId,
            String publicationId,
            String actorId,
            String actorName,
            Long createTime,
            String note) {
        public static History of(PrototypeRevisionApprovalEntity row) {
            return new History(
                    row.getId(),
                    row.getProjectId(),
                    row.getVersionId(),
                    row.getRevisionId(),
                    row.getConfigVersion(),
                    row.getAction(),
                    row.getApproved(),
                    row.getPublished(),
                    row.getApprovalId(),
                    row.getPublicationId(),
                    row.getActorId(),
                    row.getActorName(),
                    row.getCreateTime(),
                    row.getNote());
        }
    }

    /** 公开分享只读脱敏结果；不存在作者、备注、历史或内部范围元数据。 */
    public record PublicContext(
            String revisionId,
            boolean approved,
            boolean published,
            Long approvedAt,
            Long publishedAt) {}
}
