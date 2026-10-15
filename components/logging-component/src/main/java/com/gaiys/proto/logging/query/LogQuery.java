package com.gaiys.proto.logging.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.logging.document.LogDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Data;

@Data
public class LogQuery {
    private int pageIndex = 0, pageSize = 20;
    private List<Term> terms = new ArrayList<>();
    private List<Sort> sorts = new ArrayList<>();

    @Data
    public static class Term {
        private String column, termType = "eq", type = "and";
        private JsonNode value;
        private List<Term> terms = new ArrayList<>();
    }

    public record Sort(String name, String order) {}

    public record Page(long total, int pageIndex, int pageSize, List<LogDocument> data) {}

    public static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "server",
                    "level",
                    "message",
                    "name",
                    "threadName",
                    "traceId",
                    "actorId",
                    "action",
                    "resourceType",
                    "resourceId",
                    "result",
                    "httpMethod",
                    "ip",
                    "target",
                    "method",
                    "username",
                    "userName",
                    "url",
                    "describe",
                    "createTime",
                    "requestTime",
                    "responseTime",
                    "duration",
                    "status");

    public static String canonicalField(String name) {
        if (name == null) return null;
        return switch (name) {
            case "userId" -> "actorId";
            case "className" -> "name";
            case "context.server" -> "server";
            case "context.username" -> "username";
            case "context.userName" -> "userName";
            default -> name;
        };
    }

    public static boolean numeric(String name) {
        return Set.of("createTime", "requestTime", "responseTime", "duration", "status")
                .contains(name);
    }

    public void validate() {
        if (pageIndex < 0
                || pageSize < 1
                || pageSize > 200
                || (long) pageIndex * pageSize > 9800
                || terms == null
                || sorts == null
                || sorts.size() > 5) invalid();
        validateTerms(terms, 0, new int[] {0});
        List<Sort> normalized = new ArrayList<>(sorts.size());
        for (var sort : sorts) {
            if (sort == null) invalid();
            String field = canonicalField(sort.name());
            if (field == null
                    || !FIELDS.contains(field)
                    || !("asc".equalsIgnoreCase(sort.order())
                            || "desc".equalsIgnoreCase(sort.order()))) invalid();
            normalized.add(new Sort(field, sort.order()));
        }
        sorts = normalized;
    }

    private static void validateTerms(List<Term> terms, int depth, int[] count) {
        if (depth > 8) invalid();
        for (var term : terms) {
            if (++count[0] > 128
                    || term == null
                    || term.getTerms() == null
                    || !("and".equalsIgnoreCase(term.getType())
                            || "or".equalsIgnoreCase(term.getType()))) invalid();
            if (term.getColumn() != null) {
                // Normalize within the bounded traversal, before field validation, so aliases
                // query the original ES fields without recursively walking unchecked input.
                term.setColumn(canonicalField(term.getColumn()));
                if (!FIELDS.contains(term.getColumn())
                        || term.getTermType() == null
                        || !Set.of(
                                        "eq", "not", "like", "nlike", "gt", "gte", "lt", "lte",
                                        "btw", "nbtw", "in", "nin", "isnull", "notnull")
                                .contains(term.getTermType())) invalid();
                if (!Set.of("isnull", "notnull").contains(term.getTermType())) {
                    if (term.getValue() == null || term.getValue().toString().length() > 4096)
                        invalid();
                    if (Set.of("btw", "nbtw", "in", "nin").contains(term.getTermType())
                            && (!term.getValue().isArray()
                                    || term.getValue().isEmpty()
                                    || term.getValue().size() > 100)) invalid();
                    if (Set.of("btw", "nbtw").contains(term.getTermType())
                            && term.getValue().size() != 2) invalid();
                    boolean array = Set.of("btw", "nbtw", "in", "nin").contains(term.getTermType());
                    if (array) for (var value : term.getValue()) validateValue(term, value);
                    else validateValue(term, term.getValue());
                    if (numeric(term.getColumn())
                            && Set.of("like", "nlike").contains(term.getTermType())) invalid();
                }
            } else if (term.getTerms().isEmpty()) invalid();
            validateTerms(term.getTerms(), depth + 1, count);
        }
    }

    private static void validateValue(Term term, JsonNode value) {
        if (value.isNull() || value.isContainerNode() || value.isBoolean()) invalid();
        if (numeric(term.getColumn())) {
            try {
                Long.parseLong(value.asText());
            } catch (NumberFormatException error) {
                invalid();
            }
        }
    }

    private static void invalid() {
        throw new IllegalArgumentException("validation.log_query_invalid");
    }
}
