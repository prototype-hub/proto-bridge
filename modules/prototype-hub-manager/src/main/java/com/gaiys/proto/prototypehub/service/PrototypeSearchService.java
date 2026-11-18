package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeCopyEntryEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeCopySnapshotEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewMessageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.service.query.PrototypeScopeQuery;
import com.gaiys.proto.prototypehub.web.request.PrototypeSearchRequest;
import com.gaiys.proto.prototypehub.web.request.PrototypeSearchRequest.Type;
import com.gaiys.proto.prototypehub.web.response.PrototypeSearchResponse;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 读取源表的基础搜索，不建立索引副本。动作、账号与项目范围逐次校验，COUNT和数据共享同一数据库谓词。 仅在至多50条已授权结果上生成纯文本片段，无文件I/O、写入、通知或常驻状态。 */
@Service
@RequiredArgsConstructor
public class PrototypeSearchService {
    private static final String CONTAINS =
            PrototypeTextTermFragmentBuilder.COPY_LITERAL_CONTAINS.getTermType();
    private final PrototypeProjectAccessService access;
    private final QueryHelper queries;

    public Mono<PagerResult<PrototypeSearchResponse>> query(PrototypeSearchRequest input) {
        return access.current()
                .flatMap(current -> access.actor(current.userId()))
                .flatMap(
                        actor -> {
                            if (!actor.authentication()
                                    .hasPermission("prototype-hub-version", "query"))
                                return Mono.error(PrototypeProjectAccessService.roleDenied());
                            var query =
                                    switch (input.getType()) {
                                        case COPY -> copies(input);
                                        case MATERIAL -> materials(input);
                                        case FEEDBACK -> feedback(input);
                                    };
                            applyScope(query, actor, input);
                            return query.fetchPaged(
                                    rows ->
                                            Mono.just(
                                                    rows.stream()
                                                            .map(
                                                                    row ->
                                                                            response(
                                                                                    input.getType(),
                                                                                    input
                                                                                            .getKeyword(),
                                                                                    row))
                                                            .toList()));
                        })
                .checkpoint("prototype-hub/search/query");
    }

    private QueryHelper.SelectColumnMapperSpec<Row> columns() {
        return queries.select(Row.class)
                .as(PrototypeProjectEntity::getId, Row::setProjectId)
                .as(PrototypeProjectEntity::getName, Row::setProjectName)
                .as(PrototypeVersionEntity::getId, Row::setVersionId)
                .as(PrototypeVersionEntity::getVersionName, Row::setVersionName)
                .as(PrototypeRevisionEntity::getId, Row::setRevisionId)
                .as(PrototypeRevisionEntity::getCreateTime, Row::setRevisionCreatedAt);
    }

    private QueryHelper.JoinSpec<Row> copies(PrototypeSearchRequest input) {
        var query =
                columns()
                        .as(PrototypeCopyEntryEntity::getId, Row::setSourceId)
                        .as(PrototypeCopyEntryEntity::getPageId, Row::setPageId)
                        .as(PrototypeCopySnapshotEntity::getPagePath, Row::setPagePath)
                        .as(PrototypeCopyEntryEntity::getText, Row::setTitle)
                        .as(PrototypeCopyEntryEntity::getText, Row::setText)
                        .as(PrototypeCopyEntryEntity::getBusinessTerm, Row::setBusinessTerm)
                        .as(PrototypeCopyEntryEntity::getNote, Row::setNote)
                        .as(PrototypeCopyEntryEntity::getUpdatedAt, Row::setUpdatedAt)
                        .from(PrototypeCopyEntryEntity.class)
                        .innerJoin(
                                PrototypeCopySnapshotEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeCopySnapshotEntity::getId,
                                                        PrototypeCopyEntryEntity::getSnapshotId)
                                                .is(
                                                        PrototypeCopySnapshotEntity::getProjectId,
                                                        PrototypeCopyEntryEntity::getProjectId)
                                                .is(
                                                        PrototypeCopySnapshotEntity::getVersionId,
                                                        PrototypeCopyEntryEntity::getVersionId)
                                                .is(
                                                        PrototypeCopySnapshotEntity::getRevisionId,
                                                        PrototypeCopyEntryEntity::getRevisionId)
                                                .is(
                                                        PrototypeCopySnapshotEntity::getPageId,
                                                        PrototypeCopyEntryEntity::getPageId))
                        .innerJoin(
                                PrototypeVersionEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeVersionEntity::getId,
                                                        PrototypeCopyEntryEntity::getVersionId)
                                                .is(
                                                        PrototypeVersionEntity::getProjectId,
                                                        PrototypeCopyEntryEntity::getProjectId))
                        .innerJoin(
                                PrototypeRevisionEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeRevisionEntity::getId,
                                                        PrototypeCopyEntryEntity::getRevisionId)
                                                .is(
                                                        PrototypeRevisionEntity::getVersionId,
                                                        PrototypeCopyEntryEntity::getVersionId)
                                                .is(
                                                        PrototypeRevisionEntity::getProjectId,
                                                        PrototypeCopyEntryEntity::getProjectId));
        query.where(
                condition ->
                        condition
                                .nest()
                                .accept(
                                        PrototypeCopyEntryEntity::getText,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeCopyEntryEntity::getBusinessTerm,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeCopyEntryEntity::getNote,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeCopySnapshotEntity::getPagePath,
                                        CONTAINS,
                                        input.getKeyword())
                                .end());
        query.orderByDesc(PrototypeCopyEntryEntity::getUpdatedAt)
                .orderByAsc(PrototypeCopyEntryEntity::getId);
        return query;
    }

    private QueryHelper.JoinSpec<Row> materials(PrototypeSearchRequest input) {
        var query =
                columns()
                        .as(PrototypeRevisionMaterialEntity::getId, Row::setSourceId)
                        .as(PrototypeRevisionMaterialEntity::getFileName, Row::setTitle)
                        .as(PrototypeRevisionMaterialEntity::getFileName, Row::setFileName)
                        .as(PrototypeRevisionMaterialEntity::getSourceTool, Row::setSourceTool)
                        .as(PrototypeRevisionMaterialEntity::getSourceName, Row::setSourceName)
                        .as(PrototypeRevisionMaterialEntity::getSourceNotes, Row::setSourceNotes)
                        .as(PrototypeRevisionMaterialEntity::getTextContent, Row::setTextContent)
                        .as(PrototypeRevisionMaterialEntity::getMaterialType, Row::setMaterialType)
                        .as(PrototypeRevisionMaterialEntity::getUpdatedAt, Row::setUpdatedAt)
                        .from(PrototypeRevisionMaterialEntity.class)
                        .innerJoin(
                                PrototypeVersionEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeVersionEntity::getId,
                                                        PrototypeRevisionMaterialEntity
                                                                ::getVersionId)
                                                .is(
                                                        PrototypeVersionEntity::getProjectId,
                                                        PrototypeRevisionMaterialEntity
                                                                ::getProjectId))
                        .innerJoin(
                                PrototypeRevisionEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeRevisionEntity::getId,
                                                        PrototypeRevisionMaterialEntity
                                                                ::getRevisionId)
                                                .is(
                                                        PrototypeRevisionEntity::getVersionId,
                                                        PrototypeRevisionMaterialEntity
                                                                ::getVersionId)
                                                .is(
                                                        PrototypeRevisionEntity::getProjectId,
                                                        PrototypeRevisionMaterialEntity
                                                                ::getProjectId));
        query.where(
                condition ->
                        condition
                                .nest()
                                .accept(
                                        PrototypeRevisionMaterialEntity::getFileName,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeRevisionMaterialEntity::getSourceTool,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeRevisionMaterialEntity::getSourceName,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeRevisionMaterialEntity::getSourceNotes,
                                        CONTAINS,
                                        input.getKeyword())
                                .or()
                                .accept(
                                        PrototypeRevisionMaterialEntity::getTextContent,
                                        CONTAINS,
                                        input.getKeyword())
                                .end());
        query.orderByDesc(PrototypeRevisionMaterialEntity::getUpdatedAt)
                .orderByAsc(PrototypeRevisionMaterialEntity::getId);
        return query;
    }

    private QueryHelper.JoinSpec<Row> feedback(PrototypeSearchRequest input) {
        var query =
                columns()
                        .as(PrototypeReviewMessageEntity::getId, Row::setSourceId)
                        .as(PrototypeReviewThreadEntity::getId, Row::setThreadId)
                        .as(PrototypeReviewThreadEntity::getPageId, Row::setPageId)
                        .as(PrototypeReviewThreadEntity::getPagePath, Row::setPagePath)
                        .as(PrototypeReviewMessageEntity::getText, Row::setTitle)
                        .as(PrototypeReviewMessageEntity::getText, Row::setText)
                        .as(PrototypeReviewMessageEntity::getVisibility, Row::setVisibility)
                        .as(PrototypeReviewMessageEntity::getCreateTime, Row::setUpdatedAt)
                        .from(PrototypeReviewMessageEntity.class)
                        .innerJoin(
                                PrototypeReviewThreadEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeReviewThreadEntity::getId,
                                                        PrototypeReviewMessageEntity::getThreadId)
                                                .is(
                                                        PrototypeReviewThreadEntity::getProjectId,
                                                        PrototypeReviewMessageEntity::getProjectId)
                                                .is(
                                                        PrototypeReviewThreadEntity::getVersionId,
                                                        PrototypeReviewMessageEntity::getVersionId)
                                                .is(
                                                        PrototypeReviewThreadEntity::getRevisionId,
                                                        PrototypeReviewMessageEntity
                                                                ::getRevisionId))
                        .innerJoin(
                                PrototypeVersionEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeVersionEntity::getId,
                                                        PrototypeReviewMessageEntity::getVersionId)
                                                .is(
                                                        PrototypeVersionEntity::getProjectId,
                                                        PrototypeReviewMessageEntity::getProjectId))
                        .innerJoin(
                                PrototypeRevisionEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeRevisionEntity::getId,
                                                        PrototypeReviewMessageEntity::getRevisionId)
                                                .is(
                                                        PrototypeRevisionEntity::getVersionId,
                                                        PrototypeReviewMessageEntity::getVersionId)
                                                .is(
                                                        PrototypeRevisionEntity::getProjectId,
                                                        PrototypeReviewMessageEntity
                                                                ::getProjectId));
        query.where(
                condition ->
                        condition.accept(
                                PrototypeReviewMessageEntity::getText,
                                CONTAINS,
                                input.getKeyword()));
        query.orderByDesc(PrototypeReviewMessageEntity::getCreateTime)
                .orderByAsc(PrototypeReviewMessageEntity::getId);
        return query;
    }

    private static void applyScope(
            QueryHelper.JoinSpec<Row> query, Actor actor, PrototypeSearchRequest input) {
        query.innerJoin(
                        PrototypeProjectEntity.class,
                        join ->
                                join.is(
                                        PrototypeProjectEntity::getId,
                                        PrototypeVersionEntity::getProjectId))
                .leftJoin(
                        PrototypeProjectMemberEntity.class,
                        join ->
                                join.is(
                                                PrototypeProjectMemberEntity::getProjectId,
                                                PrototypeProjectEntity::getId)
                                        .and(
                                                PrototypeProjectMemberEntity::getUserId,
                                                actor.userId()));
        query.where(condition -> condition.isNull(PrototypeVersionEntity::getDeletedTime));
        PrototypeScopeQuery.applyScope(
                query, actor.userId(), actor.administrator(), input.getType() == Type.FEEDBACK);
        if (input.getProjectId() != null)
            query.where(
                    condition -> condition.is(PrototypeProjectEntity::getId, input.getProjectId()));
        if (input.getVersionId() != null)
            query.where(
                    condition -> condition.is(PrototypeVersionEntity::getId, input.getVersionId()));
        if (input.getRevisionId() != null)
            query.where(
                    condition ->
                            condition.is(PrototypeRevisionEntity::getId, input.getRevisionId()));
        QueryParamEntity page = new QueryParamEntity();
        page.doPaging(input.getPageIndex(), input.getPageSize());
        // 保留越界页，不自动回退末页；COUNT与数据不承诺并发写入强快照。
        page.setParallelPager(true);
        query.where(page);
    }

    static PrototypeSearchResponse response(Type type, String keyword, Row row) {
        List<Field> fields =
                switch (type) {
                    case COPY ->
                            List.of(
                                    new Field("text", row.text),
                                    new Field("businessTerm", row.businessTerm),
                                    new Field("note", row.note),
                                    new Field("pagePath", row.pagePath));
                    case MATERIAL ->
                            List.of(
                                    new Field("fileName", row.fileName),
                                    new Field("sourceTool", row.sourceTool),
                                    new Field("sourceName", row.sourceName),
                                    new Field("sourceNotes", row.sourceNotes),
                                    new Field("textContent", row.textContent));
                    case FEEDBACK -> List.of(new Field("text", row.text));
                };
        Field match =
                fields.stream()
                        .filter(field -> indexOf(field.value(), keyword) >= 0)
                        .findFirst()
                        .orElse(fields.getFirst());
        return new PrototypeSearchResponse(
                type,
                row.sourceId,
                row.projectId,
                row.projectName,
                row.versionId,
                row.versionName,
                row.revisionId,
                row.revisionCreatedAt,
                row.pageId,
                row.pagePath,
                row.threadId,
                truncate(row.title, 160),
                excerpt(match.value(), keyword),
                match.name(),
                row.visibility,
                row.materialType,
                row.updatedAt);
    }

    private record Field(String name, String value) {}

    private static int indexOf(String value, String keyword) {
        if (value == null) return -1;
        for (int i = 0; i <= value.length() - keyword.length(); i++)
            if (value.regionMatches(true, i, keyword, 0, keyword.length())) return i;
        return -1;
    }

    static String excerpt(String value, String keyword) {
        if (value == null) return "";
        int match = Math.max(0, indexOf(value, keyword));
        int before = value.codePointCount(0, match);
        int start = value.offsetByCodePoints(0, Math.max(0, before - 60));
        return truncate(value.substring(start), 240);
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        int length = value.codePointCount(0, value.length());
        return length <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    /** 只供QueryHelper类型投影；只选择实际需要的来源字段，不能作为HTTP响应。 */
    @Getter
    @Setter
    public static class Row {
        private String sourceId;
        private String projectId;
        private String projectName;
        private String versionId;
        private String versionName;
        private String revisionId;
        private long revisionCreatedAt;
        private String pageId;
        private String pagePath;
        private String threadId;
        private String title;
        private String text;
        private String businessTerm;
        private String note;
        private String fileName;
        private String sourceTool;
        private String sourceName;
        private String sourceNotes;
        private String textContent;
        private String visibility;
        private String materialType;
        private long updatedAt;
    }
}
