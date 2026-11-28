package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityResponseEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityStudyEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.service.PrototypeReviewService.VisitorScope;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.service.validation.UsabilityStudyValidator;
import com.gaiys.proto.prototypehub.service.validation.UsabilityStudyValidator.AnswerFields;
import com.gaiys.proto.prototypehub.service.validation.UsabilityStudyValidator.Definition;
import com.gaiys.proto.prototypehub.web.request.UsabilityStudyRequests;
import com.gaiys.proto.prototypehub.web.response.UsabilityParticipationResponse;
import com.gaiys.proto.prototypehub.web.response.UsabilityParticipationResponse.MutationResult;
import com.gaiys.proto.prototypehub.web.response.UsabilityStudyAggregate;
import com.gaiys.proto.prototypehub.web.response.UsabilityStudyResponse;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 小样本研究及本人答卷的事务边界；只记录明确同意和主动保存/提交的自报数据。 定义开放后冻结；账号或当前访客授权先于回执重放，原键重试不能复活已删内容。
 * 复用HTTP/SQL追踪与现有持久回执；没有遥测、外部发送或常驻状态。
 */
@Service
@RequiredArgsConstructor
public class PrototypeUsabilityStudyService {
    private static final long MAX_VERSION = 9007199254740991L;
    private final ReactiveRepository<PrototypeUsabilityStudyEntity, String> studies;
    private final ReactiveRepository<PrototypeUsabilityResponseEntity, String> responses;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final PrototypeShareService shares;
    private final PrototypeReviewService review;
    private final TransactionalOperator transactions;
    private final UsabilityStudyValidator validator;

    public Mono<PagerResult<UsabilityStudyResponse.Summary>> query(
            String version, String revision, UsabilityStudyRequests.Page input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope ->
                                QueryHelper.queryPager(
                                        studyPage(
                                                scope.fixed(),
                                                input.getPageIndex(),
                                                input.getPageSize(),
                                                false),
                                        this::summaryQuery,
                                        UsabilityStudyResponse.Summary::of))
                .checkpoint("prototype-hub/usability/query");
    }

    private ReactiveQuery<PrototypeUsabilityStudyEntity> summaryQuery() {
        return studies.createQuery()
                .select(
                        PrototypeUsabilityStudyEntity::getId,
                        PrototypeUsabilityStudyEntity::getProjectId,
                        PrototypeUsabilityStudyEntity::getVersionId,
                        PrototypeUsabilityStudyEntity::getRevisionId,
                        PrototypeUsabilityStudyEntity::getTitle,
                        PrototypeUsabilityStudyEntity::getState,
                        PrototypeUsabilityStudyEntity::getContentVersion,
                        PrototypeUsabilityStudyEntity::getConfigVersion,
                        PrototypeUsabilityStudyEntity::getCreatedAt,
                        PrototypeUsabilityStudyEntity::getUpdatedAt);
    }

    public Mono<UsabilityStudyResponse> detail(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope -> require(scope.fixed(), id, false).map(UsabilityStudyResponse::of));
    }

    public Mono<UsabilityStudyResponse> create(
            String version, String revision, UsabilityStudyRequests.Fields input) {
        Definition fields = validator.definition(input);
        return prepareDefinition(version, revision, fields)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            Request request =
                                                    memberRequest(
                                                            scope,
                                                            "create",
                                                            input.getClientRequestId(),
                                                            null,
                                                            null,
                                                            fields);
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(
                                                                                    scope.fixed(),
                                                                                    request.id(),
                                                                                    false)
                                                                            .map(
                                                                                    UsabilityStudyResponse
                                                                                            ::of))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            studyQuery(
                                                                                            scope
                                                                                                    .fixed())
                                                                                    .count()
                                                                                    .flatMap(
                                                                                            count -> {
                                                                                                if (count
                                                                                                        >= 10)
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    UsabilityStudyValidator
                                                                                                                            .limit());
                                                                                                long
                                                                                                        now =
                                                                                                                System
                                                                                                                        .currentTimeMillis();
                                                                                                var
                                                                                                        row =
                                                                                                                new PrototypeUsabilityStudyEntity();
                                                                                                row
                                                                                                        .setId(
                                                                                                                request
                                                                                                                        .id());
                                                                                                row
                                                                                                        .setProjectId(
                                                                                                                scope.fixed()
                                                                                                                        .getProjectId());
                                                                                                row
                                                                                                        .setVersionId(
                                                                                                                version);
                                                                                                row
                                                                                                        .setRevisionId(
                                                                                                                revision);
                                                                                                fields(
                                                                                                        row,
                                                                                                        fields);
                                                                                                row
                                                                                                        .setState(
                                                                                                                "draft");
                                                                                                row
                                                                                                        .setConfigVersion(
                                                                                                                0L);
                                                                                                row
                                                                                                        .setCreatedBy(
                                                                                                                scope
                                                                                                                        .user());
                                                                                                row
                                                                                                        .setUpdatedBy(
                                                                                                                scope
                                                                                                                        .user());
                                                                                                row
                                                                                                        .setCreatedAt(
                                                                                                                now);
                                                                                                row
                                                                                                        .setUpdatedAt(
                                                                                                                now);
                                                                                                return studies.insert(
                                                                                                                row)
                                                                                                        .then(
                                                                                                                record(
                                                                                                                        request,
                                                                                                                        scope
                                                                                                                                .fixed()))
                                                                                                        .thenReturn(
                                                                                                                UsabilityStudyResponse
                                                                                                                        .of(
                                                                                                                                row));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/usability/create");
    }

    public Mono<UsabilityStudyResponse> update(
            String version, String revision, String id, UsabilityStudyRequests.Update input) {
        Definition fields = validator.definition(input);
        return prepareDefinition(version, revision, fields)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            Request request =
                                                    memberRequest(
                                                            scope,
                                                            "update",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            fields);
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(scope.fixed(), id, false)
                                                                            .map(
                                                                                    UsabilityStudyResponse
                                                                                            ::of))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(
                                                                                            scope
                                                                                                    .fixed(),
                                                                                            id,
                                                                                            false)
                                                                                    .flatMap(
                                                                                            row -> {
                                                                                                checkVersion(
                                                                                                        row
                                                                                                                .getConfigVersion(),
                                                                                                        input
                                                                                                                .getExpectedVersion());
                                                                                                if (!"draft"
                                                                                                        .equals(
                                                                                                                row
                                                                                                                        .getState()))
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    frozen());
                                                                                                fields(
                                                                                                        row,
                                                                                                        fields);
                                                                                                row
                                                                                                        .setUpdatedBy(
                                                                                                                scope
                                                                                                                        .user());
                                                                                                row
                                                                                                        .setUpdatedAt(
                                                                                                                System
                                                                                                                        .currentTimeMillis());
                                                                                                row
                                                                                                        .setConfigVersion(
                                                                                                                next(
                                                                                                                        row
                                                                                                                                .getConfigVersion()));
                                                                                                return studies.createUpdate()
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getTitle,
                                                                                                                row
                                                                                                                        .getTitle())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getIntroduction,
                                                                                                                row
                                                                                                                        .getIntroduction())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getConsentText,
                                                                                                                row
                                                                                                                        .getConsentText())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getTasks,
                                                                                                                row
                                                                                                                        .getTasks())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getQuestions,
                                                                                                                row
                                                                                                                        .getQuestions())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getConfigVersion,
                                                                                                                row
                                                                                                                        .getConfigVersion())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getUpdatedBy,
                                                                                                                row
                                                                                                                        .getUpdatedBy())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getUpdatedAt,
                                                                                                                row
                                                                                                                        .getUpdatedAt())
                                                                                                        .where(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getId,
                                                                                                                id)
                                                                                                        .and(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getConfigVersion,
                                                                                                                input
                                                                                                                        .getExpectedVersion())
                                                                                                        .execute()
                                                                                                        .flatMap(
                                                                                                                changed ->
                                                                                                                        changed
                                                                                                                                        == 1
                                                                                                                                ? record(
                                                                                                                                                request,
                                                                                                                                                scope
                                                                                                                                                        .fixed())
                                                                                                                                        .thenReturn(
                                                                                                                                                UsabilityStudyResponse
                                                                                                                                                        .of(
                                                                                                                                                                row))
                                                                                                                                : Mono
                                                                                                                                        .error(
                                                                                                                                                conflict()));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/usability/update");
    }

    public Mono<UsabilityStudyResponse> open(
            String version, String revision, String id, UsabilityStudyRequests.Mutation input) {
        return state(version, revision, id, input, true);
    }

    public Mono<UsabilityStudyResponse> close(
            String version, String revision, String id, UsabilityStudyRequests.Mutation input) {
        return state(version, revision, id, input, false);
    }

    private Mono<UsabilityStudyResponse> state(
            String version,
            String revision,
            String id,
            UsabilityStudyRequests.Mutation input,
            boolean opening) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            Request request =
                                                    memberRequest(
                                                            scope,
                                                            opening ? "open" : "close",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            null);
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(scope.fixed(), id, false)
                                                                            .map(
                                                                                    UsabilityStudyResponse
                                                                                            ::of))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(
                                                                                            scope
                                                                                                    .fixed(),
                                                                                            id,
                                                                                            false)
                                                                                    .flatMap(
                                                                                            row -> {
                                                                                                checkVersion(
                                                                                                        row
                                                                                                                .getConfigVersion(),
                                                                                                        input
                                                                                                                .getExpectedVersion());
                                                                                                if (!(opening
                                                                                                                ? "draft"
                                                                                                                : "open")
                                                                                                        .equals(
                                                                                                                row
                                                                                                                        .getState()))
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    frozen());
                                                                                                if (opening)
                                                                                                    validator
                                                                                                            .opening(
                                                                                                                    row);
                                                                                                row
                                                                                                        .setConfigVersion(
                                                                                                                next(
                                                                                                                        row
                                                                                                                                .getConfigVersion()));
                                                                                                row
                                                                                                        .setState(
                                                                                                                opening
                                                                                                                        ? "open"
                                                                                                                        : "closed");
                                                                                                if (opening)
                                                                                                    row
                                                                                                            .setContentVersion(
                                                                                                                    row
                                                                                                                            .getConfigVersion());
                                                                                                row
                                                                                                        .setUpdatedBy(
                                                                                                                scope
                                                                                                                        .user());
                                                                                                row
                                                                                                        .setUpdatedAt(
                                                                                                                System
                                                                                                                        .currentTimeMillis());
                                                                                                return studies.createUpdate()
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getState,
                                                                                                                row
                                                                                                                        .getState())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getContentVersion,
                                                                                                                row
                                                                                                                        .getContentVersion())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getConfigVersion,
                                                                                                                row
                                                                                                                        .getConfigVersion())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getUpdatedBy,
                                                                                                                row
                                                                                                                        .getUpdatedBy())
                                                                                                        .set(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getUpdatedAt,
                                                                                                                row
                                                                                                                        .getUpdatedAt())
                                                                                                        .where(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getId,
                                                                                                                id)
                                                                                                        .and(
                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                        ::getConfigVersion,
                                                                                                                input
                                                                                                                        .getExpectedVersion())
                                                                                                        .execute()
                                                                                                        .flatMap(
                                                                                                                changed ->
                                                                                                                        changed
                                                                                                                                        == 1
                                                                                                                                ? record(
                                                                                                                                                request,
                                                                                                                                                scope
                                                                                                                                                        .fixed())
                                                                                                                                        .thenReturn(
                                                                                                                                                UsabilityStudyResponse
                                                                                                                                                        .of(
                                                                                                                                                                row))
                                                                                                                                : Mono
                                                                                                                                        .error(
                                                                                                                                                conflict()));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/usability/state");
    }

    public Mono<Boolean> delete(
            String version, String revision, String id, UsabilityStudyRequests.Mutation input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            Request request =
                                                    memberRequest(
                                                            scope,
                                                            "delete",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            null);
                                            return receipts.replay(
                                                            request, ignored -> Mono.just(true))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(
                                                                                            scope
                                                                                                    .fixed(),
                                                                                            id,
                                                                                            false)
                                                                                    .flatMap(
                                                                                            row -> {
                                                                                                checkVersion(
                                                                                                        row
                                                                                                                .getConfigVersion(),
                                                                                                        input
                                                                                                                .getExpectedVersion());
                                                                                                // 答卷和定义同一事务删除；研究CAS失败时子行删除也回滚。
                                                                                                return responses
                                                                                                        .createDelete()
                                                                                                        .where(
                                                                                                                PrototypeUsabilityResponseEntity
                                                                                                                        ::getStudyId,
                                                                                                                id)
                                                                                                        .and(
                                                                                                                PrototypeUsabilityResponseEntity
                                                                                                                        ::getProjectId,
                                                                                                                scope.fixed()
                                                                                                                        .getProjectId())
                                                                                                        .and(
                                                                                                                PrototypeUsabilityResponseEntity
                                                                                                                        ::getVersionId,
                                                                                                                version)
                                                                                                        .and(
                                                                                                                PrototypeUsabilityResponseEntity
                                                                                                                        ::getRevisionId,
                                                                                                                revision)
                                                                                                        .execute()
                                                                                                        .then(
                                                                                                                studies.createDelete()
                                                                                                                        .where(
                                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                                        ::getId,
                                                                                                                                id)
                                                                                                                        .and(
                                                                                                                                PrototypeUsabilityStudyEntity
                                                                                                                                        ::getConfigVersion,
                                                                                                                                input
                                                                                                                                        .getExpectedVersion())
                                                                                                                        .execute())
                                                                                                        .flatMap(
                                                                                                                changed ->
                                                                                                                        changed
                                                                                                                                        == 1
                                                                                                                                ? record(
                                                                                                                                                request,
                                                                                                                                                scope
                                                                                                                                                        .fixed())
                                                                                                                                        .thenReturn(
                                                                                                                                                true)
                                                                                                                                : Mono
                                                                                                                                        .error(
                                                                                                                                                conflict()));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/usability/delete");
    }

    public Mono<PagerResult<UsabilityParticipationResponse.Member>> responsePage(
            String version, String revision, String id, UsabilityStudyRequests.Page input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope ->
                                require(scope.fixed(), id, false)
                                        .flatMap(
                                                study -> {
                                                    var query =
                                                            responsePageQuery(
                                                                    study,
                                                                    input.getPageIndex(),
                                                                    input.getPageSize());
                                                    return QueryHelper.queryPager(
                                                            query,
                                                            this::memberResponseQuery,
                                                            UsabilityParticipationResponse.Member
                                                                    ::of);
                                                }));
    }

    private ReactiveQuery<PrototypeUsabilityResponseEntity> memberResponseQuery() {
        return responses
                .createQuery()
                .select(
                        PrototypeUsabilityResponseEntity::getId,
                        PrototypeUsabilityResponseEntity::getContentVersion,
                        PrototypeUsabilityResponseEntity::getStatus,
                        PrototypeUsabilityResponseEntity::getTaskAnswers,
                        PrototypeUsabilityResponseEntity::getAnswers,
                        PrototypeUsabilityResponseEntity::getFeedback);
    }

    public Mono<UsabilityParticipationResponse.Member> responseDetail(
            String version, String revision, String id, String responseId) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope ->
                                require(scope.fixed(), id, false)
                                        .flatMap(
                                                study ->
                                                        responseQuery(study)
                                                                .and(
                                                                        PrototypeUsabilityResponseEntity
                                                                                ::getId,
                                                                        responseId)
                                                                .and(
                                                                        PrototypeUsabilityResponseEntity
                                                                                ::getStatus,
                                                                        "submitted")
                                                                .fetch()
                                                                .singleOrEmpty()
                                                                .map(
                                                                        UsabilityParticipationResponse
                                                                                        .Member
                                                                                ::of)
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                () ->
                                                                                        PrototypeHubException
                                                                                                .missing(
                                                                                                        "usability_response")))));
    }

    public Mono<UsabilityStudyAggregate> summary(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope ->
                                require(scope.fixed(), id, false)
                                        .flatMap(
                                                study ->
                                                        responseQuery(study)
                                                                .and(
                                                                        PrototypeUsabilityResponseEntity
                                                                                ::getStatus,
                                                                        "submitted")
                                                                .select(
                                                                        PrototypeUsabilityResponseEntity
                                                                                ::getTaskAnswers,
                                                                        PrototypeUsabilityResponseEntity
                                                                                ::getAnswers)
                                                                .paging(0, 201)
                                                                .fetch()
                                                                .collectList()
                                                                .map(
                                                                        rows ->
                                                                                UsabilityStudyValidator
                                                                                        .aggregate(
                                                                                                study,
                                                                                                rows))))
                .checkpoint("prototype-hub/usability/summary");
    }

    /** 身份上下文读取没有研究写入；只有后续同意动作能建立答卷。 */
    public Mono<PagerResult<UsabilityStudyResponse.PublicSummary>> publicQuery(
            String token, UsabilityStudyRequests.PublicQuery input, String cookie) {
        return review.context(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .then(
                        shares.requireRevisionAccess(
                                token, input.getAccessKey(), input.getRevisionId()))
                .flatMap(
                        fixed ->
                                QueryHelper.queryPager(
                                        studyPage(
                                                fixed,
                                                input.getPageIndex(),
                                                input.getPageSize(),
                                                true),
                                        () ->
                                                studies.createQuery()
                                                        .select(
                                                                PrototypeUsabilityStudyEntity
                                                                        ::getId,
                                                                PrototypeUsabilityStudyEntity
                                                                        ::getTitle,
                                                                PrototypeUsabilityStudyEntity
                                                                        ::getState),
                                        UsabilityStudyResponse.PublicSummary::of));
    }

    public Mono<UsabilityStudyResponse.PublicContext> publicContext(
            String token, String id, UsabilityStudyRequests.PublicGrant input, String cookie) {
        return review.context(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .flatMap(
                        identity ->
                                shares.requireRevisionAccess(
                                                token, input.getAccessKey(), input.getRevisionId())
                                        .flatMap(
                                                fixed ->
                                                        require(fixed, id, true)
                                                                .flatMap(
                                                                        study -> {
                                                                            var definition =
                                                                                    UsabilityStudyResponse
                                                                                            .PublicStudy
                                                                                            .of(
                                                                                                    study);
                                                                            if (identity.session()
                                                                                    == null)
                                                                                return Mono.just(
                                                                                        new UsabilityStudyResponse
                                                                                                .PublicContext(
                                                                                                definition,
                                                                                                null));
                                                                            return review.requireStudyVisitor(
                                                                                            token,
                                                                                            input
                                                                                                    .getRevisionId(),
                                                                                            input
                                                                                                    .getAccessKey(),
                                                                                            cookie)
                                                                                    .flatMap(
                                                                                            visitor ->
                                                                                                    own(
                                                                                                                    visitor,
                                                                                                                    input
                                                                                                                            .getRevisionId(),
                                                                                                                    id)
                                                                                                            .map(
                                                                                                                    UsabilityParticipationResponse
                                                                                                                            ::of))
                                                                                    .map(
                                                                                            value ->
                                                                                                    new UsabilityStudyResponse
                                                                                                            .PublicContext(
                                                                                                            definition,
                                                                                                            value))
                                                                                    .defaultIfEmpty(
                                                                                            new UsabilityStudyResponse
                                                                                                    .PublicContext(
                                                                                                    definition,
                                                                                                    null));
                                                                        })));
    }

    public Mono<MutationResult> consent(
            String token, String id, UsabilityStudyRequests.Consent input, String cookie) {
        return review.lockedStudyVisitor(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .flatMap(
                        visitor -> {
                            Request request =
                                    participantRequest(
                                            visitor,
                                            input.getRevisionId(),
                                            id,
                                            "consent",
                                            input.getClientRequestId(),
                                            input.getContentVersion(),
                                            null,
                                            "true");
                            return replay(visitor, input.getRevisionId(), id, request)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            requireGuestStudy(
                                                                            visitor,
                                                                            input.getRevisionId(),
                                                                            id)
                                                                    .flatMap(
                                                                            study -> {
                                                                                open(
                                                                                        study,
                                                                                        input
                                                                                                .getContentVersion());
                                                                                return own(
                                                                                                visitor,
                                                                                                input
                                                                                                        .getRevisionId(),
                                                                                                id)
                                                                                        .flatMap(
                                                                                                row ->
                                                                                                        record(
                                                                                                                        request,
                                                                                                                        study)
                                                                                                                .thenReturn(
                                                                                                                        MutationResult
                                                                                                                                .current(
                                                                                                                                        row)))
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .defer(
                                                                                                                () ->
                                                                                                                        responseQuery(
                                                                                                                                        study)
                                                                                                                                .count()
                                                                                                                                .flatMap(
                                                                                                                                        count -> {
                                                                                                                                            if (count
                                                                                                                                                    >= 200)
                                                                                                                                                return Mono
                                                                                                                                                        .error(
                                                                                                                                                                UsabilityStudyValidator
                                                                                                                                                                        .limit());
                                                                                                                                            long
                                                                                                                                                    now =
                                                                                                                                                            System
                                                                                                                                                                    .currentTimeMillis();
                                                                                                                                            var
                                                                                                                                                    row =
                                                                                                                                                            new PrototypeUsabilityResponseEntity();
                                                                                                                                            row
                                                                                                                                                    .setId(
                                                                                                                                                            participationId(
                                                                                                                                                                    visitor,
                                                                                                                                                                    id));
                                                                                                                                            row
                                                                                                                                                    .setProjectId(
                                                                                                                                                            study
                                                                                                                                                                    .getProjectId());
                                                                                                                                            row
                                                                                                                                                    .setVersionId(
                                                                                                                                                            study
                                                                                                                                                                    .getVersionId());
                                                                                                                                            row
                                                                                                                                                    .setRevisionId(
                                                                                                                                                            study
                                                                                                                                                                    .getRevisionId());
                                                                                                                                            row
                                                                                                                                                    .setStudyId(
                                                                                                                                                            id);
                                                                                                                                            row
                                                                                                                                                    .setShareId(
                                                                                                                                                            visitor.share()
                                                                                                                                                                    .getId());
                                                                                                                                            row
                                                                                                                                                    .setGuestId(
                                                                                                                                                            visitor.identity()
                                                                                                                                                                    .guestId());
                                                                                                                                            row
                                                                                                                                                    .setVerificationLevel(
                                                                                                                                                            visitor.identity()
                                                                                                                                                                    .verificationLevel());
                                                                                                                                            row
                                                                                                                                                    .setContentVersion(
                                                                                                                                                            study
                                                                                                                                                                    .getContentVersion());
                                                                                                                                            row
                                                                                                                                                    .setStatus(
                                                                                                                                                            "draft");
                                                                                                                                            row
                                                                                                                                                    .setConsentedAt(
                                                                                                                                                            now);
                                                                                                                                            row
                                                                                                                                                    .setTaskAnswers(
                                                                                                                                                            List
                                                                                                                                                                    .of());
                                                                                                                                            row
                                                                                                                                                    .setAnswers(
                                                                                                                                                            List
                                                                                                                                                                    .of());
                                                                                                                                            row
                                                                                                                                                    .setFeedback(
                                                                                                                                                            "");
                                                                                                                                            row
                                                                                                                                                    .setConfigVersion(
                                                                                                                                                            0L);
                                                                                                                                            row
                                                                                                                                                    .setCreatedAt(
                                                                                                                                                            now);
                                                                                                                                            row
                                                                                                                                                    .setUpdatedAt(
                                                                                                                                                            now);
                                                                                                                                            return responses
                                                                                                                                                    .insert(
                                                                                                                                                            row)
                                                                                                                                                    .then(
                                                                                                                                                            record(
                                                                                                                                                                    request,
                                                                                                                                                                    study))
                                                                                                                                                    .thenReturn(
                                                                                                                                                            MutationResult
                                                                                                                                                                    .current(
                                                                                                                                                                            row));
                                                                                                                                        })));
                                                                            })));
                        })
                .as(transactions::transactional)
                .checkpoint("prototype-hub/usability/consent");
    }

    public Mono<MutationResult> progress(
            String token,
            String id,
            UsabilityStudyRequests.Progress input,
            String cookie,
            boolean submit) {
        return review.lockedStudyVisitor(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .flatMap(
                        visitor -> {
                            Request request =
                                    participantRequest(
                                            visitor,
                                            input.getRevisionId(),
                                            id,
                                            submit ? "submit" : "progress",
                                            input.getClientRequestId(),
                                            input.getContentVersion(),
                                            input.getExpectedVersion(),
                                            validator.encode(
                                                    new AnswerFields(
                                                            input.getTaskAnswers(),
                                                            input.getAnswers(),
                                                            Objects.toString(
                                                                    input.getFeedback(), ""))));
                            return replay(visitor, input.getRevisionId(), id, request)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            requireGuestStudy(
                                                                            visitor,
                                                                            input.getRevisionId(),
                                                                            id)
                                                                    .flatMap(
                                                                            study -> {
                                                                                open(
                                                                                        study,
                                                                                        input
                                                                                                .getContentVersion());
                                                                                AnswerFields
                                                                                        fields =
                                                                                                validator
                                                                                                        .answers(
                                                                                                                study,
                                                                                                                input,
                                                                                                                submit);
                                                                                return requireOwn(
                                                                                                visitor,
                                                                                                input
                                                                                                        .getRevisionId(),
                                                                                                id)
                                                                                        .flatMap(
                                                                                                row -> {
                                                                                                    checkVersion(
                                                                                                            row
                                                                                                                    .getConfigVersion(),
                                                                                                            input
                                                                                                                    .getExpectedVersion());
                                                                                                    if (!"draft"
                                                                                                            .equals(
                                                                                                                    row
                                                                                                                            .getStatus()))
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        frozen());
                                                                                                    row
                                                                                                            .setTaskAnswers(
                                                                                                                    fields
                                                                                                                            .taskAnswers());
                                                                                                    row
                                                                                                            .setAnswers(
                                                                                                                    fields
                                                                                                                            .answers());
                                                                                                    row
                                                                                                            .setFeedback(
                                                                                                                    fields
                                                                                                                            .feedback());
                                                                                                    if (submit) {
                                                                                                        row
                                                                                                                .setStatus(
                                                                                                                        "submitted");
                                                                                                        row
                                                                                                                .setSubmittedAt(
                                                                                                                        System
                                                                                                                                .currentTimeMillis());
                                                                                                    }
                                                                                                    return updateResponse(
                                                                                                                    row,
                                                                                                                    input
                                                                                                                            .getExpectedVersion())
                                                                                                            .then(
                                                                                                                    record(
                                                                                                                            request,
                                                                                                                            study))
                                                                                                            .thenReturn(
                                                                                                                    MutationResult
                                                                                                                            .current(
                                                                                                                                    row));
                                                                                                });
                                                                            })));
                        })
                .as(transactions::transactional)
                .checkpoint("prototype-hub/usability/progress");
    }

    public Mono<MutationResult> withdraw(
            String token, String id, UsabilityStudyRequests.Withdraw input, String cookie) {
        return review.lockedStudyVisitor(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .flatMap(
                        visitor -> {
                            Request request =
                                    participantRequest(
                                            visitor,
                                            input.getRevisionId(),
                                            id,
                                            "withdraw",
                                            input.getClientRequestId(),
                                            input.getContentVersion(),
                                            input.getExpectedVersion(),
                                            null);
                            return replay(visitor, input.getRevisionId(), id, request)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            requireGuestStudy(
                                                                            visitor,
                                                                            input.getRevisionId(),
                                                                            id)
                                                                    .flatMap(
                                                                            study -> {
                                                                                content(
                                                                                        study,
                                                                                        input
                                                                                                .getContentVersion());
                                                                                return requireOwn(
                                                                                                visitor,
                                                                                                input
                                                                                                        .getRevisionId(),
                                                                                                id)
                                                                                        .flatMap(
                                                                                                row -> {
                                                                                                    checkVersion(
                                                                                                            row
                                                                                                                    .getConfigVersion(),
                                                                                                            input
                                                                                                                    .getExpectedVersion());
                                                                                                    row
                                                                                                            .setStatus(
                                                                                                                    "withdrawn");
                                                                                                    row
                                                                                                            .setTaskAnswers(
                                                                                                                    List
                                                                                                                            .of());
                                                                                                    row
                                                                                                            .setAnswers(
                                                                                                                    List
                                                                                                                            .of());
                                                                                                    row
                                                                                                            .setFeedback(
                                                                                                                    "");
                                                                                                    row
                                                                                                            .setSubmittedAt(
                                                                                                                    null);
                                                                                                    return updateResponse(
                                                                                                                    row,
                                                                                                                    input
                                                                                                                            .getExpectedVersion())
                                                                                                            .then(
                                                                                                                    record(
                                                                                                                            request,
                                                                                                                            study))
                                                                                                            .thenReturn(
                                                                                                                    MutationResult
                                                                                                                            .current(
                                                                                                                                    row));
                                                                                                });
                                                                            })));
                        })
                .as(transactions::transactional)
                .checkpoint("prototype-hub/usability/withdraw");
    }

    private Mono<Void> updateResponse(PrototypeUsabilityResponseEntity row, long expected) {
        row.setConfigVersion(next(row.getConfigVersion()));
        row.setUpdatedAt(System.currentTimeMillis());
        var update =
                responses
                        .createUpdate()
                        .set(PrototypeUsabilityResponseEntity::getTaskAnswers, row.getTaskAnswers())
                        .set(PrototypeUsabilityResponseEntity::getAnswers, row.getAnswers())
                        .set(PrototypeUsabilityResponseEntity::getFeedback, row.getFeedback())
                        .set(PrototypeUsabilityResponseEntity::getStatus, row.getStatus())
                        .set(
                                PrototypeUsabilityResponseEntity::getConfigVersion,
                                row.getConfigVersion())
                        .set(PrototypeUsabilityResponseEntity::getUpdatedAt, row.getUpdatedAt());
        if (row.getSubmittedAt() == null)
            update.setNull(PrototypeUsabilityResponseEntity::getSubmittedAt);
        else update.set(PrototypeUsabilityResponseEntity::getSubmittedAt, row.getSubmittedAt());
        return update.where(PrototypeUsabilityResponseEntity::getId, row.getId())
                .and(PrototypeUsabilityResponseEntity::getConfigVersion, expected)
                .execute()
                .flatMap(changed -> changed == 1 ? Mono.empty() : Mono.error(conflict()));
    }

    private Mono<Scope> prepare(String version, String revision, Action action) {
        return access.current().flatMap(actor -> authorize(actor, version, revision, action, true));
    }

    private Mono<Scope> authorize(
            Actor actor, String version, String revision, Action action, boolean audit) {
        if (!actor.authentication()
                .hasPermission("prototype-hub-version", action == Action.READ ? "query" : "save"))
            return Mono.error(PrototypeProjectAccessService.roleDenied());
        return access.requireVersion(actor, version, action, audit)
                .flatMap(row -> revisions.require(row, revision))
                .map(fixed -> new Scope(actor.userId(), fixed));
    }

    private Mono<Scope> prepareDefinition(String version, String revision, Definition fields) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                revisions
                                        .pages(scope.fixed())
                                        .map(
                                                pages -> {
                                                    for (var task : fields.tasks())
                                                        PrototypeDemoScriptService.validateSteps(
                                                                task.steps(), pages);
                                                    return scope;
                                                }));
    }

    private <T> Mono<T> locked(Scope scope, Supplier<Mono<T>> work) {
        return access.lockActiveUsers(scope.user())
                .then(access.lockProject(scope.fixed().getProjectId()))
                .then(access.lockVersion(scope.fixed().getVersionId()))
                .then(access.actor(scope.user()))
                .flatMap(
                        actor ->
                                authorize(
                                        actor,
                                        scope.fixed().getVersionId(),
                                        scope.fixed().getId(),
                                        Action.EDIT,
                                        false))
                .filter(
                        current ->
                                scope.fixed().getProjectId().equals(current.fixed().getProjectId()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")))
                .then(Mono.defer(work))
                .as(transactions::transactional);
    }

    private ReactiveQuery<PrototypeUsabilityStudyEntity> studyQuery(PrototypeRevisionEntity fixed) {
        return studies.createQuery()
                .where(PrototypeUsabilityStudyEntity::getProjectId, fixed.getProjectId())
                .and(PrototypeUsabilityStudyEntity::getVersionId, fixed.getVersionId())
                .and(PrototypeUsabilityStudyEntity::getRevisionId, fixed.getId());
    }

    private QueryParamEntity studyPage(
            PrototypeRevisionEntity fixed, int page, int size, boolean shared) {
        var builder =
                QueryParamEntity.newQuery()
                        .where(PrototypeUsabilityStudyEntity::getProjectId, fixed.getProjectId())
                        .and(PrototypeUsabilityStudyEntity::getVersionId, fixed.getVersionId())
                        .and(PrototypeUsabilityStudyEntity::getRevisionId, fixed.getId());
        if (shared) builder.in(PrototypeUsabilityStudyEntity::getState, List.of("open", "closed"));
        var value =
                builder.orderByDesc(PrototypeUsabilityStudyEntity::getUpdatedAt)
                        .orderByAsc(PrototypeUsabilityStudyEntity::getId)
                        .getParam();
        value.doPaging(page, size);
        value.setParallelPager(true);
        return value;
    }

    private Mono<PrototypeUsabilityStudyEntity> find(
            PrototypeRevisionEntity fixed, String id, boolean shared) {
        var q = studyQuery(fixed).and(PrototypeUsabilityStudyEntity::getId, id);
        if (shared) q.in(PrototypeUsabilityStudyEntity::getState, List.of("open", "closed"));
        return q.fetch().singleOrEmpty();
    }

    private Mono<PrototypeUsabilityStudyEntity> require(
            PrototypeRevisionEntity fixed, String id, boolean shared) {
        return find(fixed, id, shared)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("usability_study")));
    }

    private Mono<PrototypeUsabilityStudyEntity> requireGuestStudy(
            VisitorScope visitor, String revision, String id) {
        return studies.createQuery()
                .where(PrototypeUsabilityStudyEntity::getId, id)
                .and(PrototypeUsabilityStudyEntity::getProjectId, visitor.share().getProjectId())
                .and(PrototypeUsabilityStudyEntity::getVersionId, visitor.share().getVersionId())
                .and(PrototypeUsabilityStudyEntity::getRevisionId, revision)
                .in(PrototypeUsabilityStudyEntity::getState, List.of("open", "closed"))
                .fetch()
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("usability_study")));
    }

    private ReactiveQuery<PrototypeUsabilityResponseEntity> responseQuery(
            PrototypeUsabilityStudyEntity study) {
        return responses
                .createQuery()
                .where(PrototypeUsabilityResponseEntity::getProjectId, study.getProjectId())
                .and(PrototypeUsabilityResponseEntity::getVersionId, study.getVersionId())
                .and(PrototypeUsabilityResponseEntity::getRevisionId, study.getRevisionId())
                .and(PrototypeUsabilityResponseEntity::getStudyId, study.getId());
    }

    private QueryParamEntity responsePageQuery(
            PrototypeUsabilityStudyEntity study, int page, int size) {
        var query =
                QueryParamEntity.newQuery()
                        .where(PrototypeUsabilityResponseEntity::getProjectId, study.getProjectId())
                        .and(PrototypeUsabilityResponseEntity::getVersionId, study.getVersionId())
                        .and(PrototypeUsabilityResponseEntity::getRevisionId, study.getRevisionId())
                        .and(PrototypeUsabilityResponseEntity::getStudyId, study.getId())
                        .and(PrototypeUsabilityResponseEntity::getStatus, "submitted")
                        .orderByDesc(PrototypeUsabilityResponseEntity::getUpdatedAt)
                        .orderByAsc(PrototypeUsabilityResponseEntity::getId)
                        .getParam();
        query.doPaging(page, size);
        query.setParallelPager(true);
        return query;
    }

    private Mono<PrototypeUsabilityResponseEntity> own(
            VisitorScope visitor, String revision, String id) {
        return responses
                .createQuery()
                .where(PrototypeUsabilityResponseEntity::getId, participationId(visitor, id))
                .and(PrototypeUsabilityResponseEntity::getProjectId, visitor.share().getProjectId())
                .and(PrototypeUsabilityResponseEntity::getVersionId, visitor.share().getVersionId())
                .and(PrototypeUsabilityResponseEntity::getRevisionId, revision)
                .and(PrototypeUsabilityResponseEntity::getStudyId, id)
                .and(PrototypeUsabilityResponseEntity::getShareId, visitor.share().getId())
                .and(PrototypeUsabilityResponseEntity::getGuestId, visitor.identity().guestId())
                .and(
                        PrototypeUsabilityResponseEntity::getVerificationLevel,
                        visitor.identity().verificationLevel())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeUsabilityResponseEntity> requireOwn(
            VisitorScope visitor, String revision, String id) {
        return own(visitor, revision, id)
                .switchIfEmpty(
                        Mono.error(() -> PrototypeHubException.missing("usability_response")));
    }

    private Mono<MutationResult> replay(
            VisitorScope visitor, String revision, String id, Request request) {
        return receipts.replay(
                request,
                ignored ->
                        own(visitor, revision, id)
                                .map(MutationResult::current)
                                .defaultIfEmpty(MutationResult.missing()));
    }

    private Request memberRequest(
            Scope scope, String action, String key, String id, Long expected, Definition fields) {
        return receipts.request(
                scope.user(),
                "usability-" + action,
                scope.fixed().getId(),
                key,
                scope.fixed().getProjectId(),
                scope.fixed().getVersionId(),
                scope.fixed().getId(),
                id,
                Objects.toString(expected, null),
                fields == null ? null : validator.encode(fields));
    }

    private Request participantRequest(
            VisitorScope visitor,
            String revision,
            String id,
            String action,
            String key,
            long contentVersion,
            Long expected,
            String payload) {
        String subject =
                PrototypeReviewKeys.digest(
                        "usability-actor-v1:"
                                + visitor.identity().verificationLevel()
                                + ":"
                                + visitor.identity().guestId());
        return receipts.request(
                subject,
                "usability-" + action,
                visitor.share().getId(),
                key,
                visitor.share().getProjectId(),
                visitor.share().getVersionId(),
                revision,
                id,
                Long.toString(contentVersion),
                Objects.toString(expected, null),
                payload);
    }

    private static String participationId(VisitorScope visitor, String id) {
        return PrototypeReviewKeys.digest(
                "usability-response-v1:"
                        + id.length()
                        + ":"
                        + id
                        + ":"
                        + visitor.share().getId()
                        + ":"
                        + visitor.identity().verificationLevel()
                        + ":"
                        + visitor.identity().guestId());
    }

    private Mono<Void> record(Request request, PrototypeRevisionEntity fixed) {
        return receipts.record(request, fixed.getVersionId(), fixed.getId());
    }

    private Mono<Void> record(Request request, PrototypeUsabilityStudyEntity study) {
        return receipts.record(request, study.getVersionId(), study.getRevisionId());
    }

    private static void fields(PrototypeUsabilityStudyEntity row, Definition fields) {
        row.setTitle(fields.title());
        row.setIntroduction(fields.introduction());
        row.setConsentText(fields.consentText());
        row.setTasks(fields.tasks());
        row.setQuestions(fields.questions());
    }

    private static void open(PrototypeUsabilityStudyEntity study, long version) {
        content(study, version);
        if (!"open".equals(study.getState())) throw closed();
    }

    private static void content(PrototypeUsabilityStudyEntity study, long version) {
        if (study.getContentVersion() == null || study.getContentVersion() != version)
            throw conflict();
    }

    private static void checkVersion(long actual, long expected) {
        if (actual != expected) throw conflict();
    }

    private static long next(long actual) {
        if (actual >= MAX_VERSION) throw conflict();
        return actual + 1;
    }

    public static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.usability_conflict", "PROTOTYPE_HUB_USABILITY_CONFLICT", 409);
    }

    public static PrototypeHubException frozen() {
        return new PrototypeHubException(
                "error.prototype-hub.usability_frozen", "PROTOTYPE_HUB_USABILITY_FROZEN", 409);
    }

    public static PrototypeHubException closed() {
        return new PrototypeHubException(
                "error.prototype-hub.usability_closed", "PROTOTYPE_HUB_USABILITY_CLOSED", 409);
    }

    private record Scope(String user, PrototypeRevisionEntity fixed) {}
}
