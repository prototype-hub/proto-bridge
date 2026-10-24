package com.gaiys.proto.logging.query;

import static org.junit.jupiter.api.Assertions.*;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.translator.LogQueryTranslator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogQueryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mixedFlatRelationsUseAndPrecedenceWhileExplicitGroupsRetainParentheses() throws Exception {
        LogQuery flat =
                mapper.readValue(
                        """
{"terms":[{"column":"name","value":"A"},{"column":"level","value":"B","type":"or"},{"column":"server","value":"C","type":"and"}]}
""",
                        LogQuery.class);
        LogQuery grouped =
                mapper.readValue(
                        """
{"terms":[{"terms":[{"column":"name","value":"A"},{"column":"level","value":"B","type":"or"}]},{"column":"server","value":"C","type":"and"}]}
""",
                        LogQuery.class);
        for (int values = 0; values < 8; values++) {
            boolean a = (values & 1) != 0, b = (values & 2) != 0, c = (values & 4) != 0;
            LogDocument document =
                    LogDocument.builder()
                            .kind("system")
                            .name(a ? "A" : "other")
                            .level(b ? "B" : "other")
                            .server(c ? "C" : "other")
                            .build();
            assertBoth(flat, document, a || b && c);
            assertBoth(grouped, document, (a || b) && c);
        }
    }

    @Test
    void multipleAndArmsRemainIndependentInsideOr() throws Exception {
        LogQuery query =
                mapper.readValue(
                        """
{"terms":[{"column":"name","value":"A","type":"or"},{"column":"level","value":"B","type":"and"},{"column":"server","value":"C","type":"or"},{"column":"message","value":"D","type":"and"}]}
""",
                        LogQuery.class);
        for (int values = 0; values < 16; values++) {
            boolean a = (values & 1) != 0, b = (values & 2) != 0;
            boolean c = (values & 4) != 0, d = (values & 8) != 0;
            LogDocument document =
                    LogDocument.builder()
                            .kind("system")
                            .name(a ? "A" : "other")
                            .level(b ? "B" : "other")
                            .server(c ? "C" : "other")
                            .message(d ? "D" : "other")
                            .build();
            assertBoth(query, document, a && b || c && d);
        }
    }

    private void assertBoth(LogQuery query, LogDocument document, boolean expected) {
        assertEquals(expected, LogQueryTranslator.memory("system", query).test(document));
        assertEquals(expected, evaluate(LogQueryTranslator.elastic("system", query), document));
    }

    /** Evaluate the emitted ES bool/term tree against the same truth-table records. */
    private boolean evaluate(Query query, LogDocument document) {
        if (query.isMatchAll()) return true;
        if (query.isTerm())
            return query.term().value().stringValue().equals(document.field(query.term().field()));
        if (!query.isBool()) throw new AssertionError("Unexpected ES query: " + query);
        var clauses = query.bool();
        return clauses.must().stream().allMatch(child -> evaluate(child, document))
                && clauses.filter().stream().allMatch(child -> evaluate(child, document))
                && clauses.mustNot().stream().noneMatch(child -> evaluate(child, document))
                && (clauses.should().isEmpty()
                        || clauses.should().stream().anyMatch(child -> evaluate(child, document)));
    }

    @Test
    void nestedConditionsAndNumericPagingTranslateConsistently() throws Exception {
        var query =
                mapper.readValue(
                        """
{"pageSize":10,"terms":[{"column":"level","value":"WARN"},{"type":"and","terms":[{"column":"createTime","termType":"btw","value":[100,200]},{"column":"message","termType":"like","value":"%failed%","type":"or"}]}]}
""",
                        LogQuery.class);
        var filter = LogQueryTranslator.memory("system", query);
        assertTrue(
                filter.test(
                        LogDocument.builder()
                                .kind("system")
                                .level("WARN")
                                .createTime(150)
                                .message("ok")
                                .build()));
        assertTrue(
                filter.test(
                        LogDocument.builder()
                                .kind("system")
                                .level("WARN")
                                .createTime(250)
                                .message("operation failed")
                                .build()));
        assertFalse(
                filter.test(
                        LogDocument.builder()
                                .kind("access")
                                .level("WARN")
                                .createTime(150)
                                .build()));
        assertFalse(
                filter.test(
                        LogDocument.builder()
                                .kind("system")
                                .level("INFO")
                                .createTime(150)
                                .build()));
        assertNotNull(LogQueryTranslator.elastic("system", query));
    }

    @Test
    void rejectsUnknownFieldsUnboundedWindowsAndMalformedRanges() throws Exception {
        for (String value :
                List.of(
                        "{\"pageSize\":1000}",
                        "{\"pageIndex\":9999}",
                        "{\"terms\":[{\"column\":\"password\",\"value\":\"anything\"}]}",
                        "{\"terms\":[{\"column\":\"createTime\",\"termType\":\"btw\",\"value\":[1]}]}")) {
            var query = mapper.readValue(value, LogQuery.class);
            assertThrows(IllegalArgumentException.class, query::validate);
        }
    }

    private LogQuery like(String operator, String pattern) {
        var term = new LogQuery.Term();
        term.setColumn("message");
        term.setTermType(operator);
        term.setValue(mapper.valueToTree(pattern));
        var query = new LogQuery();
        query.setTerms(List.of(term));
        return query;
    }

    @Test
    void legalHostileLikePatternCompletesWithoutBacktracking() {
        var predicate = LogQueryTranslator.memory("system", like("like", "%a".repeat(18) + "b"));
        var document = LogDocument.builder().kind("system").message("a".repeat(30) + "c").build();
        assertTimeout(
                Duration.ofSeconds(1),
                () -> {
                    for (int index = 0; index < 100; index++) assertFalse(predicate.test(document));
                });
    }

    @Test
    void likeMatchesNewlinesCodePointsAndLiteralWildcards() {
        for (var row :
                List.of(
                        List.of("%failed%", "start\nfailed\nend"),
                        List.of("a_b", "a\nb"),
                        List.of("a_b", "a😀b"),
                        List.of("%C:\\work%", "at C:\\work\\file"),
                        List.of("%a*?b%", "a*?b"),
                        List.of("%%_%%", "\n"),
                        List.of("", ""),
                        List.of("%%", ""))) {
            var doc = LogDocument.builder().kind("system").message(row.get(1)).build();
            assertTrue(
                    LogQueryTranslator.memory("system", like("like", row.getFirst())).test(doc),
                    row.toString());
            assertFalse(
                    LogQueryTranslator.memory("system", like("nlike", row.getFirst())).test(doc),
                    row.toString());
        }
        assertFalse(
                LogQueryTranslator.memory("system", like("like", "a*?b"))
                        .test(LogDocument.builder().kind("system").message("axxb").build()));
        assertFalse(
                LogQueryTranslator.memory("system", like("like", "_"))
                        .test(LogDocument.builder().kind("system").message("").build()));
        assertTrue(
                LogQueryTranslator.memory("system", like("nlike", "anything"))
                        .test(LogDocument.builder().kind("system").build()));
    }

    @Test
    void elasticLikeEscapesBackslashesBeforeLiteralWildcardCharacters() {
        var query = like("like", "%C:\\work\\a*?_%");
        String wildcard =
                LogQueryTranslator.elastic("system", query)
                        .bool()
                        .filter()
                        .get(1)
                        .wildcard()
                        .value();
        assertEquals("*C:\\\\work\\\\a\\*\\??*", wildcard);
        var negative = like("nlike", "%C:\\work%");
        assertEquals(
                "*C:\\\\work*",
                LogQueryTranslator.elastic("system", negative)
                        .bool()
                        .filter()
                        .get(1)
                        .bool()
                        .mustNot()
                        .getFirst()
                        .wildcard()
                        .value());
    }

    @Test
    void numericSortUsesNumbers() {
        var query = new LogQuery();
        query.setSorts(List.of(new LogQuery.Sort("createTime", "asc")));
        var docs =
                List.of(
                                LogDocument.builder().createTime(20).build(),
                                LogDocument.builder().createTime(3).build())
                        .stream()
                        .sorted(LogQueryTranslator.order(query))
                        .toList();
        assertEquals(3, docs.getFirst().getCreateTime());
    }

    @Test
    void aliasesNormalizeNestedQueriesSortsAndHistoricalJson() throws Exception {
        LogDocument historical =
                mapper.readValue(
                        "{\"kind\":\"system\",\"actorId\":\"actor-a\",\"name\":\"ClassA\",\"server\":\"server-a\"}",
                        LogDocument.class);
        var json = mapper.readTree(mapper.writeValueAsString(historical));
        assertEquals("actor-a", json.path("userId").asText());
        assertEquals("ClassA", json.path("className").asText());
        assertEquals("server-a", json.path("context").path("server").asText());
        var query =
                mapper.readValue(
                        "{\"terms\":[{\"terms\":[{\"column\":\"userId\",\"value\":\"actor-a\"},{\"column\":\"className\",\"value\":\"ClassA\"},{\"column\":\"context.server\",\"value\":\"server-a\"}]}],\"sorts\":[{\"name\":\"userId\",\"order\":\"asc\"},{\"name\":\"className\",\"order\":\"desc\"},{\"name\":\"context.server\",\"order\":\"asc\"}]}",
                        LogQuery.class);
        assertTrue(LogQueryTranslator.memory("system", query).test(historical));
        assertEquals(
                List.of("actorId", "name", "server"),
                query.getTerms().getFirst().getTerms().stream()
                        .map(LogQuery.Term::getColumn)
                        .toList());
        assertEquals(
                List.of("actorId", "name", "server"),
                query.getSorts().stream().map(LogQuery.Sort::name).toList());
        String elastic = LogQueryTranslator.elastic("system", query).toString();
        assertTrue(elastic.contains("actorId"));
        assertTrue(elastic.contains("name"));
        assertTrue(elastic.contains("server"));
        assertFalse(elastic.contains("userId"));
        assertFalse(elastic.contains("className"));
        assertFalse(elastic.contains("context.server"));
        var sorted =
                List.of(LogDocument.builder().actorId("actor-b").build(), historical).stream()
                        .sorted(LogQueryTranslator.order(query))
                        .toList();
        assertSame(historical, sorted.getFirst());
    }

    @Test
    void loginAndDisplayNameSurviveJsonRoundtripIndependently() throws Exception {
        LogDocument document =
                LogDocument.builder()
                        .kind("access")
                        .username("demo-login")
                        .userName("Display Name")
                        .server("server-a")
                        .build();
        String source = mapper.writeValueAsString(document);
        var json = mapper.readTree(source);
        assertEquals("demo-login", json.path("username").asText());
        assertEquals("Display Name", json.path("userName").asText());
        LogDocument restored = mapper.readValue(source, LogDocument.class);
        assertEquals("demo-login", restored.getUsername());
        assertEquals("demo-login", restored.getContext().get("username"));
        assertEquals("Display Name", restored.getContext().get("userName"));
        LogDocument canonical =
                mapper.readValue(
                        "{\"username\":\"canonical-login\",\"userName\":\"Canonical"
                                + " Name\",\"context\":{\"username\":\"ignored-login\",\"userName\":\"Ignored"
                                + " Name\"}}",
                        LogDocument.class);
        assertEquals("canonical-login", canonical.getContext().get("username"));
        assertEquals("Canonical Name", canonical.getContext().get("userName"));
    }

    @Test
    void nullChecksAndNegativeOperatorsRetainMissingValueSemantics() throws Exception {
        LogDocument missing = LogDocument.builder().kind("system").build();
        for (String operator : List.of("isnull", "notnull", "not", "nlike", "nin", "nbtw")) {
            var query = new LogQuery();
            var term = new LogQuery.Term();
            term.setColumn("className");
            term.setTermType(operator);
            if (List.of("not", "nlike").contains(operator))
                term.setValue(mapper.readTree("\"ClassA\""));
            if (operator.equals("nin")) term.setValue(mapper.readTree("[\"ClassA\"]"));
            if (operator.equals("nbtw")) term.setValue(mapper.readTree("[\"ClassA\",\"ClassZ\"]"));
            query.setTerms(List.of(term));
            assertEquals(
                    List.of("isnull", "not", "nlike", "nin").contains(operator),
                    LogQueryTranslator.memory("system", query).test(missing),
                    operator);
            var elastic = LogQueryTranslator.elastic("system", query).bool().filter().get(1);
            if (operator.equals("isnull"))
                assertEquals("name", elastic.bool().mustNot().getFirst().exists().field());
            else if (operator.equals("notnull")) assertEquals("name", elastic.exists().field());
            else if (operator.equals("nbtw"))
                assertEquals("name", elastic.bool().filter().getFirst().exists().field());
            else assertTrue(elastic.bool().filter().isEmpty(), operator);
        }
        var nullValue =
                mapper.readValue(
                        "{\"terms\":[{\"column\":\"userId\",\"termType\":\"isnull\",\"value\":null}]}",
                        LogQuery.class);
        assertTrue(LogQueryTranslator.memory("system", nullValue).test(missing));
        var omittedValue =
                mapper.readValue(
                        "{\"terms\":[{\"column\":\"userId\",\"termType\":\"notnull\"}]}",
                        LogQuery.class);
        assertFalse(LogQueryTranslator.memory("system", omittedValue).test(missing));
    }

    @Test
    void emptyAndUndefinedTextStayLiteralAndNullChecksIgnoreTheirValue() throws Exception {
        for (String text : List.of("", "undefined")) {
            var query = new LogQuery();
            var term = new LogQuery.Term();
            term.setColumn("name");
            term.setValue(mapper.valueToTree(text));
            query.setTerms(List.of(term));
            assertTrue(
                    LogQueryTranslator.memory("system", query)
                            .test(LogDocument.builder().kind("system").name(text).build()));
            assertFalse(
                    LogQueryTranslator.memory("system", query)
                            .test(LogDocument.builder().kind("system").build()));
            term.setTermType("isnull");
            assertTrue(
                    LogQueryTranslator.memory("system", query)
                            .test(LogDocument.builder().kind("system").build()));
            assertFalse(
                    LogQueryTranslator.memory("system", query)
                            .test(LogDocument.builder().kind("system").name(text).build()));
        }
    }
}
