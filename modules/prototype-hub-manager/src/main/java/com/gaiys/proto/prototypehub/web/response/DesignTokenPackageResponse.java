package com.gaiys.proto.prototypehub.web.response;

import java.util.List;
import java.util.Map;

/** 已授权规范包的元数据与首次完整验证报告；不返回私有存储路径。 */
public record DesignTokenPackageResponse(
        String id,
        String projectId,
        String versionId,
        String revisionId,
        String fileName,
        long fileSize,
        String sha256,
        String standardVersion,
        String validatorVersion,
        boolean valid,
        int tokenCount,
        int groupCount,
        int issueCount,
        Report report,
        String sourceTool,
        String sourceName,
        String sourceUrl,
        String sourceNotes,
        long configVersion,
        String createdBy,
        String updatedBy,
        long createdAt,
        long updatedAt) {
    public record Report(
            boolean valid,
            List<DesignTokenDocument.Issue> issues,
            boolean truncated,
            Map<String, String> inputs) {
        public static Report of(DesignTokenDocument document) {
            return new Report(
                    document.isValid(),
                    document.getIssues(),
                    document.isTruncated(),
                    document.getInputs());
        }
    }
}
