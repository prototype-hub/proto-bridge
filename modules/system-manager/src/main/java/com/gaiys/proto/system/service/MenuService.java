package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hswebframework.ezorm.core.param.Sort;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.GenericReactiveTreeSupportCrudService;
import org.springframework.stereotype.Service;

@Service
public class MenuService extends GenericReactiveTreeSupportCrudService<MenuEntity, String> {
    /** 前端固定隐藏条件应保留旧菜单的空 options。仅适配该确切表达式，其他 nlike 保留官方框架包含 NULL 的语义；调用方查询和关系分组保持独立。 */
    public QueryParamEntity treeQuery(QueryParamEntity source) {
        if (source != null) {
            validateTerms(source.getTerms());
            if (source.getSorts() == null || source.getSorts().stream().anyMatch(Objects::isNull))
                throw ManagementSupport.invalid();
        }
        QueryParamEntity query =
                source == null ? new QueryParamEntity() : QueryParamEntity.of(source);
        query.setTerms(menuTerms(query.getTerms()));
        query.setSorts(new ArrayList<>(query.getSorts()));
        if (query.getSorts().isEmpty()) query.getSorts().add(new Sort("sortIndex").asc());
        return query.noPaging();
    }

    private void validateTerms(List<Term> terms) {
        if (terms == null) throw ManagementSupport.invalid();
        for (Term term : terms) {
            if (term == null || term.getOptions() == null) throw ManagementSupport.invalid();
            validateTerms(term.getTerms());
        }
    }

    private List<Term> menuTerms(List<Term> source) {
        List<Term> result = new ArrayList<>(source.size());
        for (Term original : source) {
            Term term = original.clone();
            List<Term> children = menuTerms(original.getTerms());
            term.setTerms(children);
            if ("options".equals(term.getColumn())
                    && "nlike".equals(term.getTermType())
                    && term.getOptions().isEmpty()
                    && "%show\":false%".equals(term.getValue())) {
                Term visible = term.clone();
                visible.setType(Term.Type.and);
                visible.setTerms(new ArrayList<>());
                Term absent = new Term();
                absent.setColumn("options");
                absent.setTermType("isnull");
                absent.setValue("1");
                absent.setType(Term.Type.or);
                Term group = new Term();
                group.setType(original.getType());
                group.setTermType(original.getTermType());
                group.setOptions(new ArrayList<>(original.getOptions()));
                group.setTerms(new ArrayList<>(List.of(visible, absent)));
                if (!children.isEmpty()) {
                    Term visibility = new Term();
                    visibility.setTerms(group.getTerms());
                    Term nested = new Term();
                    nested.setType(original.getType());
                    nested.setTerms(children);
                    group.setTerms(new ArrayList<>(List.of(visibility, nested)));
                }
                term = group;
            }
            result.add(term);
        }
        return result;
    }

    @Override
    public org.hswebframework.web.id.IDGenerator<String> getIDGenerator() {
        return org.hswebframework.web.id.IDGenerator.MD5;
    }

    @Override
    public void setChildren(MenuEntity entity, List<MenuEntity> children) {
        entity.setChildren(children);
    }
}
