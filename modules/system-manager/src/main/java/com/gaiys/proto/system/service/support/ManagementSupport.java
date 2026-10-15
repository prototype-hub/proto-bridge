package com.gaiys.proto.system.service.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;

/** 系统管理适配的纯数据与参数工具。保留前端查询分组、批次上限和统一异常键，不访问数据库或承担认证检查。 */
public final class ManagementSupport {
    private ManagementSupport() {}

    public static BusinessException invalid() {
        return new BusinessException("validation.management_invalid", "BAD_REQUEST", 400);
    }

    public static BusinessException missing() {
        return new BusinessException("error.http.404", "NOT_FOUND", 404);
    }

    public static BusinessException conflict() {
        return new BusinessException("error.conflict", "CONFLICT", 409);
    }

    public static String text(Map<String, ?> values, String key) {
        return Objects.toString(values.get(key), "");
    }

    public static List<String> ids(Object values) {
        if (values == null) return List.of();
        if (!(values instanceof Collection<?> list) || list.size() > 200) throw invalid();
        // 上限按原始输入条数检查，重复标识不能绕过批次限制；结果保留首次出现顺序。
        Set<String> unique = new LinkedHashSet<>();
        for (Object value : list) {
            String id =
                    value instanceof Map<?, ?> map
                            ? Objects.toString(map.get("id"), "")
                            : Objects.toString(value, "");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
            unique.add(id);
        }
        return List.copyOf(unique);
    }

    public static void limit(QueryParamEntity query) {
        // 分页条数必须为正；负数会进入数据库 LIMIT 并导致服务端错误，零值会产生不一致的空页。
        if (query.getPageSize() < 1
                || query.getPageSize() > 200
                || query.getPageIndex() < 0
                || query.getPageIndex() > 500) throw invalid();
        // 原生 Term setter 接收 null；在递归适配或克隆之前拒绝空条目，不改变合法空分组和 NULL 比较值。
        validateTerms(query.getTerms());
        if (query.getSorts() == null || query.getSorts().stream().anyMatch(Objects::isNull))
            throw invalid();
    }

    private static void validateTerms(List<Term> terms) {
        if (terms == null) throw invalid();
        for (Term term : terms) {
            if (term == null || term.getOptions() == null) throw invalid();
            validateTerms(term.getTerms());
        }
    }

    public static QueryParamEntity typed(QueryParamEntity source, String type) {
        limit(source);
        QueryParamEntity query = QueryParamEntity.of(source);
        Term group = new Term();
        group.setTerms(new ArrayList<>(query.getTerms()));
        Term term = new Term();
        term.setColumn("typeId");
        term.setValue(type);
        term.setTermType("eq");
        query.setTerms(new ArrayList<>(List.of(group, term)));
        return query;
    }

    public static void normalizeUserTerms(List<Term> terms) {
        for (Term term : terms) {
            List<String> options = term.getOptions();
            if ("id".equals(term.getColumn())
                    && !options.isEmpty()
                    && Set.of("org", "role", "position").contains(options.get(0))) {
                String suffix = options.contains("not") ? "$not" : "";
                term.setTermType("in-dimension$" + options.get(0) + suffix);
            }
            normalizeUserTerms(term.getTerms());
        }
    }
}
