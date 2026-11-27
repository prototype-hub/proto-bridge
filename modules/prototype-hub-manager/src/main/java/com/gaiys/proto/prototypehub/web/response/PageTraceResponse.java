package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypePageRelationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypePageTraceContextEntity;
import com.gaiys.proto.prototypehub.entity.PrototypePageTraceDecisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import java.util.List;
import java.util.Map;

/** 授权成员的当前元数据视图；来源缺失时不泄露其他范围标签，不复制历史正文。 */
public final class PageTraceResponse {
    private PageTraceResponse() {}

    public record Relation(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            String fromPageId,
            String toPageId,
            String label,
            String note,
            long configVersion,
            long createdAt,
            long updatedAt) {
        public static Relation of(PrototypePageRelationEntity r) {
            return new Relation(
                    r.getId(),
                    r.getProjectId(),
                    r.getVersionId(),
                    r.getRevisionId(),
                    r.getFromPageId(),
                    r.getToPageId(),
                    r.getLabel(),
                    r.getNote(),
                    r.getConfigVersion(),
                    r.getCreatedAt(),
                    r.getUpdatedAt());
        }
    }

    public record ResolvedSource(
            String kind,
            String id,
            long expectedVersion,
            String status,
            Long currentVersion,
            String title) {}

    public record SourceCandidate(String kind, String id, String title, long configVersion) {}

    public record SourceDetail(
            String kind, String id, String title, long configVersion, Map<String, String> fields) {
        public SourceCandidate candidate() {
            return new SourceCandidate(kind, id, title, configVersion);
        }
    }

    public record Context(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            String pageId,
            boolean entry,
            List<ResolvedSource> references,
            long configVersion,
            Long createdAt,
            Long updatedAt) {
        public static Context of(PrototypePageTraceContextEntity r, List<ResolvedSource> refs) {
            return new Context(
                    r.getId(),
                    r.getProjectId(),
                    r.getVersionId(),
                    r.getRevisionId(),
                    r.getPageId(),
                    r.getEntry(),
                    refs,
                    r.getConfigVersion(),
                    r.getCreatedAt(),
                    r.getUpdatedAt());
        }

        public static Context empty(PrototypeRevisionEntity r, String page) {
            return new Context(
                    null,
                    r.getProjectId(),
                    r.getVersionId(),
                    r.getId(),
                    page,
                    false,
                    List.of(),
                    -1,
                    null,
                    null);
        }
    }

    public record Entry(String pageId, long configVersion, long updatedAt) {
        public static Entry of(PrototypePageTraceContextEntity r) {
            return new Entry(r.getPageId(), r.getConfigVersion(), r.getUpdatedAt());
        }
    }

    public record Decision(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            String pageId,
            String title,
            String decision,
            String rationale,
            String authorId,
            String updatedBy,
            long configVersion,
            long createdAt,
            long updatedAt) {
        public static Decision of(PrototypePageTraceDecisionEntity r) {
            return new Decision(
                    r.getId(),
                    r.getProjectId(),
                    r.getVersionId(),
                    r.getRevisionId(),
                    r.getPageId(),
                    r.getTitle(),
                    r.getDecision(),
                    r.getRationale(),
                    r.getAuthorId(),
                    r.getUpdatedBy(),
                    r.getConfigVersion(),
                    r.getCreatedAt(),
                    r.getUpdatedAt());
        }
    }
}
