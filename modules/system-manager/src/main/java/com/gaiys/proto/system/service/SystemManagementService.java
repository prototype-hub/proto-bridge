package com.gaiys.proto.system.service;

import static com.gaiys.proto.system.service.support.ManagementSupport.conflict;
import static com.gaiys.proto.system.service.support.ManagementSupport.ids;
import static com.gaiys.proto.system.service.support.ManagementSupport.invalid;
import static com.gaiys.proto.system.service.support.ManagementSupport.limit;
import static com.gaiys.proto.system.service.support.ManagementSupport.missing;
import static com.gaiys.proto.system.service.support.ManagementSupport.normalizeUserTerms;
import static com.gaiys.proto.system.service.support.ManagementSupport.text;
import static com.gaiys.proto.system.service.support.ManagementSupport.typed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.entity.UserDetailEntity;
import com.gaiys.proto.system.enums.UserType;
import com.gaiys.proto.system.web.request.UserProfileRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.supports.json.JsonTermType;
import org.hswebframework.ezorm.rdb.supports.json.JsonValueCondition;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.id.IDGenerator;
import org.hswebframework.web.system.authorization.api.PasswordValidator;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.event.ClearUserAuthorizationCacheEvent;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 系统管理接口的响应式适配服务，组合 hsweb 账号、维度授权与本地用户资料。 控制器负责资源权限检查；账号和授权仍由官方服务维护，查询资料条件在这里映射到用户主键。 */
@Service
@RequiredArgsConstructor
public class SystemManagementService {
    private final DefaultDimensionService dimensions;
    private final DefaultDimensionUserService bindings;
    private final DefaultReactiveUserService users;
    private final UserDetailService details;
    private final RoleGroupService groups;
    private final PositionService positions;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final PasswordValidator passwords;

    public Mono<DimensionEntity> dimension(String type, String id) {
        return dimensions
                .findById(id)
                .filter(d -> type.equals(d.getTypeId()))
                .switchIfEmpty(Mono.error(missing()));
    }

    public Map<String, Object> role(DimensionEntity d) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", d.getId());
        value.put("name", d.getName());
        value.put("description", Objects.toString(d.getDescribe(), ""));
        value.putAll(d.getProperties() == null ? Map.of() : d.getProperties());
        return value;
    }

    @Transactional
    public Mono<Map<String, Object>> saveRole(Map<String, Object> input, boolean creating) {
        String id = text(input, "id");
        if (id.isBlank()) id = IDGenerator.MD5.generate();
        if (!id.matches("[A-Za-z0-9_-]{1,32}")
                || (text(input, "name").isBlank()
                        || text(input, "name").length() > 128
                        || text(input, "description").length() > 1024))
            return Mono.error(invalid());
        String groupId = canonicalRoleGroup(text(input, "groupId"));
        if (input.containsKey("state")
                && !Set.of("enabled", "disabled").contains(text(input, "state")))
            return Mono.error(invalid());
        if (Set.of("ADMIN", "MEMBER").contains(id) && "disabled".equals(input.get("state")))
            return Mono.error(conflict());
        final String key = id;
        return (groupId.isBlank()
                        ? Mono.empty()
                        : groups.findById(groupId).switchIfEmpty(Mono.error(missing())).then())
                .then(dimensions.findById(id))
                .map(
                        old -> {
                            if (creating || !"role".equals(old.getTypeId())) throw conflict();
                            return old;
                        })
                .defaultIfEmpty(new DimensionEntity())
                .flatMap(d -> saveRoleDimension(prepareRoleDimension(d, input, key)));
    }

    private DimensionEntity prepareRoleDimension(
            DimensionEntity dimension, Map<String, Object> input, String id) {
        dimension.setId(id);
        dimension.setTypeId("role");
        dimension.setName(text(input, "name"));
        dimension.setDescribe(text(input, "description"));
        Map<String, Object> properties =
                new LinkedHashMap<>(
                        dimension.getProperties() == null ? Map.of() : dimension.getProperties());
        if (input.containsKey("groupId"))
            properties.put("groupId", canonicalRoleGroup(text(input, "groupId")));
        if (input.containsKey("state")) properties.put("state", input.get("state"));
        if (Objects.toString(properties.get("groupId"), "").isBlank())
            properties.put("groupId", "default");
        properties.putIfAbsent("state", "enabled");
        dimension.setProperties(properties);
        return dimension;
    }

    private Mono<Map<String, Object>> saveRoleDimension(DimensionEntity dimension) {
        // 角色名称同步到既有绑定后再失效授权缓存，整个流程仍属于入口事务。
        return dimensions
                .save(dimension)
                .then(
                        bindings.createUpdate()
                                .set(DimensionUserEntity::getDimensionName, dimension.getName())
                                .where(DimensionUserEntity::getDimensionTypeId, "role")
                                .and(DimensionUserEntity::getDimensionId, dimension.getId())
                                .execute())
                .then(ClearUserAuthorizationCacheEvent.all().publish(events))
                .thenReturn(role(dimension));
    }

    public Mono<PagerResult<Map<String, Object>>> roles(QueryParamEntity query) {
        return Mono.defer(() -> dimensions.queryPager(roleQuery(query)))
                .map(
                        page ->
                                PagerResult.of(
                                        page.getTotal(),
                                        page.getData().stream().map(this::role).toList(),
                                        query));
    }

    public Flux<Map<String, Object>> allRoles(QueryParamEntity query) {
        return Flux.defer(() -> dimensions.query(roleQuery(query).noPaging())).map(this::role);
    }

    private QueryParamEntity roleQuery(QueryParamEntity source) {
        QueryParamEntity query = typed(source, "role");
        query.getSorts()
                .forEach(
                        sort -> {
                            if ("description".equals(sort.getName())) sort.setName("describe");
                        });
        roleTerms(query.getTerms());
        return query;
    }

    /** 旧前端默认分组标识只是别名，不创建或迁移第二个默认分组。 */
    private String canonicalRoleGroup(String value) {
        return "default_group".equals(value) ? "default" : value;
    }

    private void roleTerms(List<Term> terms) {
        for (Term term : terms) {
            String column = term.getColumn();
            if ("description".equals(column)) term.setColumn("describe");
            if (Set.of("groupId", "state").contains(column == null ? "" : column))
                resolveRoleProperty(term, column);
            roleTerms(term.getTerms());
        }
    }

    /** 保留请求的布尔分组，由 EasyORM 的 JSON 条件方言处理属性取值、操作符和绑定参数。 */
    private void resolveRoleProperty(Term term, String property) {
        String operator = term.getTermType();
        if (operator == null
                || !Set.of("eq", "not", "in", "nin", "like", "nlike", "isnull", "notnull")
                        .contains(operator)
                || !term.getOptions().isEmpty()) throw invalid();
        boolean collection = "in".equals(operator) || "nin".equals(operator);
        boolean nullComparison = "isnull".equals(operator) || "notnull".equals(operator);
        Object value = term.getValue();
        if (collection) {
            if (!(value instanceof Collection<?> values) || values.size() > 200) throw invalid();
            List<String> normalized =
                    values.stream().map(item -> rolePropertyValue(property, item)).toList();
            if (normalized.isEmpty()) {
                // EasyORM 会忽略空 IN；显式使用非空主键的反例，避免放宽外层查询。
                if ("in".equals(operator)) {
                    term.setColumn("id");
                    term.setTermType("isnull");
                    term.setValue("");
                    return;
                }
                // 原空 NOT IN 只匹配存在的非空属性，不能替换为恒真条件。
                operator = "notnull";
                value = null;
            } else value = normalized;
        } else if (nullComparison) value = null;
        else value = rolePropertyValue(property, value);
        term.setColumn("properties");
        term.setTermType(JsonTermType.value);
        term.setValue(JsonValueCondition.of(property, operator, value));
    }

    private String rolePropertyValue(String property, Object value) {
        if (!(value instanceof String text) || text.length() > 128) throw invalid();
        return "groupId".equals(property) ? canonicalRoleGroup(text) : text;
    }

    @Transactional
    public Mono<Void> deleteRole(String id) {
        if (List.of("ADMIN", "MEMBER").contains(id)) return Mono.error(conflict());
        return positions
                .createQuery()
                .fetch()
                .any(
                        p ->
                                p.getRoles() != null
                                        && p.getRoles().stream()
                                                .anyMatch(role -> id.equals(role.get("id"))))
                .flatMap(
                        inUse ->
                                inUse
                                        ? Mono.error(conflict())
                                        : dimension("role", id)
                                                .flatMap(d -> dimensions.deleteById(id))
                                                .then());
    }

    @Transactional
    public Mono<Void> bind(String type, String id, List<String> userIds, boolean remove) {
        List<String> keys = ids(userIds);
        if (keys.isEmpty()) return Mono.empty();
        return dimension(type, id)
                .zipWith(users.findById(keys).collectList())
                .flatMap(
                        tuple ->
                                changeBindings(
                                        type, id, keys, tuple.getT1(), tuple.getT2(), remove))
                .then(ClearUserAuthorizationCacheEvent.of(keys).publish(events));
    }

    private Mono<Void> changeBindings(
            String type,
            String id,
            List<String> userIds,
            DimensionEntity dimension,
            List<UserEntity> foundUsers,
            boolean remove) {
        if (foundUsers.size() != userIds.size()) return Mono.error(missing());
        if (remove) {
            return bindings.createDelete()
                    .where(DimensionUserEntity::getDimensionTypeId, type)
                    .and(DimensionUserEntity::getDimensionId, id)
                    .in(DimensionUserEntity::getUserId, userIds)
                    .execute()
                    .then(removeOrganizationPositionBindings(type, id, userIds));
        }
        List<DimensionUserEntity> rows =
                foundUsers.stream()
                        .map(user -> createBinding(type, id, dimension.getName(), user))
                        .toList();
        return bindings.save(rows).then();
    }

    private DimensionUserEntity createBinding(
            String type, String id, String name, UserEntity user) {
        DimensionUserEntity row = new DimensionUserEntity();
        row.setDimensionTypeId(type);
        row.setDimensionId(id);
        row.setDimensionName(name);
        row.setUserId(user.getId());
        row.setUserName(user.getName());
        row.setRelationTime(System.currentTimeMillis());
        row.generateId();
        return row;
    }

    private Mono<Void> removeOrganizationPositionBindings(
            String type, String organizationId, List<String> userIds) {
        if (!"org".equals(type)) return Mono.empty();
        // 先移除组织绑定，再移除该组织职位绑定，避免用户继续通过职位继承组织权限。
        return positions
                .createQuery()
                .where(PositionEntity::getOrgId, organizationId)
                .fetch()
                .map(PositionEntity::getId)
                .collectList()
                .flatMap(positionIds -> removePositionBindings(positionIds, userIds));
    }

    private Mono<Void> removePositionBindings(List<String> positionIds, List<String> userIds) {
        if (positionIds.isEmpty()) return Mono.empty();
        return bindings.createDelete()
                .where(DimensionUserEntity::getDimensionTypeId, "position")
                .in(DimensionUserEntity::getDimensionId, positionIds)
                .in(DimensionUserEntity::getUserId, userIds)
                .execute()
                .then();
    }

    public Mono<Void> replaceBindings(String userId, String type, List<String> targets) {
        List<String> keys = ids(targets);
        return Flux.fromIterable(keys)
                .concatMap(id -> dimension(type, id))
                .collectList()
                .flatMap(
                        valid ->
                                bindings.createDelete()
                                        .where(DimensionUserEntity::getUserId, userId)
                                        .and(DimensionUserEntity::getDimensionTypeId, type)
                                        .execute()
                                        .thenMany(Flux.fromIterable(valid))
                                        .concatMap(
                                                d -> bind(type, d.getId(), List.of(userId), false))
                                        .then());
    }

    @Transactional
    public Mono<Map<String, Object>> saveUser(
            Map<String, Object> input, String id, boolean creating) {
        Map<String, Object> account = userAccountInput(input);
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(text(account, "name"));
        user.setUsername(text(account, "username"));
        if (creating) {
            user.setPassword(text(account, "password"));
            user.setStatus((byte) 1);
            user.setType(UserType.normal.getValue());
        } else if (account.containsKey("password") && !text(account, "password").isBlank())
            return Mono.error(invalid());
        if (user.getName().isBlank()
                || user.getName().length() > 128
                || user.getUsername().length() > 64
                || (creating && user.getUsername().isBlank())) return Mono.error(invalid());
        Mono<UserEntity> saved =
                creating
                        ? users.addUser(user)
                        : users.findById(id)
                                .switchIfEmpty(Mono.error(missing()))
                                .flatMap(
                                        old -> {
                                            user.setUsername(old.getUsername());
                                            return users.saveUser(Mono.just(user))
                                                    .then(users.findById(id));
                                        });
        return saved.flatMap(
                userEntity ->
                        saveUserDetails(userEntity.getId(), account)
                                .then(replaceUserBindings(userEntity.getId(), input))
                                .then(user(userEntity.getId())));
    }

    /** 兼容前端嵌套 user 和已发布的平铺请求；只投影可写账号字段，维度仍从外层读取。 */
    private Map<String, Object> userAccountInput(Map<String, Object> input) {
        Map<?, ?> source = input;
        List<String> fields =
                List.of("name", "username", "password", "telephone", "email", "avatar");
        if (input.containsKey("user")) {
            if (!(input.get("user") instanceof Map<?, ?> nested)) throw invalid();
            if (fields.stream().anyMatch(input::containsKey)) throw invalid();
            source = nested;
        }
        Map<String, Object> account = new LinkedHashMap<>();
        for (String field : fields) {
            if (!source.containsKey(field)) continue;
            Object value = source.get(field);
            if (value != null && !(value instanceof String)) throw invalid();
            account.put(field, value);
        }
        return account;
    }

    /** 当前身份由控制器传入，姓名和资料在同一事务更新；不进入管理用户的维度或状态写入。 */
    @Transactional
    public Mono<Boolean> saveSelfProfile(String id, UserProfileRequest input) {
        UserEntity update = new UserEntity();
        update.setId(id);
        update.setName(input.getName());
        return users.findById(id)
                .switchIfEmpty(Mono.error(missing()))
                .flatMap(old -> users.saveUser(Mono.just(update)))
                .flatMap(saved -> saveUserDetails(id, input.details()).thenReturn(saved));
    }

    private Mono<Void> saveUserDetails(String userId, Map<String, Object> input) {
        UserDetailEntity initial = new UserDetailEntity();
        initial.setId(userId);
        return details.findById(userId)
                .defaultIfEmpty(initial)
                .map(row -> updateUserDetails(row, input))
                .flatMap(details::save)
                .then();
    }

    private UserDetailEntity updateUserDetails(UserDetailEntity row, Map<String, Object> input) {
        for (String field : List.of("telephone", "email", "avatar")) {
            if (!input.containsKey(field)) continue;
            String value = text(input, field);
            int max = field.equals("telephone") ? 64 : field.equals("email") ? 255 : 1024;
            if (value.length() > max) throw invalid();
            if (field.equals("email")
                    && !value.isBlank()
                    && !value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw invalid();
            switch (field) {
                case "telephone" -> row.setTelephone(value);
                case "email" -> row.setEmail(value);
                case "avatar" -> row.setAvatar(value);
                default -> throw invalid();
            }
        }
        return row;
    }

    private Mono<Void> replaceUserBindings(String userId, Map<String, Object> input) {
        // 仅替换请求中出现的维度；未提交的维度保持原绑定，并沿用角色、组织、职位的顺序。
        return (input.containsKey("roleIdList")
                        ? replaceBindings(userId, "role", ids(input.get("roleIdList")))
                        : Mono.<Void>empty())
                .then(
                        input.containsKey("orgIdList")
                                ? replaceBindings(userId, "org", ids(input.get("orgIdList")))
                                : Mono.empty())
                .then(
                        input.containsKey("positions")
                                ? replaceBindings(userId, "position", ids(input.get("positions")))
                                : Mono.empty());
    }

    public Mono<Map<String, Object>> user(String id) {
        return users.findById(id)
                .switchIfEmpty(Mono.error(missing()))
                .flatMap(u -> enrich(List.of(u)).map(rows -> rows.get(0)));
    }

    public Mono<PagerResult<Map<String, Object>>> userPage(QueryParamEntity query) {
        limit(query);
        normalizeContactWrappers(query.getTerms());
        normalizeUserTerms(query.getTerms());
        return resolveUserTerms(query.getTerms())
                .then(Mono.defer(() -> users.queryPager(query)))
                .flatMap(
                        page ->
                                enrich(page.getData())
                                        .map(rows -> PagerResult.of(page.getTotal(), rows, query)));
    }

    /** 把旧 id$user-detail 包装转为原位置的分组，保留外层 AND/OR，再复用联系字段查询。 */
    private void normalizeContactWrappers(List<Term> terms) {
        if (terms == null) throw invalid();
        for (int index = 0; index < terms.size(); index++) {
            Term term = terms.get(index);
            if (term == null) throw invalid();
            if ("id".equals(term.getColumn()) && "user-detail".equals(term.getTermType())) {
                if (!term.getOptions().isEmpty()
                        || !(term.getValue() instanceof Collection<?> values)
                        || values.isEmpty()
                        || values.size() > 200
                        || !term.getTerms().isEmpty()) throw invalid();
                List<Term> predicates =
                        values.stream()
                                .map(value -> mapper.convertValue(value, Term.class))
                                .toList();
                validateContactPredicates(predicates);
                // Term.setColumn(null) 是原生 no-op，因此用公开新 Term 替换叶子，而不是残留 id 比较。
                Term group = new Term();
                group.setType(term.getType());
                group.setTerms(new java.util.ArrayList<>(predicates));
                terms.set(index, group);
                term = group;
            }
            normalizeContactWrappers(term.getTerms());
        }
    }

    private void validateContactPredicates(List<Term> terms) {
        for (Term term : terms) {
            if (term == null || term.getTerms() == null || term.getOptions() == null)
                throw invalid();
            if (term.getTerms().isEmpty()) {
                if (!"telephone".equals(term.getColumn()) && !"email".equals(term.getColumn()))
                    throw invalid();
            } else {
                if (term.getColumn() != null) throw invalid();
                validateContactPredicates(term.getTerms());
            }
        }
    }

    private Mono<Void> resolveUserTerms(List<Term> terms) {
        return Flux.fromIterable(terms)
                .concatMap(
                        term ->
                                resolveUserTerm(term)
                                        .then(Mono.defer(() -> resolveUserTerms(term.getTerms()))))
                .then();
    }

    private Mono<Void> resolveUserTerm(Term term) {
        if ("telephone".equals(term.getColumn()) || "email".equals(term.getColumn()))
            return resolveContactTerm(term);
        if ("id".equals(term.getColumn()) && "in-dimension".equals(term.getTermType()))
            return resolveDimensionUserTerm(term);
        return Mono.empty();
    }

    private Mono<Void> resolveContactTerm(Term term) {
        Term predicate = term.clone();
        predicate.setType(Term.Type.and);
        predicate.getTerms().clear();
        boolean includeMissing = "isnull".equals(predicate.getTermType());
        // 无详情记录等价于联系字段为 NULL；仅 isnull 需要包含这些用户。
        // not/nlike/nin 交给官方详情查询处理，遵循 SQL 比较不会命中 NULL 的语义。
        if (includeMissing) {
            predicate.setTermType("notnull");
            predicate.getOptions().clear();
        }
        // ORM 会跳过 value=null 的叶条件；NULL 操作不消费值，内部占位保留真正的 IS NOT NULL。
        if (Set.of("isnull", "notnull").contains(predicate.getTermType())) predicate.setValue("");
        QueryParamEntity contactQuery = QueryParamEntity.of();
        contactQuery.addTerm(predicate);
        // hsweb 的否定比较默认也包含 NULL；联系字段遵循 SQL 比较语义，显式排除 NULL。
        // 固定条件只作用于单个详情谓词，不改变用户查询外层的 AND/OR 分组。
        if (Set.of("not", "nlike", "nin", "nbtw", "nempty").contains(predicate.getTermType())
                || predicate.getOptions().contains("not"))
            contactQuery.toNestQuery().and(predicate.getColumn(), "notnull", "");
        contactQuery.getIncludes().add("id");
        contactQuery.noPaging();
        return details.query(contactQuery)
                .map(UserDetailEntity::getId)
                .collectList()
                .doOnNext(userIds -> rewriteContactTerm(term, userIds, includeMissing))
                .then();
    }

    private void rewriteContactTerm(Term term, List<String> userIds, boolean exclude) {
        term.setColumn("id");
        term.getOptions().clear();
        // 用户主键非空：空匹配用恒假条件，空补集用恒真条件，避免 ORM 忽略空 IN 后放宽查询。
        term.setTermType(
                userIds.isEmpty() ? (exclude ? "notnull" : "isnull") : (exclude ? "nin" : "in"));
        term.setValue(userIds.isEmpty() ? "" : userIds);
    }

    private Mono<Void> resolveDimensionUserTerm(Term term) {
        List<String> options = term.getOptions();
        if (options.isEmpty() || !Set.of("org", "role", "position").contains(options.get(0)))
            return Mono.error(invalid());
        String dimensionType = options.get(0);
        boolean negate = options.contains("not");
        List<String> targets =
                ids(
                        term.getValue() instanceof Collection<?>
                                ? term.getValue()
                                : List.of(Objects.toString(term.getValue(), "")));
        Mono<List<String>> matched =
                targets.isEmpty()
                        ? Mono.just(List.of())
                        : bindings.createQuery()
                                .where(DimensionUserEntity::getDimensionTypeId, dimensionType)
                                .in(DimensionUserEntity::getDimensionId, targets)
                                .fetch()
                                .map(DimensionUserEntity::getUserId)
                                .distinct()
                                .collectList();
        return matched.doOnNext(
                        userIds -> {
                            term.setTermType(negate ? "nin" : "in");
                            term.getOptions().clear();
                            term.setValue(
                                    userIds.isEmpty() ? List.of("__no_bound_user__") : userIds);
                        })
                .then();
    }

    public Mono<List<Map<String, Object>>> enrich(List<UserEntity> list) {
        if (list.isEmpty()) return Mono.just(List.of());
        List<String> keys = list.stream().map(UserEntity::getId).toList();
        return details.findById(keys)
                .collectMap(UserDetailEntity::getId)
                .zipWith(
                        bindings.createQuery()
                                .in(DimensionUserEntity::getUserId, keys)
                                .fetch()
                                .collectList())
                .map(
                        tuple ->
                                list.stream()
                                        .map(
                                                user ->
                                                        userView(
                                                                user,
                                                                tuple.getT1().get(user.getId()),
                                                                tuple.getT2()))
                                        .toList());
    }

    private Map<String, Object> userView(
            UserEntity user, UserDetailEntity detail, List<DimensionUserEntity> dimensionUsers) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("name", user.getName());
        row.put("username", user.getUsername());
        row.put("status", user.getStatus());
        row.put("type", UserType.fromPersisted(user.getType()));
        if (detail != null) {
            row.put("telephone", detail.getTelephone());
            row.put("email", detail.getEmail());
            row.put("avatar", detail.getAvatar());
        }
        for (String type : List.of("role", "org", "position")) {
            List<Map<String, String>> targets =
                    dimensionUsers.stream()
                            .filter(
                                    binding ->
                                            user.getId().equals(binding.getUserId())
                                                    && type.equals(binding.getDimensionTypeId()))
                            .map(
                                    binding ->
                                            Map.of(
                                                    "id",
                                                    binding.getDimensionId(),
                                                    "name",
                                                    binding.getDimensionName()))
                            .toList();
            row.put(
                    type.equals("role") ? "roleList" : type.equals("org") ? "orgList" : "positions",
                    targets);
            row.put(
                    type.equals("role")
                            ? "roleIdList"
                            : type.equals("org") ? "orgIdList" : "positionIdList",
                    targets.stream().map(target -> target.get("id")).toList());
        }
        return row;
    }

    public Mono<Void> resetPassword(String id, String password) {
        UserEntity update = new UserEntity();
        update.setId(id);
        update.setPassword(password);
        return users.findById(id)
                .switchIfEmpty(Mono.error(missing()))
                .flatMap(old -> users.saveUser(Mono.just(update)))
                .then();
    }

    public Mono<Map<String, Object>> validateUsername(String name) {
        return users.findByUsername(name).hasElement().map(exists -> Map.of("passed", !exists));
    }

    public Map<String, Object> validatePassword(String password) {
        if (password.isBlank()) return Map.of("passed", false);
        try {
            passwords.validate(password);
            return Map.of("passed", true);
        } catch (IllegalArgumentException invalidPassword) {
            return Map.of("passed", false);
        }
    }
}
