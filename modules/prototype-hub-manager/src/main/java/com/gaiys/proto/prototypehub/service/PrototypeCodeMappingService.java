package com.gaiys.proto.prototypehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeComponentCodeMappingEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.request.CodeMappingRequests;
import com.gaiys.proto.prototypehub.web.response.CodeMappingResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 固定页人工代码卡的唯一写入边界；锁内重验当前成员，CAS与父回执同事务。代码和路径始终是文本。
 * 沿HTTP/SQL既有追踪加阶段checkpoint，不记录片段/路径；没有常驻队列、缓存或新MBean。
 */
@Service
@RequiredArgsConstructor
public class PrototypeCodeMappingService {
    static final long MAX_VERSION = 9007199254740991L;
    static final String FORMAT = "prototype-hub-code-mapping-cards";
    private final ReactiveRepository<PrototypeComponentCodeMappingEntity, String> cards;
    private final ReactiveRepository<PrototypeElementDescriptionEntity, String> descriptions;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;
    private final ObjectMapper json;

    public Mono<PagerResult<CodeMappingResponse.Card>> query(
            String version, String revision, String page, CodeMappingRequests.Query input) {
        return prepare(version, revision, page, Action.READ)
                .flatMap(
                        scope -> {
                            var query = scopeQuery(scope);
                            if (input.getKeyword() != null && !input.getKeyword().isEmpty())
                                query.toQuery()
                                        .and(
                                                PrototypeComponentCodeMappingEntity::getAlias,
                                                PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS
                                                        .getTermType(),
                                                input.getKeyword());
                            query.toQuery()
                                    .orderByDesc(PrototypeComponentCodeMappingEntity::getCreatedAt)
                                    .orderByAsc(PrototypeComponentCodeMappingEntity::getId);
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            // 范围与字面筛选同时用于COUNT和LIMIT；只批量装配当前有界页的说明状态。
                            return QueryHelper.transformPageResult(
                                    QueryHelper.queryPager(query, cards::createQuery, row -> row),
                                    rows -> responses(scope, rows));
                        })
                .checkpoint("prototype-hub/code-mapping/query");
    }

    public Mono<CodeMappingResponse.Card> detail(
            String version, String revision, String page, String id) {
        checkId(id);
        return prepare(version, revision, page, Action.READ)
                .flatMap(scope -> require(scope, id).flatMap(row -> response(scope, row)));
    }

    public Mono<CodeMappingResponse.Card> create(
            String version, String revision, String page, CodeMappingRequests.Create input) {
        validateLinks(input);
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            var request =
                                                    request(
                                                            scope,
                                                            "create",
                                                            input.getClientRequestId(),
                                                            fields(input));
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(scope, request.id())
                                                                            .flatMap(
                                                                                    row ->
                                                                                            response(
                                                                                                    scope,
                                                                                                    row)))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            loadDescriptions(
                                                                                            scope,
                                                                                            List.of(
                                                                                                    input))
                                                                                    .flatMap(
                                                                                            live -> {
                                                                                                validateAttach(
                                                                                                        null,
                                                                                                        input
                                                                                                                .getDescription(),
                                                                                                        live);
                                                                                                var
                                                                                                        row =
                                                                                                                row(
                                                                                                                        scope,
                                                                                                                        request
                                                                                                                                .id(),
                                                                                                                        input);
                                                                                                return cards.insert(
                                                                                                                row)
                                                                                                        .then(
                                                                                                                record(
                                                                                                                        scope,
                                                                                                                        request))
                                                                                                        .thenReturn(
                                                                                                                CodeMappingResponse
                                                                                                                        .Card
                                                                                                                        .of(
                                                                                                                                row,
                                                                                                                                resolve(
                                                                                                                                        row,
                                                                                                                                        live)));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/code-mapping/create");
    }

    public Mono<CodeMappingResponse.Card> update(
            String version,
            String revision,
            String page,
            String id,
            CodeMappingRequests.Update input) {
        checkId(id);
        validateLinks(input);
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            var values =
                                                    new ArrayList<String>(
                                                            Arrays.asList(
                                                                    id,
                                                                    input.getExpectedVersion()
                                                                            .toString()));
                                            values.addAll(Arrays.asList(fields(input)));
                                            var request =
                                                    request(
                                                            scope,
                                                            "update",
                                                            input.getClientRequestId(),
                                                            values.toArray(String[]::new));
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(scope, id)
                                                                            .flatMap(
                                                                                    row ->
                                                                                            response(
                                                                                                    scope,
                                                                                                    row)))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(scope, id)
                                                                                    .flatMap(
                                                                                            row ->
                                                                                                    update(
                                                                                                            scope,
                                                                                                            row,
                                                                                                            input,
                                                                                                            request))));
                                        }))
                .checkpoint("prototype-hub/code-mapping/update");
    }

    private Mono<CodeMappingResponse.Card> update(
            Scope scope,
            PrototypeComponentCodeMappingEntity row,
            CodeMappingRequests.Update input,
            Request request) {
        checkVersion(row.getConfigVersion(), input.getExpectedVersion());
        long next = nextVersion(row.getConfigVersion());
        return loadDescriptions(scope, List.of(input))
                .flatMap(
                        live -> {
                            validateAttach(row, input.getDescription(), live);
                            long now = System.currentTimeMillis();
                            var update =
                                    cards.createUpdate()
                                            .set(
                                                    PrototypeComponentCodeMappingEntity::getAlias,
                                                    input.getAlias())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getRepositoryPath,
                                                    input.getRepositoryPath())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getRepositoryUrl,
                                                    input.getRepositoryUrl())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getDocumentationUrl,
                                                    input.getDocumentationUrl())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity::getSnippet,
                                                    input.getSnippet())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getFramework,
                                                    input.getFramework())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getFrameworkVersion,
                                                    input.getFrameworkVersion())
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getConfigVersion,
                                                    next)
                                            .set(
                                                    PrototypeComponentCodeMappingEntity
                                                            ::getUpdatedAt,
                                                    now);
                            if (input.getDescription() == null) {
                                update.setNull(
                                        PrototypeComponentCodeMappingEntity::getDescriptionId);
                                update.setNull(
                                        PrototypeComponentCodeMappingEntity::getDescriptionVersion);
                            } else {
                                update.set(
                                        PrototypeComponentCodeMappingEntity::getDescriptionId,
                                        input.getDescription().getId());
                                update.set(
                                        PrototypeComponentCodeMappingEntity::getDescriptionVersion,
                                        input.getDescription().getExpectedVersion());
                            }
                            return update.where(
                                            PrototypeComponentCodeMappingEntity::getId, row.getId())
                                    .and(
                                            PrototypeComponentCodeMappingEntity::getConfigVersion,
                                            input.getExpectedVersion())
                                    .execute()
                                    .flatMap(PrototypeCodeMappingService::requireChanged)
                                    .then(record(scope, request))
                                    .then(
                                            Mono.fromSupplier(
                                                    () -> {
                                                        copy(input, row);
                                                        row.setConfigVersion(next);
                                                        row.setUpdatedAt(now);
                                                        return CodeMappingResponse.Card.of(
                                                                row, resolve(row, live));
                                                    }));
                        });
    }

    public Mono<Boolean> delete(
            String version,
            String revision,
            String page,
            String id,
            CodeMappingRequests.Delete input) {
        checkId(id);
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            var request =
                                                    request(
                                                            scope,
                                                            "delete",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion().toString());
                                            return receipts.replay(
                                                            request, ignored -> Mono.just(true))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(scope, id)
                                                                                    .flatMap(
                                                                                            row -> {
                                                                                                checkVersion(
                                                                                                        row
                                                                                                                .getConfigVersion(),
                                                                                                        input
                                                                                                                .getExpectedVersion());
                                                                                                return cards.createDelete()
                                                                                                        .where(
                                                                                                                PrototypeComponentCodeMappingEntity
                                                                                                                        ::getId,
                                                                                                                id)
                                                                                                        .and(
                                                                                                                PrototypeComponentCodeMappingEntity
                                                                                                                        ::getConfigVersion,
                                                                                                                input
                                                                                                                        .getExpectedVersion())
                                                                                                        .execute()
                                                                                                        .flatMap(
                                                                                                                PrototypeCodeMappingService
                                                                                                                        ::requireChanged)
                                                                                                        .then(
                                                                                                                record(
                                                                                                                        scope,
                                                                                                                        request))
                                                                                                        .thenReturn(
                                                                                                                true);
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/code-mapping/delete");
    }

    public Mono<byte[]> export(
            String version, String revision, String page, CodeMappingRequests.Export input) {
        if (new HashSet<>(input.getIds()).size() != input.getIds().size())
            return Mono.error(invalid());
        return prepare(version, revision, page, Action.READ)
                .flatMap(
                        scope ->
                                loadCards(scope, input.getIds())
                                        .map(
                                                rows -> {
                                                    var ordered =
                                                            new ArrayList<
                                                                    CodeMappingResponse.Text>();
                                                    for (String id : input.getIds()) {
                                                        var row = rows.get(id);
                                                        if (row == null) throw missing();
                                                        ordered.add(
                                                                CodeMappingResponse.Text.of(row));
                                                    }
                                                    return portableBytes(ordered);
                                                }))
                .checkpoint("prototype-hub/code-mapping/export");
    }

    public Mono<CodeMappingResponse.ImportResult> importCards(
            String version, String revision, String page, CodeMappingRequests.Import input) {
        input.getCards().forEach(PrototypeCodeMappingService::validateLinks);
        // This is the portable content bound; the browser separately bounds original file bytes.
        portableBytes(
                input.getCards().stream().map(PrototypeCodeMappingService::portable).toList());
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            var values = new ArrayList<String>();
                                            values.add(input.getFormat());
                                            values.add(input.getSchemaVersion().toString());
                                            values.add(Integer.toString(input.getCards().size()));
                                            for (int i = 0; i < input.getCards().size(); i++) {
                                                values.add(Integer.toString(i));
                                                values.addAll(
                                                        Arrays.asList(
                                                                fields(input.getCards().get(i))));
                                            }
                                            var request =
                                                    request(
                                                            scope,
                                                            "import",
                                                            input.getClientRequestId(),
                                                            values.toArray(String[]::new));
                                            var ids =
                                                    importIds(
                                                            scope,
                                                            request,
                                                            input.getCards().size());
                                            return receipts.replay(
                                                            request,
                                                            ignored -> importResult(scope, ids))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            importNew(
                                                                                    scope,
                                                                                    request,
                                                                                    ids,
                                                                                    input
                                                                                            .getCards())));
                                        }))
                .checkpoint("prototype-hub/code-mapping/import");
    }

    private Mono<CodeMappingResponse.ImportResult> importNew(
            Scope scope,
            Request request,
            List<String> ids,
            List<CodeMappingRequests.Draft> inputs) {
        return loadDescriptions(scope, inputs)
                .flatMap(
                        live -> {
                            // Validate every association before a batch INSERT; transaction also
                            // rolls back DB failures.
                            inputs.forEach(
                                    input -> validateAttach(null, input.getDescription(), live));
                            var rows = new ArrayList<PrototypeComponentCodeMappingEntity>();
                            for (int i = 0; i < inputs.size(); i++)
                                rows.add(row(scope, ids.get(i), inputs.get(i)));
                            return cards.insert(Flux.fromIterable(rows))
                                    .then(record(scope, request))
                                    .thenReturn(
                                            new CodeMappingResponse.ImportResult(
                                                    rows.stream()
                                                            .map(
                                                                    row ->
                                                                            new CodeMappingResponse
                                                                                    .ImportItem(
                                                                                    row.getId(),
                                                                                    "current",
                                                                                    CodeMappingResponse
                                                                                            .Card
                                                                                            .of(
                                                                                                    row,
                                                                                                    resolve(
                                                                                                            row,
                                                                                                            live))))
                                                            .toList()));
                        });
    }

    private Mono<CodeMappingResponse.ImportResult> importResult(Scope scope, List<String> ids) {
        return loadCards(scope, ids)
                .flatMap(rows -> responses(scope, List.copyOf(rows.values())))
                .map(
                        current -> {
                            var byId = new java.util.HashMap<String, CodeMappingResponse.Card>();
                            current.forEach(card -> byId.put(card.id(), card));
                            // An envelope is returned even when all derived cards are gone, so
                            // replay cannot recreate them.
                            return new CodeMappingResponse.ImportResult(
                                    ids.stream()
                                            .map(
                                                    id -> {
                                                        var card = byId.get(id);
                                                        return new CodeMappingResponse.ImportItem(
                                                                id,
                                                                card == null
                                                                        ? "missing"
                                                                        : "current",
                                                                card);
                                                    })
                                            .toList());
                        });
    }

    private List<String> importIds(Scope scope, Request request, int count) {
        var ids = new ArrayList<String>();
        for (int i = 0; i < count; i++)
            ids.add(
                    receipts.request(
                                    scope.user(),
                                    "code-mapping-import-item",
                                    request.id(),
                                    Integer.toString(i))
                            .id());
        return List.copyOf(ids);
    }

    private Mono<Scope> prepare(String version, String revision, String page, Action action) {
        checkId(page);
        return access.current()
                .flatMap(actor -> authorize(actor, version, revision, action, true))
                .flatMap(
                        fixed ->
                                revisions
                                        .pages(fixed.fixed())
                                        .map(
                                                pages -> {
                                                    if (!PrototypePageTraceService.realPages(pages)
                                                            .contains(page))
                                                        throw PrototypeHubException.missing("page");
                                                    return new Scope(
                                                            fixed.user(), fixed.fixed(), page);
                                                }));
    }

    private Mono<Scope> authorize(
            Actor actor, String version, String revision, Action action, boolean audit) {
        if (!actor.authentication()
                .hasPermission("prototype-hub-version", action == Action.READ ? "query" : "save"))
            return Mono.error(PrototypeProjectAccessService.roleDenied());
        return access.requireVersion(actor, version, action, audit)
                .flatMap(row -> revisions.require(row, revision))
                .map(fixed -> new Scope(actor.userId(), fixed, null));
    }

    private <T> Mono<T> locked(Scope scope, Supplier<Mono<T>> work) {
        // Manifest FileWork is outside locks; immutable ownership and current action are rechecked
        // inside.
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

    private static QueryParamEntity scopeQuery(Scope scope) {
        return QueryParamEntity.newQuery()
                .where(
                        PrototypeComponentCodeMappingEntity::getProjectId,
                        scope.fixed().getProjectId())
                .and(
                        PrototypeComponentCodeMappingEntity::getVersionId,
                        scope.fixed().getVersionId())
                .and(PrototypeComponentCodeMappingEntity::getRevisionId, scope.fixed().getId())
                .and(PrototypeComponentCodeMappingEntity::getPageId, scope.page())
                .getParam();
    }

    private Mono<Map<String, PrototypeComponentCodeMappingEntity>> loadCards(
            Scope scope, List<String> ids) {
        var param = scopeQuery(scope);
        param.toQuery().in(PrototypeComponentCodeMappingEntity::getId, ids);
        param.doPaging(0, 50);
        return cards.createQuery()
                .setParam(param)
                .fetch()
                .collectMap(PrototypeComponentCodeMappingEntity::getId);
    }

    private Mono<PrototypeComponentCodeMappingEntity> find(Scope scope, String id) {
        var query = scopeQuery(scope);
        query.toQuery().and(PrototypeComponentCodeMappingEntity::getId, id);
        return cards.createQuery().setParam(query).fetch().singleOrEmpty();
    }

    private Mono<PrototypeComponentCodeMappingEntity> require(Scope scope, String id) {
        return find(scope, id).switchIfEmpty(Mono.error(PrototypeCodeMappingService::missing));
    }

    private Mono<Map<String, PrototypeElementDescriptionEntity>> loadDescriptions(
            Scope scope, List<? extends CodeMappingRequests.Draft> inputs) {
        return descriptions(
                scope,
                inputs.stream()
                        .map(CodeMappingRequests.Draft::getDescription)
                        .filter(Objects::nonNull)
                        .map(CodeMappingRequests.Description::getId)
                        .distinct()
                        .toList());
    }

    private Mono<Map<String, PrototypeElementDescriptionEntity>> descriptions(
            Scope scope, List<String> ids) {
        if (ids.isEmpty()) return Mono.just(Map.of());
        return descriptions
                .createQuery()
                .where(
                        PrototypeElementDescriptionEntity::getProjectId,
                        scope.fixed().getProjectId())
                .and(PrototypeElementDescriptionEntity::getVersionId, scope.fixed().getVersionId())
                .and(PrototypeElementDescriptionEntity::getRevisionId, scope.fixed().getId())
                .and(PrototypeElementDescriptionEntity::getPageId, scope.page())
                .in(PrototypeElementDescriptionEntity::getId, ids)
                .fetch()
                .collectMap(PrototypeElementDescriptionEntity::getId);
    }

    private Mono<CodeMappingResponse.Card> response(
            Scope scope, PrototypeComponentCodeMappingEntity row) {
        return responses(scope, List.of(row)).map(rows -> rows.get(0));
    }

    private Mono<List<CodeMappingResponse.Card>> responses(
            Scope scope, List<PrototypeComponentCodeMappingEntity> rows) {
        return descriptions(
                        scope,
                        rows.stream()
                                .map(PrototypeComponentCodeMappingEntity::getDescriptionId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .map(
                        live ->
                                rows.stream()
                                        .map(
                                                row ->
                                                        CodeMappingResponse.Card.of(
                                                                row, resolve(row, live)))
                                        .toList());
    }

    static CodeMappingResponse.Description resolve(
            PrototypeComponentCodeMappingEntity row,
            Map<String, PrototypeElementDescriptionEntity> live) {
        if (row.getDescriptionId() == null) return null;
        var current = live.get(row.getDescriptionId());
        return new CodeMappingResponse.Description(
                row.getDescriptionId(),
                row.getDescriptionVersion(),
                current == null
                        ? "missing"
                        : Objects.equals(current.getConfigVersion(), row.getDescriptionVersion())
                                ? "current"
                                : "changed",
                current == null ? null : current.getConfigVersion(),
                current == null ? null : current.getTitle());
    }

    static void validateAttach(
            PrototypeComponentCodeMappingEntity previous,
            CodeMappingRequests.Description ref,
            Map<String, PrototypeElementDescriptionEntity> live) {
        if (ref == null
                || previous != null
                        && Objects.equals(previous.getDescriptionId(), ref.getId())
                        && Objects.equals(
                                previous.getDescriptionVersion(), ref.getExpectedVersion())) return;
        var current = live.get(ref.getId());
        if (current == null) throw PrototypeHubException.missing("code_mapping_description");
        if (!current.getConfigVersion().equals(ref.getExpectedVersion()))
            throw new PrototypeHubException(
                    "error.prototype-hub.code_mapping_description_changed",
                    "PROTOTYPE_HUB_CODE_MAPPING_DESCRIPTION_CHANGED",
                    409);
    }

    private Request request(Scope scope, String operation, String key, String... fields) {
        var fixed = scope.fixed();
        var values =
                new ArrayList<String>(
                        List.of(
                                fixed.getProjectId(),
                                fixed.getVersionId(),
                                fixed.getId(),
                                scope.page()));
        values.addAll(Arrays.asList(fields));
        return receipts.request(
                scope.user(),
                "code-mapping-" + operation,
                jsonString(
                        List.of(
                                fixed.getProjectId(),
                                fixed.getVersionId(),
                                fixed.getId(),
                                scope.page())),
                key,
                values.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.fixed().getVersionId(), scope.fixed().getId());
    }

    private String jsonString(Object value) {
        try {
            return json.writer()
                    .without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Code mapping JSON serialization failed", error);
        }
    }

    private byte[] portableBytes(List<CodeMappingResponse.Text> texts) {
        try {
            byte[] bytes =
                    json.writer()
                            .without(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsBytes(new CodeMappingResponse.Package(FORMAT, 1, texts));
            if (bytes.length > 1048576) throw tooLarge();
            return bytes;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Code mapping JSON serialization failed", error);
        }
    }

    private static CodeMappingResponse.Text portable(CodeMappingRequests.Draft input) {
        return new CodeMappingResponse.Text(
                input.getAlias(),
                input.getRepositoryPath(),
                input.getRepositoryUrl(),
                input.getDocumentationUrl(),
                input.getSnippet(),
                input.getFramework(),
                input.getFrameworkVersion());
    }

    private static String[] fields(CodeMappingRequests.Draft input) {
        var ref = input.getDescription();
        return new String[] {
            input.getAlias(),
            input.getRepositoryPath(),
            input.getRepositoryUrl(),
            input.getDocumentationUrl(),
            input.getSnippet(),
            input.getFramework(),
            input.getFrameworkVersion(),
            ref == null ? null : ref.getId(),
            ref == null ? null : ref.getExpectedVersion().toString()
        };
    }

    private static PrototypeComponentCodeMappingEntity row(
            Scope scope, String id, CodeMappingRequests.Draft input) {
        var row = new PrototypeComponentCodeMappingEntity();
        row.setId(id);
        row.setProjectId(scope.fixed().getProjectId());
        row.setVersionId(scope.fixed().getVersionId());
        row.setRevisionId(scope.fixed().getId());
        row.setPageId(scope.page());
        copy(input, row);
        row.setConfigVersion(0L);
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static void copy(
            CodeMappingRequests.Draft input, PrototypeComponentCodeMappingEntity row) {
        row.setAlias(input.getAlias());
        row.setRepositoryPath(input.getRepositoryPath());
        row.setRepositoryUrl(input.getRepositoryUrl());
        row.setDocumentationUrl(input.getDocumentationUrl());
        row.setSnippet(input.getSnippet());
        row.setFramework(input.getFramework());
        row.setFrameworkVersion(input.getFrameworkVersion());
        row.setDescriptionId(
                input.getDescription() == null ? null : input.getDescription().getId());
        row.setDescriptionVersion(
                input.getDescription() == null
                        ? null
                        : input.getDescription().getExpectedVersion());
    }

    static void validateLinks(CodeMappingRequests.Draft input) {
        validateLink(input.getRepositoryUrl());
        validateLink(input.getDocumentationUrl());
    }

    static void validateLink(String value) {
        if (value.isEmpty()) return;
        try {
            var uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")
                    || uri.getRawAuthority().contains("@")
                    || value.codePoints()
                            .anyMatch(
                                    c ->
                                            Character.isISOControl(c)
                                                    || Character.isWhitespace(c)
                                                    || Character.isSpaceChar(c)
                                                    || c == 0xFEFF
                                                    || c == '\\')) throw invalid();
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static void checkId(String id) {
        if (id == null || id.length() > 128 || id.isBlank() || id.indexOf(0) >= 0) throw invalid();
    }

    private static void checkVersion(long current, long expected) {
        if (current != expected) throw conflict();
    }

    private static long nextVersion(long current) {
        if (current >= MAX_VERSION) throw conflict();
        return current + 1;
    }

    private static Mono<Void> requireChanged(int count) {
        return count == 1 ? Mono.empty() : Mono.error(conflict());
    }

    private static PrototypeHubException missing() {
        return PrototypeHubException.missing("code_mapping");
    }

    private static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.code_mapping_invalid", "PROTOTYPE_HUB_CODE_MAPPING_INVALID");
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.code_mapping_conflict",
                "PROTOTYPE_HUB_CODE_MAPPING_CONFLICT",
                409);
    }

    public static PrototypeHubException tooLarge() {
        return new PrototypeHubException(
                "error.prototype-hub.code_mapping_too_large",
                "PROTOTYPE_HUB_CODE_MAPPING_TOO_LARGE",
                413);
    }

    private record Scope(String user, PrototypeRevisionEntity fixed, String page) {}
}
