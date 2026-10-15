package com.gaiys.proto.logging.translator;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.query.LogQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class LogQueryTranslator {
    private LogQueryTranslator() {}

    public static Query elastic(String kind, LogQuery request) {
        request.validate();
        return Query.of(
                q ->
                        q.bool(
                                b ->
                                        b.filter(
                                                Query.of(
                                                        k ->
                                                                k.term(
                                                                        t ->
                                                                                t.field("kind")
                                                                                        .value(
                                                                                                kind))),
                                                group(request.getTerms()))));
    }

    private static Query group(List<LogQuery.Term> terms) {
        List<Query> alternatives = new ArrayList<>();
        for (List<LogQuery.Term> conjunction : conjunctions(terms)) {
            List<Query> required = new ArrayList<>();
            for (LogQuery.Term term : conjunction) {
                Query current = term.getColumn() == null ? group(term.getTerms()) : one(term);
                if (term.getColumn() != null && !term.getTerms().isEmpty()) {
                    Query nested = group(term.getTerms()), value = current;
                    current = Query.of(q -> q.bool(b -> b.must(value, nested)));
                }
                required.add(current);
            }
            alternatives.add(
                    required.size() == 1
                            ? required.getFirst()
                            : Query.of(q -> q.bool(b -> b.must(required))));
        }
        if (alternatives.isEmpty()) return Query.of(q -> q.matchAll(m -> m));
        return alternatives.size() == 1
                ? alternatives.getFirst()
                : Query.of(q -> q.bool(b -> b.should(alternatives).minimumShouldMatch("1")));
    }

    /** 普通条件按 SQL 的 AND 优先级组合；显式 terms 子组保留独立括号。 */
    private static List<List<LogQuery.Term>> conjunctions(List<LogQuery.Term> terms) {
        List<List<LogQuery.Term>> result = new ArrayList<>();
        List<LogQuery.Term> current = new ArrayList<>();
        for (LogQuery.Term term : terms) {
            if (!current.isEmpty() && "or".equalsIgnoreCase(term.getType())) {
                result.add(current);
                current = new ArrayList<>();
            }
            current.add(term);
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    private static Query one(LogQuery.Term term) {
        String field = term.getColumn(), op = term.getTermType();
        var value = term.getValue();
        if (op.equals("isnull") || op.equals("notnull")) {
            Query exists = Query.of(q -> q.exists(e -> e.field(field)));
            return op.equals("isnull") ? Query.of(q -> q.bool(b -> b.mustNot(exists))) : exists;
        }
        if (op.equals("not") || op.equals("nlike") || op.equals("nbtw") || op.equals("nin")) {
            var positive = new LogQuery.Term();
            positive.setColumn(field);
            positive.setValue(value);
            positive.setTermType(
                    switch (op) {
                        case "not" -> "eq";
                        case "nlike" -> "like";
                        case "nbtw" -> "btw";
                        default -> "in";
                    });
            Query nested = one(positive);
            // The local query contract excludes missing values from nbtw, while the other
            // negative operators include them. Preserve that distinction in ES as well.
            if (op.equals("nbtw"))
                return Query.of(
                        q ->
                                q.bool(
                                        b ->
                                                b.filter(f -> f.exists(e -> e.field(field)))
                                                        .mustNot(nested)));
            return Query.of(q -> q.bool(b -> b.mustNot(nested)));
        }
        if (op.equals("eq"))
            return Query.of(
                    q -> q.term(t -> t.field(field).value(v -> v.stringValue(value.asText()))));
        if (op.equals("like")) {
            String wildcard =
                    value.asText()
                            .replace("*", "\\*")
                            .replace("?", "\\?")
                            .replace("%", "*")
                            .replace("_", "?");
            return Query.of(q -> q.wildcard(w -> w.field(field).value(wildcard)));
        }
        if (op.equals("in")) {
            var queries = new java.util.ArrayList<Query>();
            for (var entry : value)
                queries.add(Query.of(q -> q.term(t -> t.field(field).value(entry.asText()))));
            return Query.of(q -> q.bool(b -> b.should(queries).minimumShouldMatch("1")));
        }
        return Query.of(
                q ->
                        q.range(
                                range ->
                                        range.untyped(
                                                n -> {
                                                    n.field(field);
                                                    switch (op) {
                                                        case "gt" ->
                                                                n.gt(JsonData.of(value.asText()));
                                                        case "gte" ->
                                                                n.gte(JsonData.of(value.asText()));
                                                        case "lt" ->
                                                                n.lt(JsonData.of(value.asText()));
                                                        case "lte" ->
                                                                n.lte(JsonData.of(value.asText()));
                                                        case "btw" -> {
                                                            n.gte(
                                                                    JsonData.of(
                                                                            value.get(0).asText()));
                                                            n.lte(
                                                                    JsonData.of(
                                                                            value.get(1).asText()));
                                                        }
                                                        default ->
                                                                throw new IllegalArgumentException(
                                                                        "validation.log_query_invalid");
                                                    }
                                                    return n;
                                                })));
    }

    public static Predicate<LogDocument> memory(String kind, LogQuery request) {
        request.validate();
        return doc -> kind.equals(doc.getKind()) && matchGroup(doc, request.getTerms());
    }

    private static boolean matchGroup(LogDocument doc, List<LogQuery.Term> terms) {
        return terms.isEmpty()
                || conjunctions(terms).stream()
                        .anyMatch(
                                group ->
                                        group.stream()
                                                .allMatch(
                                                        term ->
                                                                (term.getColumn() == null
                                                                                || match(doc, term))
                                                                        && matchGroup(
                                                                                doc,
                                                                                term.getTerms())));
    }

    private static boolean match(LogDocument doc, LogQuery.Term term) {
        String actual = doc.field(term.getColumn()), op = term.getTermType();
        var value = term.getValue();
        if (op.equals("isnull")) return actual == null;
        if (op.equals("notnull")) return actual != null;
        if (actual == null) return op.equals("not") || op.equals("nin") || op.equals("nlike");
        return switch (op) {
            case "eq" -> actual.equals(value.asText());
            case "not" -> !actual.equals(value.asText());
            case "like", "nlike" -> {
                StringBuilder pattern = new StringBuilder("^");
                for (char c : value.asText().toCharArray())
                    pattern.append(
                            c == '%' ? ".*" : c == '_' ? "." : Pattern.quote(String.valueOf(c)));
                pattern.append('$');
                boolean matched = actual.matches(pattern.toString());
                yield op.equals("like") == matched;
            }
            case "in", "nin" -> {
                boolean found = false;
                for (var entry : value) if (actual.equals(entry.asText())) found = true;
                yield op.equals("in") == found;
            }
            case "btw", "nbtw" -> {
                boolean between =
                        compare(actual, value.get(0).asText(), term.getColumn()) >= 0
                                && compare(actual, value.get(1).asText(), term.getColumn()) <= 0;
                yield op.equals("btw") == between;
            }
            case "gt" -> compare(actual, value.asText(), term.getColumn()) > 0;
            case "gte" -> compare(actual, value.asText(), term.getColumn()) >= 0;
            case "lt" -> compare(actual, value.asText(), term.getColumn()) < 0;
            case "lte" -> compare(actual, value.asText(), term.getColumn()) <= 0;
            default -> false;
        };
    }

    private static int compare(String a, String b, String field) {
        try {
            return LogQuery.numeric(field)
                    ? Long.compare(Long.parseLong(a), Long.parseLong(b))
                    : a.compareTo(b);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("validation.log_query_invalid");
        }
    }

    public static Comparator<LogDocument> order(LogQuery request) {
        request.validate();
        Comparator<LogDocument> result = null;
        List<LogQuery.Sort> sorts =
                request.getSorts().isEmpty()
                        ? List.of(new LogQuery.Sort("createTime", "desc"))
                        : request.getSorts();
        for (var sort : sorts) {
            Comparator<LogDocument> current =
                    (a, b) -> {
                        String av = a.field(sort.name()), bv = b.field(sort.name());
                        return av == null
                                ? bv == null ? 0 : -1
                                : bv == null ? 1 : compare(av, bv, sort.name());
                    };
            if ("desc".equalsIgnoreCase(sort.order())) current = current.reversed();
            result = result == null ? current : result.thenComparing(current);
        }
        return result;
    }
}
