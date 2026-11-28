package com.gaiys.proto.prototypehub.web.info;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 研究的固定普通文本结构；HTTP与持久JsonCodec使用同一精确标量合同。 */
public final class UsabilityStudyContent {
    private UsabilityStudyContent() {}

    public record Task(
            String id,
            String title,
            String objective,
            String prerequisites,
            String resetNote,
            List<DemoScriptStep> steps) {
        public Task {
            id = identifier(id);
            title = text(title, 120, true);
            objective = text(objective, 2000, false);
            prerequisites = text(prerequisites, 2000, false);
            resetNote = text(resetNote, 2000, false);
            if (steps == null
                    || steps.size() > 12
                    || steps.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            steps = List.copyOf(steps);
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Task from(JsonNode node) {
            exact(node, "id", "title", "objective", "prerequisites", "resetNote", "steps");
            var steps = new ArrayList<DemoScriptStep>();
            for (var value : array(node, "steps")) steps.add(DemoScriptStep.from(value));
            return new Task(
                    string(node, "id"),
                    string(node, "title"),
                    optional(node, "objective"),
                    optional(node, "prerequisites"),
                    optional(node, "resetNote"),
                    steps);
        }
    }

    public record Option(String id, String label) {
        public Option {
            id = identifier(id);
            label = text(label, 200, true);
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Option from(JsonNode node) {
            exact(node, "id", "label");
            return new Option(string(node, "id"), string(node, "label"));
        }
    }

    public record Question(
            String id, String prompt, String type, boolean required, List<Option> options) {
        public Question {
            id = identifier(id);
            prompt = text(prompt, 500, true);
            if (type == null
                    || !Set.of("singleChoice", "rating", "text").contains(type)
                    || options == null
                    || options.size() > 8
                    || options.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            options = List.copyOf(options);
            if ("singleChoice".equals(type)) {
                if (options.size() < 2
                        || options.stream().map(Option::id).distinct().count() != options.size())
                    throw invalid();
            } else if (!options.isEmpty()) throw invalid();
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Question from(JsonNode node) {
            exact(node, "id", "prompt", "type", "required", "options");
            if (!node.path("required").isBoolean()) throw invalid();
            var options = new ArrayList<Option>();
            for (var value : array(node, "options")) options.add(Option.from(value));
            return new Question(
                    string(node, "id"),
                    string(node, "prompt"),
                    string(node, "type"),
                    node.get("required").booleanValue(),
                    options);
        }
    }

    public record TaskAnswer(String taskId, String outcome, String feedback) {
        public TaskAnswer {
            taskId = identifier(taskId);
            feedback = text(feedback, 2000, false);
            if (outcome == null
                    || !Set.of("completed", "not_completed", "skipped").contains(outcome))
                throw invalid();
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static TaskAnswer from(JsonNode node) {
            exact(node, "taskId", "outcome", "feedback");
            return new TaskAnswer(
                    string(node, "taskId"), string(node, "outcome"), optional(node, "feedback"));
        }
    }

    public record Answer(String questionId, String optionId, Integer rating, String text) {
        public Answer {
            questionId = identifier(questionId);
            if (optionId != null) optionId = identifier(optionId);
            if (rating != null && (rating < 1 || rating > 5)) throw invalid();
            if (text != null) text = UsabilityStudyContent.text(text, 2000, false);
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Answer from(JsonNode node) {
            exact(node, "questionId", "optionId", "rating", "text");
            var rating = node.get("rating");
            if (rating != null
                    && !rating.isNull()
                    && (!rating.isIntegralNumber() || !rating.canConvertToInt())) throw invalid();
            return new Answer(
                    string(node, "questionId"),
                    nullable(node, "optionId"),
                    rating == null || rating.isNull() ? null : rating.intValue(),
                    nullable(node, "text"));
        }
    }

    public static String text(String value, int max, boolean required) {
        if (value == null) value = "";
        if (value.length() > max
                || value.indexOf('\0') >= 0
                || (required && value.replaceAll("[\\s\\p{Z}\\uFEFF]", "").isEmpty()))
            throw invalid();
        return value;
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
        return value;
    }

    private static void exact(JsonNode node, String... names) {
        if (!node.isObject()) throw invalid();
        var allowed = Set.of(names);
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
    }

    private static String string(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String nullable(JsonNode node, String name) {
        var value = node.get(name);
        return value == null || value.isNull() ? null : string(node, name);
    }

    private static String optional(JsonNode node, String name) {
        var value = nullable(node, name);
        return value == null ? "" : value;
    }

    private static JsonNode array(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isArray()) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid usability study value");
    }
}
