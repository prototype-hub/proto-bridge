package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeComponentCodeMappingEntity;
import java.util.List;

/** 固定页代码卡与可迁移白名单文本投影；说明正文、anchor和内部回执不在卡包中。 */
public final class CodeMappingResponse {
    private CodeMappingResponse() {}

    public record Description(
            String id, long expectedVersion, String status, Long currentVersion, String title) {}

    public record Card(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            String pageId,
            String alias,
            String repositoryPath,
            String repositoryUrl,
            String documentationUrl,
            String snippet,
            String framework,
            String frameworkVersion,
            Description description,
            long configVersion,
            long createdAt,
            long updatedAt) {
        public static Card of(PrototypeComponentCodeMappingEntity row, Description description) {
            return new Card(
                    row.getId(),
                    row.getProjectId(),
                    row.getVersionId(),
                    row.getRevisionId(),
                    row.getPageId(),
                    row.getAlias(),
                    row.getRepositoryPath(),
                    row.getRepositoryUrl(),
                    row.getDocumentationUrl(),
                    row.getSnippet(),
                    row.getFramework(),
                    row.getFrameworkVersion(),
                    description,
                    row.getConfigVersion(),
                    row.getCreatedAt(),
                    row.getUpdatedAt());
        }
    }

    public record Text(
            String alias,
            String repositoryPath,
            String repositoryUrl,
            String documentationUrl,
            String snippet,
            String framework,
            String frameworkVersion) {
        public static Text of(PrototypeComponentCodeMappingEntity row) {
            return new Text(
                    row.getAlias(),
                    row.getRepositoryPath(),
                    row.getRepositoryUrl(),
                    row.getDocumentationUrl(),
                    row.getSnippet(),
                    row.getFramework(),
                    row.getFrameworkVersion());
        }
    }

    public record Package(String format, int schemaVersion, List<Text> cards) {}

    /** 非空envelope保证全部卡已删的原键重放也不会落入新建分支。 */
    public record ImportResult(List<ImportItem> items) {}

    public record ImportItem(String id, String status, Card card) {}
}
