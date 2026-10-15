package com.gaiys.proto.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

class MenuTreeQueryTest {
    private final MenuService service = new MenuService();

    @Test
    void visibilityExpansionCreatesARealGroupAndDoesNotMutateTheSourceTree() {
        Term hidden = Term.of("options", "nlike", "%show\":false%");
        hidden.setType(Term.Type.or);
        Term child = Term.of("name", "eq", "Visible");
        hidden.setTerms(List.of(child));
        QueryParamEntity source = new QueryParamEntity();
        source.setTerms(List.of(hidden));
        // Tree queries ignore paging; they do not introduce a new page-size validation rule.
        source.setPageSize(300);
        QueryParamEntity query = service.treeQuery(source);
        Term group = query.getTerms().getFirst();
        assertNull(group.getColumn());
        assertNull(group.getValue());
        assertEquals(Term.Type.or, group.getType());
        Term visibility = group.getTerms().getFirst();
        assertNull(visibility.getColumn());
        assertEquals("nlike", visibility.getTerms().get(0).getTermType());
        assertEquals("options", visibility.getTerms().get(0).getColumn());
        assertEquals("isnull", visibility.getTerms().get(1).getTermType());
        assertEquals(Term.Type.or, visibility.getTerms().get(1).getType());
        assertEquals(Term.Type.or, group.getTerms().get(1).getType());
        assertNotSame(child, group.getTerms().get(1).getTerms().getFirst());
        assertEquals("options", hidden.getColumn());
        assertEquals("%show\":false%", hidden.getValue());
        assertEquals(List.of(child), hidden.getTerms());
        assertTrue(source.isPaging());
        assertFalse(query.isPaging());
        assertTrue(source.getSorts().isEmpty());
        assertEquals("sortIndex", query.getSorts().getFirst().getName());
    }

    @Test
    void onlyTheExactUnmodifiedFrontendPredicateExpandsNullVisibility() {
        Term hidden = Term.of("options", "nlike", "%show\":false%");
        Term negated = Term.of("options", "nlike", "%show\":false%", "not");
        Term other = Term.of("options", "nlike", "%unrelated%");
        QueryParamEntity source = new QueryParamEntity();
        source.setTerms(List.of(hidden, negated, other));
        List<Term> terms = service.treeQuery(source).getTerms();
        assertNull(terms.get(0).getColumn());
        assertEquals(2, terms.get(0).getTerms().size());
        assertEquals("options", terms.get(1).getColumn());
        assertEquals(List.of("not"), terms.get(1).getOptions());
        assertEquals("%show\":false%", terms.get(1).getValue());
        assertEquals("options", terms.get(2).getColumn());
        assertEquals("%unrelated%", terms.get(2).getValue());
    }

    @Test
    void malformedNestedTermsAndSortsFailBeforeFrameworkClone() {
        QueryParamEntity source = new QueryParamEntity();
        source.setTerms(Collections.singletonList(null));
        assertThrows(BusinessException.class, () -> service.treeQuery(source));
        Term nested = new Term();
        nested.setTerms(Collections.singletonList(null));
        source.setTerms(List.of(nested));
        assertThrows(BusinessException.class, () -> service.treeQuery(source));
        source.setTerms(List.of());
        source.setSorts(Collections.singletonList(null));
        assertThrows(BusinessException.class, () -> service.treeQuery(source));
        source.setSorts(null);
        assertThrows(BusinessException.class, () -> service.treeQuery(source));
    }
}
