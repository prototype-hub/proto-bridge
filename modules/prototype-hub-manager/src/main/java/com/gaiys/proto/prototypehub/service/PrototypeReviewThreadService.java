package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewEventEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewMessageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewRateEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.ReviewMemberTextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewMessageQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewPageRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewTextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadStateRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewMessageResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewThreadResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/** 页面文本评审的唯一持久化边界；先完全释放清单读取再进入FileWork，写入持项目→版本锁。 消息、状态、领域outbox与回执同事务；基础版没有投递任务或业务邮件副作用。 */
@Service
@RequiredArgsConstructor
public class PrototypeReviewThreadService {
    private static final long HOUR = 3_600_000L;
    private static final long EVENT_TTL = 30L * 24 * HOUR;
    private final PrototypeReviewService review;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    private final ReactiveRepository<PrototypeReviewThreadEntity, String> threads;
    private final ReactiveRepository<PrototypeReviewMessageEntity, String> messages;
    private final ReactiveRepository<PrototypeReviewEventEntity, String> events;
    private final ReactiveRepository<PrototypeReviewRateEntity, String> rates;
    private final ReactiveRepository<PrototypeCreationReceiptEntity, String> receiptRows;
    private final PrototypeCreationReceiptService receipts;
    private final FileWork work;
    private final TransactionalOperator transactions;
    private final PrototypeReviewImageService images;
    private final FileCleanupService cleanup;

    public Mono<PagerResult<ReviewThreadResponse>> queryVisitor(
            String token, ReviewThreadQueryRequest input, String cookie) {
        return visitorPage(token, input, cookie).flatMap(scope -> queryThreads(scope, input));
    }

    public Mono<PagerResult<ReviewThreadResponse>> queryMember(
            String versionId, ReviewThreadQueryRequest input) {
        return memberPage(versionId, input).flatMap(scope -> queryThreads(scope, input));
    }

    /** 深链只定位当前访客有权读取的页面线程，不依赖列表分页或放宽身份范围。 */
    public Mono<ReviewThreadResponse> detailVisitor(
            String token, String threadId, ReviewPageRequest input, String cookie) {
        return visitorPage(token, input, cookie)
                .flatMap(scope -> requireThread(scope, threadId))
                .map(PrototypeReviewThreadService::response);
    }

    /** 内部扩展复用同一页面授权与清单读取边界，不暴露访客凭据或复制权限规则。 */
    Mono<MemberThread> currentThread(String versionId, String threadId, ReviewPageRequest input) {
        return memberPage(versionId, input)
                .flatMap(
                        scope ->
                                requireThread(scope, threadId)
                                        .map(row -> new MemberThread(row, scope)));
    }

    /** 调用方已按用户顺序加锁；锁内重验不再解析清单或跨文件工作线程。 */
    Mono<PrototypeReviewThreadEntity> lockedThread(
            String versionId, ReviewPageRequest input, MemberThread prepared) {
        return lockedMember(versionId, input, prepared.scope())
                .flatMap(scope -> requireThread(scope, prepared.thread().getId()));
    }

    record MemberThread(PrototypeReviewThreadEntity thread, Scope scope) {
        String actorId() {
            return scope.actorId();
        }
    }

    /** 管理详情沿用页面读取的项目EDIT检查，不因持有线程标识而获得额外权限。 */
    public Mono<ReviewThreadResponse> detailMember(
            String versionId, String threadId, ReviewPageRequest input) {
        return memberPage(versionId, input)
                .flatMap(scope -> requireThread(scope, threadId))
                .map(PrototypeReviewThreadService::response);
    }

    public Mono<PagerResult<ReviewMessageResponse>> messagesVisitor(
            String token, String threadId, ReviewMessageQueryRequest input, String cookie) {
        return visitorPage(token, input, cookie)
                .flatMap(scope -> queryMessages(scope, threadId, input));
    }

    public Mono<PagerResult<ReviewMessageResponse>> messagesMember(
            String versionId, String threadId, ReviewMessageQueryRequest input) {
        return memberPage(versionId, input).flatMap(scope -> queryMessages(scope, threadId, input));
    }

    public Mono<ReviewThreadResponse> create(
            String token, ReviewThreadCreateRequest input, String cookie) {
        return visitorPage(token, input, cookie)
                .flatMap(
                        prepared ->
                                lockedVisitor(token, input, cookie, prepared)
                                        .flatMap(
                                                scope -> {
                                                    var request = threadRequest(scope, input);
                                                    return receipts.replay(
                                                                    request,
                                                                    id ->
                                                                            requireThread(scope, id)
                                                                                    .map(
                                                                                            PrototypeReviewThreadService
                                                                                                    ::response))
                                                            .switchIfEmpty(
                                                                    Mono.defer(
                                                                            () ->
                                                                                    newThread(
                                                                                                    scope,
                                                                                                    input,
                                                                                                    request,
                                                                                                    null)
                                                                                            .map(
                                                                                                    PrototypeReviewImageService
                                                                                                                    .Stored
                                                                                                            ::response)));
                                                })
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/review/thread-create");
    }

    /** 初建图片复用文字线程的回执作用域；首消息持有图片，线程标识不能作为文件归属依据。 */
    public Mono<ReviewThreadResponse> createImage(
            String token, ReviewThreadCreateRequest input, String cookie, FilePart image) {
        return visitorPage(token, input, cookie)
                .flatMap(
                        prepared ->
                                images.upload(
                                        prepared.projectId(),
                                        prepared.versionId(),
                                        image,
                                        candidate ->
                                                lockedVisitor(token, input, cookie, prepared)
                                                        .flatMap(
                                                                scope ->
                                                                        createImage(
                                                                                scope, input,
                                                                                candidate))))
                .checkpoint("prototype-hub/review/thread-create-image");
    }

    private Mono<PrototypeReviewImageService.Stored<ReviewThreadResponse>> createImage(
            Scope scope,
            ReviewThreadCreateRequest input,
            PrototypeReviewImageService.Candidate image) {
        var request = threadRequest(scope, input).withPayload(image.sha256());
        return receipts.replay(
                        request,
                        id ->
                                requireThread(scope, id)
                                        .map(
                                                row ->
                                                        new PrototypeReviewImageService.Stored<>(
                                                                response(row), null)))
                .switchIfEmpty(Mono.defer(() -> newThread(scope, input, request, image)));
    }

    public Mono<ReviewMessageResponse> replyVisitor(
            String token, String threadId, ReviewTextRequest input, String cookie) {
        return visitorPage(token, input, cookie)
                .flatMap(
                        prepared ->
                                lockedVisitor(token, input, cookie, prepared)
                                        .flatMap(scope -> append(scope, threadId, input, "public"))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/review/visitor-message");
    }

    public Mono<ReviewMessageResponse> replyMember(
            String versionId, String threadId, ReviewMemberTextRequest input) {
        return memberPage(versionId, input)
                .flatMap(
                        prepared ->
                                lockedMember(versionId, input, prepared)
                                        .flatMap(
                                                scope ->
                                                        append(
                                                                scope,
                                                                threadId,
                                                                input,
                                                                input.getVisibility()))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/review/member-message");
    }

    /** 先完成页面和线程授权，再接收图片；锁内再次授权，文件归属等待数据库最终结果。 */
    public Mono<ReviewMessageResponse> imageVisitor(
            String token, String threadId, ReviewTextRequest input, String cookie, FilePart image) {
        return visitorPage(token, input, cookie)
                .flatMap(prepared -> requireThread(prepared, threadId).thenReturn(prepared))
                .flatMap(
                        prepared ->
                                images.upload(
                                        prepared.projectId(),
                                        prepared.versionId(),
                                        image,
                                        candidate ->
                                                lockedVisitor(token, input, cookie, prepared)
                                                        .flatMap(
                                                                scope ->
                                                                        append(
                                                                                        scope,
                                                                                        threadId,
                                                                                        input,
                                                                                        "public",
                                                                                        candidate)
                                                                                .map(
                                                                                        result ->
                                                                                                new PrototypeReviewImageService
                                                                                                        .Stored<>(
                                                                                                        result,
                                                                                                        result
                                                                                                                .id())))));
    }

    public Mono<ReviewMessageResponse> imageMember(
            String versionId, String threadId, ReviewMemberTextRequest input, FilePart image) {
        return memberPage(versionId, input)
                .flatMap(prepared -> requireThread(prepared, threadId).thenReturn(prepared))
                .flatMap(
                        prepared ->
                                images.upload(
                                        prepared.projectId(),
                                        versionId,
                                        image,
                                        candidate ->
                                                lockedMember(versionId, input, prepared)
                                                        .flatMap(
                                                                scope ->
                                                                        append(
                                                                                        scope,
                                                                                        threadId,
                                                                                        input,
                                                                                        input
                                                                                                .getVisibility(),
                                                                                        candidate)
                                                                                .map(
                                                                                        result ->
                                                                                                new PrototypeReviewImageService
                                                                                                        .Stored<>(
                                                                                                        result,
                                                                                                        result
                                                                                                                .id())))));
    }

    public Mono<ResponseEntity<Resource>> imageVisitor(
            String token,
            String threadId,
            String messageId,
            ReviewPageRequest input,
            String cookie) {
        return visitorPage(token, input, cookie)
                .flatMap(scope -> image(scope, threadId, messageId));
    }

    public Mono<ResponseEntity<Resource>> imageMember(
            String versionId, String threadId, String messageId, ReviewPageRequest input) {
        return memberPage(versionId, input).flatMap(scope -> image(scope, threadId, messageId));
    }

    private Mono<ResponseEntity<Resource>> image(Scope scope, String threadId, String messageId) {
        return requireThread(scope, threadId)
                .then(
                        Mono.defer(
                                () -> {
                                    var query =
                                            messages.createQuery()
                                                    .where(
                                                            PrototypeReviewMessageEntity::getId,
                                                            messageId)
                                                    .and(
                                                            PrototypeReviewMessageEntity
                                                                    ::getThreadId,
                                                            threadId)
                                                    .and(
                                                            PrototypeReviewMessageEntity
                                                                    ::getProjectId,
                                                            scope.projectId())
                                                    .and(
                                                            PrototypeReviewMessageEntity
                                                                    ::getVersionId,
                                                            scope.versionId())
                                                    .and(
                                                            PrototypeReviewMessageEntity
                                                                    ::getRevisionId,
                                                            scope.revisionId());
                                    if (scope.visitor())
                                        query.and(
                                                PrototypeReviewMessageEntity::getVisibility,
                                                "public");
                                    return query.fetch().singleOrEmpty();
                                }))
                .switchIfEmpty(Mono.error(PrototypeReviewImageService::unavailable))
                .flatMap(images::read);
    }

    public Mono<ReviewThreadResponse> stateVisitor(
            String token, String threadId, ReviewThreadStateRequest input, String cookie) {
        if (!"open".equals(input.getState()))
            return Mono.error(new ServerWebInputException("Invalid visitor state"));
        return visitorPage(token, input, cookie)
                .flatMap(
                        prepared ->
                                lockedVisitor(token, input, cookie, prepared)
                                        .flatMap(scope -> state(scope, threadId, input))
                                        .as(transactions::transactional));
    }

    public Mono<ReviewThreadResponse> stateMember(
            String versionId, String threadId, ReviewThreadStateRequest input) {
        return memberPage(versionId, input)
                .flatMap(
                        prepared ->
                                lockedMember(versionId, input, prepared)
                                        .flatMap(scope -> state(scope, threadId, input))
                                        .as(transactions::transactional));
    }

    private Mono<Scope> visitorPage(String token, ReviewPageRequest input, String cookie) {
        return review.requireVisitor(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .flatMap(
                        grant ->
                                page(
                                                grant.share().getProjectId(),
                                                grant.share().getVersionId(),
                                                input)
                                        .flatMap(
                                                page ->
                                                        review.requireVisitor(
                                                                        token,
                                                                        input.getRevisionId(),
                                                                        input.getAccessKey(),
                                                                        cookie)
                                                                .map(
                                                                        current ->
                                                                                visitor(
                                                                                        current,
                                                                                        input,
                                                                                        page))));
    }

    private Mono<Scope> memberPage(String versionId, ReviewPageRequest input) {
        return access.requireVersion(versionId, PrototypeProjectAccessService.Action.EDIT)
                .flatMap(version -> page(version.getProjectId(), versionId, input))
                .flatMap(page -> member(versionId, input, page));
    }

    private Mono<Scope> member(String versionId, ReviewPageRequest input, PrototypePageInfo page) {
        return access.current()
                .flatMap(
                        actor ->
                                access.requireVersion(
                                                actor,
                                                versionId,
                                                PrototypeProjectAccessService.Action.EDIT,
                                                true)
                                        .map(
                                                version ->
                                                        new Scope(
                                                                version.getProjectId(),
                                                                versionId,
                                                                input.getRevisionId(),
                                                                null,
                                                                page,
                                                                "member",
                                                                actor.userId(),
                                                                actor.authentication()
                                                                        .getUser()
                                                                        .getName(),
                                                                null,
                                                                null)));
    }

    private static Scope visitor(
            PrototypeReviewService.VisitorScope grant,
            ReviewPageRequest input,
            PrototypePageInfo page) {
        var identity = grant.identity();
        return new Scope(
                grant.share().getProjectId(),
                grant.share().getVersionId(),
                input.getRevisionId(),
                grant.share().getId(),
                page,
                "guest",
                identity.guestId(),
                identity.displayName(),
                identity.company(),
                identity.verificationLevel());
    }

    private Mono<Scope> lockedVisitor(
            String token, ReviewPageRequest input, String cookie, Scope prepared) {
        return review.lockedVisitor(token, input.getRevisionId(), input.getAccessKey(), cookie)
                .map(grant -> visitor(grant, input, prepared.page()))
                .filter(current -> sameScope(prepared, current))
                .switchIfEmpty(Mono.error(PrototypeReviewThreadService::unavailable));
    }

    private Mono<Scope> lockedMember(String versionId, ReviewPageRequest input, Scope prepared) {
        return access.lockProject(prepared.projectId())
                .then(access.lockVersion(versionId))
                .then(member(versionId, input, prepared.page()))
                .flatMap(
                        current ->
                                revisions
                                        .createQuery()
                                        .select(PrototypeRevisionEntity::getId)
                                        .where(PrototypeRevisionEntity::getId, current.revisionId())
                                        .and(
                                                PrototypeRevisionEntity::getProjectId,
                                                current.projectId())
                                        .and(
                                                PrototypeRevisionEntity::getVersionId,
                                                current.versionId())
                                        .fetch()
                                        .singleOrEmpty()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        PrototypeReviewThreadService::pageInvalid))
                                        .thenReturn(current))
                .filter(current -> sameScope(prepared, current))
                .switchIfEmpty(Mono.error(PrototypeReviewThreadService::unavailable));
    }

    private static boolean sameScope(Scope before, Scope current) {
        return before.projectId().equals(current.projectId())
                && before.versionId().equals(current.versionId())
                && before.revisionId().equals(current.revisionId())
                && Objects.equals(before.shareId(), current.shareId());
    }

    /** 不可变清单在锁外解析；正常完成数据库Flux后才进入专用工作线程。 */
    private Mono<PrototypePageInfo> page(
            String projectId, String versionId, ReviewPageRequest input) {
        return revisions
                .createQuery()
                .select(PrototypeRevisionEntity::getManifestJson)
                .where(PrototypeRevisionEntity::getId, input.getRevisionId())
                .and(PrototypeRevisionEntity::getProjectId, projectId)
                .and(PrototypeRevisionEntity::getVersionId, versionId)
                .fetch()
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(PrototypeReviewThreadService::pageInvalid))
                .flatMap(
                        row ->
                                work.call(
                                        () -> {
                                            var matches = new ArrayList<PrototypePageInfo>();
                                            matchPage(
                                                    PrototypeManifestBuilder.parse(
                                                                    row.getManifestJson())
                                                            .pages(),
                                                    input.getPageId(),
                                                    matches);
                                            if (matches.size() != 1) throw pageInvalid();
                                            return matches.getFirst();
                                        }));
    }

    private static void matchPage(
            List<PrototypePageInfo> pages, String id, List<PrototypePageInfo> matches) {
        for (var page : pages) {
            if (!page.folder() && id.equals(page.pageId())) matches.add(page);
            matchPage(page.children(), id, matches);
        }
    }

    private Mono<PagerResult<ReviewThreadResponse>> queryThreads(
            Scope scope, ReviewThreadQueryRequest input) {
        QueryParamEntity query =
                QueryParamEntity.newQuery()
                        .where(PrototypeReviewThreadEntity::getProjectId, scope.projectId())
                        .and(PrototypeReviewThreadEntity::getVersionId, scope.versionId())
                        .and(PrototypeReviewThreadEntity::getRevisionId, scope.revisionId())
                        .and(PrototypeReviewThreadEntity::getPageId, scope.page().pageId())
                        .orderByDesc(PrototypeReviewThreadEntity::getPublicUpdatedAt)
                        .orderByAsc(PrototypeReviewThreadEntity::getId)
                        .getParam();
        if (scope.visitor()) {
            query.toQuery().and(PrototypeReviewThreadEntity::getShareId, scope.shareId());
            query.toQuery().and(PrototypeReviewThreadEntity::getGuestId, scope.actorId());
            query.toQuery().and(PrototypeReviewThreadEntity::getVerificationLevel, scope.level());
        }
        if (input.getState() != null)
            query.toQuery().and(PrototypeReviewThreadEntity::getState, input.getState());
        query.doPaging(input.getPageIndex(), input.getPageSize());
        query.setParallelPager(true);
        return QueryHelper.queryPager(
                query, threads::createQuery, PrototypeReviewThreadService::response);
    }

    private Mono<PrototypeReviewThreadEntity> requireThread(Scope scope, String id) {
        var query =
                threads.createQuery()
                        .where(PrototypeReviewThreadEntity::getId, id)
                        .and(PrototypeReviewThreadEntity::getProjectId, scope.projectId())
                        .and(PrototypeReviewThreadEntity::getVersionId, scope.versionId())
                        .and(PrototypeReviewThreadEntity::getRevisionId, scope.revisionId())
                        .and(PrototypeReviewThreadEntity::getPageId, scope.page().pageId());
        if (scope.visitor())
            query.and(PrototypeReviewThreadEntity::getShareId, scope.shareId())
                    .and(PrototypeReviewThreadEntity::getGuestId, scope.actorId())
                    .and(PrototypeReviewThreadEntity::getVerificationLevel, scope.level());
        return query.fetch()
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(PrototypeReviewThreadService::unavailable));
    }

    private Mono<PagerResult<ReviewMessageResponse>> queryMessages(
            Scope scope, String id, ReviewMessageQueryRequest input) {
        return requireThread(scope, id)
                .flatMap(
                        thread -> {
                            QueryParamEntity query =
                                    QueryParamEntity.newQuery()
                                            .where(PrototypeReviewMessageEntity::getThreadId, id)
                                            .and(
                                                    PrototypeReviewMessageEntity::getProjectId,
                                                    scope.projectId())
                                            .and(
                                                    PrototypeReviewMessageEntity::getVersionId,
                                                    scope.versionId())
                                            .and(
                                                    PrototypeReviewMessageEntity::getRevisionId,
                                                    scope.revisionId())
                                            .orderByAsc(PrototypeReviewMessageEntity::getCreateTime)
                                            .orderByAsc(PrototypeReviewMessageEntity::getId)
                                            .getParam();
                            if (scope.visitor())
                                query.toQuery()
                                        .and(PrototypeReviewMessageEntity::getVisibility, "public");
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            return QueryHelper.queryPager(
                                    query,
                                    messages::createQuery,
                                    PrototypeReviewThreadService::response);
                        });
    }

    private PrototypeCreationReceiptService.Request request(
            Scope scope, String operation, String key, String... semanticFields) {
        List<String> fields =
                new ArrayList<>(
                        List.of(
                                scope.projectId(),
                                scope.versionId(),
                                scope.revisionId(),
                                scope.page().pageId()));
        fields.add(scope.shareId());
        fields.addAll(java.util.Arrays.asList(semanticFields));
        return receipts.request(
                scope.actorType() + ":" + scope.level() + ":" + scope.actorId(),
                "review-" + operation,
                receipts.request(
                                "review",
                                "scope",
                                "scope",
                                "scope",
                                scope.projectId(),
                                scope.versionId(),
                                scope.revisionId(),
                                scope.shareId(),
                                scope.page().pageId(),
                                "thread".equals(operation) ? null : semanticFields[0])
                        .fingerprint(),
                key,
                fields.toArray(String[]::new));
    }

    /** 无锚点沿用已发布指纹；非空位置先于图片摘要并入，不受JSON字段顺序影响。 */
    private PrototypeCreationReceiptService.Request threadRequest(
            Scope scope, ReviewThreadCreateRequest input) {
        var request = request(scope, "thread", input.getClientRequestId(), input.getText());
        return input.getAnchor() == null
                ? request
                : request.withPayload(input.getAnchor().canonical());
    }

    private Mono<PrototypeReviewImageService.Stored<ReviewThreadResponse>> newThread(
            Scope scope,
            ReviewThreadCreateRequest input,
            PrototypeCreationReceiptService.Request request,
            PrototypeReviewImageService.Candidate image) {
        String text = input.getText();
        long now = System.currentTimeMillis();
        var row = new PrototypeReviewThreadEntity();
        row.setId(PrototypeReviewKeys.random());
        row.setProjectId(scope.projectId());
        row.setVersionId(scope.versionId());
        row.setRevisionId(scope.revisionId());
        row.setShareId(scope.shareId());
        row.setPageId(scope.page().pageId());
        row.setPageName(scope.page().name());
        row.setPagePath(scope.page().path());
        row.setAnchor(input.getAnchor());
        row.setGuestId(scope.actorId());
        row.setVerificationLevel(scope.level());
        row.setAuthorName(scope.name());
        row.setAuthorCompany(scope.company());
        int end = Math.min(200, text.length());
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        row.setSummary(text.substring(0, end));
        row.setState("open");
        row.setConfigVersion(0L);
        row.setCreateTime(now);
        row.setPublicUpdatedAt(now);
        var message = message(scope, row.getId(), text, "public", now);
        if (image != null) {
            message.setId(image.id());
            image.apply(message);
        }
        return useRate(scope, now)
                .then(threads.insert(row))
                .then(messages.insert(message))
                .then(event(scope, row.getId(), message.getId(), "thread.created", now))
                .then(receipts.record(request, row.getId(), scope.revisionId()))
                .thenReturn(
                        new PrototypeReviewImageService.Stored<>(
                                response(row), image == null ? null : message.getId()));
    }

    private Mono<ReviewMessageResponse> append(
            Scope scope, String id, ReviewTextRequest input, String visibility) {
        return append(scope, id, input, visibility, null);
    }

    private Mono<ReviewMessageResponse> append(
            Scope scope,
            String id,
            ReviewTextRequest input,
            String visibility,
            PrototypeReviewImageService.Candidate image) {
        return requireThread(scope, id)
                .flatMap(
                        thread -> {
                            var request =
                                    request(
                                            scope,
                                            "message",
                                            input.getClientRequestId(),
                                            id,
                                            input.getText(),
                                            visibility);
                            if (image != null) request = request.withPayload(image.sha256());
                            var currentRequest = request;
                            return receipts.replay(
                                            request,
                                            resource ->
                                                    messages.createQuery()
                                                            .where(
                                                                    PrototypeReviewMessageEntity
                                                                            ::getId,
                                                                    resource)
                                                            .and(
                                                                    PrototypeReviewMessageEntity
                                                                            ::getThreadId,
                                                                    id)
                                                            .fetch()
                                                            .singleOrEmpty()
                                                            .map(
                                                                    PrototypeReviewThreadService
                                                                            ::response))
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () -> {
                                                        long now = System.currentTimeMillis();
                                                        var message =
                                                                message(
                                                                        scope,
                                                                        id,
                                                                        input.getText(),
                                                                        visibility,
                                                                        now);
                                                        if (image != null) {
                                                            message.setId(image.id());
                                                            image.apply(message);
                                                        }
                                                        boolean reopen =
                                                                scope.visitor()
                                                                        && "resolved"
                                                                                .equals(
                                                                                        thread
                                                                                                .getState());
                                                        Mono<Void> update =
                                                                "internal".equals(visibility)
                                                                        ? Mono.empty()
                                                                        : publicUpdate(
                                                                                thread,
                                                                                reopen
                                                                                        ? "open"
                                                                                        : thread
                                                                                                .getState(),
                                                                                now);
                                                        return (scope.visitor()
                                                                        ? useRate(scope, now)
                                                                        : Mono.<Void>empty())
                                                                .then(messages.insert(message))
                                                                .then(update)
                                                                .then(
                                                                        event(
                                                                                scope,
                                                                                id,
                                                                                message.getId(),
                                                                                "internal"
                                                                                                .equals(
                                                                                                        visibility)
                                                                                        ? "note.created"
                                                                                        : "message.created",
                                                                                now))
                                                                .then(
                                                                        reopen
                                                                                ? event(
                                                                                        scope,
                                                                                        id,
                                                                                        message
                                                                                                .getId(),
                                                                                        "thread.reopened",
                                                                                        now)
                                                                                : Mono.empty())
                                                                .then(
                                                                        receipts.record(
                                                                                currentRequest,
                                                                                message.getId(),
                                                                                scope.revisionId()))
                                                                .thenReturn(response(message));
                                                    }));
                        });
    }

    private Mono<ReviewThreadResponse> state(
            Scope scope, String id, ReviewThreadStateRequest input) {
        return requireThread(scope, id)
                .flatMap(
                        thread -> {
                            var request =
                                    request(
                                            scope,
                                            "state",
                                            input.getClientRequestId(),
                                            id,
                                            input.getState(),
                                            input.getExpectedVersion().toString());
                            return receipts.replay(
                                            request,
                                            resource ->
                                                    requireThread(scope, resource)
                                                            .map(
                                                                    PrototypeReviewThreadService
                                                                            ::response))
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () -> {
                                                        if (thread.getConfigVersion()
                                                                != input.getExpectedVersion()
                                                                        .longValue())
                                                            return Mono.error(conflict());
                                                        Mono<Void> changed = Mono.empty();
                                                        if (!thread.getState()
                                                                .equals(input.getState())) {
                                                            long now = System.currentTimeMillis();
                                                            changed =
                                                                    publicUpdate(
                                                                                    thread,
                                                                                    input
                                                                                            .getState(),
                                                                                    now)
                                                                            .then(
                                                                                    event(
                                                                                            scope,
                                                                                            id,
                                                                                            null,
                                                                                            "open"
                                                                                                            .equals(
                                                                                                                    input
                                                                                                                            .getState())
                                                                                                    ? "thread.reopened"
                                                                                                    : "thread.resolved",
                                                                                            now));
                                                        }
                                                        return (scope.visitor()
                                                                        ? useRate(
                                                                                scope,
                                                                                System
                                                                                        .currentTimeMillis())
                                                                        : Mono.<Void>empty())
                                                                .then(changed)
                                                                .then(
                                                                        receipts.record(
                                                                                request,
                                                                                id,
                                                                                scope.revisionId()))
                                                                .thenReturn(response(thread));
                                                    }));
                        });
    }

    /** 状态版本只跟随状态变化；备注不调用此方法，公开消息可以只更新时间。 */
    private Mono<Void> publicUpdate(PrototypeReviewThreadEntity thread, String state, long now) {
        long previous = thread.getConfigVersion();
        boolean changed = !state.equals(thread.getState());
        if (changed && previous == Long.MAX_VALUE) return Mono.error(conflict());
        thread.setState(state);
        thread.setPublicUpdatedAt(now);
        if (changed) thread.setConfigVersion(previous + 1);
        return threads.createUpdate()
                .set(PrototypeReviewThreadEntity::getState, state)
                .set(PrototypeReviewThreadEntity::getPublicUpdatedAt, now)
                .set(PrototypeReviewThreadEntity::getConfigVersion, thread.getConfigVersion())
                .where(PrototypeReviewThreadEntity::getId, thread.getId())
                .and(PrototypeReviewThreadEntity::getConfigVersion, previous)
                .execute()
                .flatMap(count -> count == 1 ? Mono.empty() : Mono.error(conflict()));
    }

    private static PrototypeReviewMessageEntity message(
            Scope scope, String threadId, String text, String visibility, long now) {
        var row = new PrototypeReviewMessageEntity();
        row.setId(PrototypeReviewKeys.random());
        row.setProjectId(scope.projectId());
        row.setVersionId(scope.versionId());
        row.setRevisionId(scope.revisionId());
        row.setThreadId(threadId);
        row.setText(text);
        row.setAuthorType(scope.actorType());
        row.setAuthorName(scope.name());
        row.setAuthorCompany(scope.company());
        row.setVerificationLevel(scope.level());
        row.setVisibility(visibility);
        row.setCreateTime(now);
        return row;
    }

    private Mono<Void> event(
            Scope scope, String threadId, String messageId, String type, long now) {
        var row = new PrototypeReviewEventEntity();
        row.setId(PrototypeReviewKeys.random());
        row.setProjectId(scope.projectId());
        row.setVersionId(scope.versionId());
        row.setRevisionId(scope.revisionId());
        row.setThreadId(threadId);
        row.setMessageId(messageId);
        row.setEventType(type);
        row.setActorType(scope.actorType());
        row.setCreateTime(now);
        return events.insert(row).then();
    }

    private Mono<Void> useRate(Scope scope, long now) {
        return rate(scope.projectId(), "comment-project", 300, now)
                .then(
                        rate(
                                scope.projectId(),
                                "comment-guest:" + scope.level() + ":" + scope.actorId(),
                                60,
                                now));
    }

    private Mono<Void> rate(String projectId, String domain, int limit, long now) {
        String id = PrototypeReviewKeys.digest("review-rate-v1:" + projectId + ":" + domain);
        return rates.findById(id)
                .defaultIfEmpty(new PrototypeReviewRateEntity())
                .flatMap(
                        row -> {
                            long window = now / HOUR * HOUR;
                            int count =
                                    row.getWindowStart() != null && row.getWindowStart() == window
                                            ? row.getSendCount()
                                            : 0;
                            if (count >= limit)
                                return Mono.error(
                                        new PrototypeHubException(
                                                "error.prototype-hub.review_rate_limited",
                                                "PROTOTYPE_HUB_REVIEW_RATE_LIMITED",
                                                429));
                            row.setId(id);
                            row.setProjectId(projectId);
                            row.setWindowStart(window);
                            row.setSendCount(count + 1);
                            row.setNextAllowedAt(0L);
                            row.setExpiresAt(window + HOUR);
                            return rates.save(row).then();
                        });
    }

    public Mono<Void> cleanupEvents() {
        return Mono.defer(
                () -> {
                    long cutoff = System.currentTimeMillis() - EVENT_TTL;
                    return events.createQuery()
                            .select(PrototypeReviewEventEntity::getId)
                            .lte(PrototypeReviewEventEntity::getCreateTime, cutoff)
                            .orderBy(
                                    SortOrder.asc(PrototypeReviewEventEntity::getCreateTime),
                                    SortOrder.asc(PrototypeReviewEventEntity::getId))
                            .paging(0, 50)
                            .fetch()
                            .map(PrototypeReviewEventEntity::getId)
                            .collectList()
                            .flatMap(
                                    ids ->
                                            ids.isEmpty()
                                                    ? Mono.empty()
                                                    : events.createDelete()
                                                            .in(
                                                                    PrototypeReviewEventEntity
                                                                            ::getId,
                                                                    ids)
                                                            .lte(
                                                                    PrototypeReviewEventEntity
                                                                            ::getCreateTime,
                                                                    cutoff)
                                                            .execute()
                                                            .then());
                });
    }

    public Mono<Void> purgeVersion(String id) {
        return purge("versionId", id);
    }

    public Mono<Void> purgeProject(String id) {
        return purge("projectId", id);
    }

    /** 仅永久清理时分批删除回执与资源；不可在访客/session过期时调用。 */
    private Mono<Void> purge(String field, String id) {
        return deleteBatches(
                        () ->
                                messages.createQuery()
                                        .select(PrototypeReviewMessageEntity::getId)
                                        .where(field, id)
                                        .paging(0, 50)
                                        .fetch()
                                        .map(PrototypeReviewMessageEntity::getId)
                                        .collectList(),
                        ids ->
                                messages.createQuery()
                                        .in(PrototypeReviewMessageEntity::getId, ids)
                                        .fetch()
                                        .collectList()
                                        .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                                        .filter(row -> row.getImagePath() != null)
                                        .concatMap(
                                                row ->
                                                        cleanup.enqueueReviewImage(
                                                                row.getProjectId(),
                                                                row.getVersionId(),
                                                                row.getImagePath()),
                                                1)
                                        .then(
                                                messages.createDelete()
                                                        .in(
                                                                PrototypeReviewMessageEntity::getId,
                                                                ids)
                                                        .execute())
                                        .then())
                .then(
                        deleteBatches(
                                () ->
                                        threads.createQuery()
                                                .select(PrototypeReviewThreadEntity::getId)
                                                .where(field, id)
                                                .paging(0, 50)
                                                .fetch()
                                                .map(PrototypeReviewThreadEntity::getId)
                                                .collectList(),
                                ids ->
                                        threads.createDelete()
                                                .in(PrototypeReviewThreadEntity::getId, ids)
                                                .execute()
                                                .then()))
                .then(events.createDelete().where(field, id).execute())
                .then();
    }

    private Mono<Void> deleteBatches(
            Supplier<Mono<List<String>>> select,
            java.util.function.Function<List<String>, Mono<Void>> delete) {
        return Mono.defer(select)
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(false)
                                        : receiptRows
                                                .createDelete()
                                                .in(
                                                        PrototypeCreationReceiptEntity
                                                                ::getResourceId,
                                                        ids)
                                                .execute()
                                                .then(delete.apply(ids))
                                                .thenReturn(true))
                .repeat()
                .takeUntil(more -> !more)
                .then();
    }

    private static ReviewThreadResponse response(PrototypeReviewThreadEntity row) {
        return new ReviewThreadResponse(
                row.getId(),
                row.getRevisionId(),
                row.getPageId(),
                row.getPageName(),
                row.getPagePath(),
                row.getSummary(),
                row.getAuthorName(),
                row.getAuthorCompany(),
                row.getVerificationLevel(),
                row.getState(),
                row.getConfigVersion(),
                row.getCreateTime(),
                row.getPublicUpdatedAt(),
                row.getAnchor());
    }

    private static ReviewMessageResponse response(PrototypeReviewMessageEntity row) {
        return new ReviewMessageResponse(
                row.getId(),
                row.getThreadId(),
                row.getText(),
                row.getAuthorType(),
                row.getAuthorName(),
                row.getAuthorCompany(),
                row.getVerificationLevel(),
                row.getVisibility(),
                row.getCreateTime(),
                PrototypeReviewImageService.metadata(row));
    }

    public static PrototypeHubException commentsDisabled() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.review_comments_disabled",
                "PROTOTYPE_HUB_REVIEW_COMMENTS_DISABLED");
    }

    public static PrototypeHubException sessionRequired() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.review_session_required",
                "PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED");
    }

    private static PrototypeHubException unavailable() {
        return new PrototypeHubException(
                "error.prototype-hub.review_thread_unavailable",
                "PROTOTYPE_HUB_REVIEW_THREAD_UNAVAILABLE",
                404);
    }

    private static PrototypeHubException pageInvalid() {
        return new PrototypeHubException(
                "error.prototype-hub.review_page_invalid",
                "PROTOTYPE_HUB_REVIEW_PAGE_INVALID",
                404);
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.review_thread_conflict",
                "PROTOTYPE_HUB_REVIEW_THREAD_CONFLICT",
                409);
    }

    private record Scope(
            String projectId,
            String versionId,
            String revisionId,
            String shareId,
            PrototypePageInfo page,
            String actorType,
            String actorId,
            String name,
            String company,
            String level) {
        boolean visitor() {
            return "guest".equals(actorType);
        }
    }
}
