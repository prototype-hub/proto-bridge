package com.gaiys.proto.prototypehub.service.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityResponseEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityStudyEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Answer;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Question;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Task;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.TaskAnswer;
import com.gaiys.proto.prototypehub.web.request.UsabilityStudyRequests;
import com.gaiys.proto.prototypehub.web.response.UsabilityStudyAggregate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 定义、答卷及匿名白名单的纯数据边界，不访问身份/文件/数据库。 */
@Component
@RequiredArgsConstructor
public class UsabilityStudyValidator {
    private final ObjectMapper json;

    public record Definition(
            String title,
            String introduction,
            String consentText,
            List<Task> tasks,
            List<Question> questions) {}

    public record AnswerFields(
            List<TaskAnswer> taskAnswers, List<Answer> answers, String feedback) {}

    public Definition definition(UsabilityStudyRequests.Fields input) {
        var result =
                new Definition(
                        UsabilityStudyContent.text(input.getTitle(), 120, true),
                        UsabilityStudyContent.text(input.getIntroduction(), 4000, false),
                        UsabilityStudyContent.text(input.getConsentText(), 4000, true),
                        List.copyOf(input.getTasks()),
                        List.copyOf(input.getQuestions()));
        checkDefinition(result, false);
        return result;
    }

    public Definition definition(PrototypeUsabilityStudyEntity row) {
        return new Definition(
                row.getTitle(),
                row.getIntroduction(),
                row.getConsentText(),
                row.getTasks(),
                row.getQuestions());
    }

    public void opening(PrototypeUsabilityStudyEntity row) {
        checkDefinition(definition(row), true);
    }

    private void checkDefinition(Definition value, boolean opening) {
        if (value.tasks().size() > 12
                || value.questions().size() > 8
                || (opening && value.tasks().isEmpty())) throw invalid();
        Set<String> taskIds = new HashSet<>();
        int steps = 0;
        for (Task task : value.tasks()) {
            if (!taskIds.add(task.id()) || (opening && task.steps().isEmpty())) throw invalid();
            Set<String> stepIds = new HashSet<>();
            for (var step : task.steps()) if (!stepIds.add(step.id())) throw invalid();
            steps += task.steps().size();
        }
        if (steps > 48
                || value.questions().stream().map(Question::id).distinct().count()
                        != value.questions().size()
                || encode(value).getBytes(StandardCharsets.UTF_8).length > 131072) throw invalid();
    }

    public AnswerFields answers(
            PrototypeUsabilityStudyEntity study,
            UsabilityStudyRequests.Progress input,
            boolean submit) {
        var result =
                new AnswerFields(
                        List.copyOf(input.getTaskAnswers()),
                        List.copyOf(input.getAnswers()),
                        UsabilityStudyContent.text(input.getFeedback(), 2000, false));
        if (encode(result).getBytes(StandardCharsets.UTF_8).length > 65536) throw invalidAnswers();
        Map<String, Task> tasks =
                study.getTasks().stream().collect(Collectors.toMap(Task::id, Function.identity()));
        Set<String> taskIds = new HashSet<>();
        for (var answer : result.taskAnswers()) {
            if (!taskIds.add(answer.taskId()) || !tasks.containsKey(answer.taskId()))
                throw invalidAnswers();
        }
        if (submit && taskIds.size() != tasks.size()) throw incomplete();
        Map<String, Question> questions =
                study.getQuestions().stream()
                        .collect(Collectors.toMap(Question::id, Function.identity()));
        Set<String> answered = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Answer answer : result.answers()) {
            Question question = questions.get(answer.questionId());
            if (question == null || !seen.add(answer.questionId())) throw invalidAnswers();
            switch (question.type()) {
                case "singleChoice" -> {
                    if (answer.rating() != null
                            || answer.text() != null
                            || answer.optionId() == null
                            || question.options().stream()
                                    .noneMatch(option -> option.id().equals(answer.optionId())))
                        throw invalidAnswers();
                    answered.add(question.id());
                }
                case "rating" -> {
                    if (answer.optionId() != null
                            || answer.text() != null
                            || answer.rating() == null) throw invalidAnswers();
                    answered.add(question.id());
                }
                case "text" -> {
                    if (answer.optionId() != null
                            || answer.rating() != null
                            || answer.text() == null) throw invalidAnswers();
                    if (!answer.text().replaceAll("[\\s\\p{Z}\\uFEFF]", "").isEmpty())
                        answered.add(question.id());
                }
                default -> throw invalidAnswers();
            }
        }
        if (submit
                && study.getQuestions().stream()
                        .anyMatch(q -> q.required() && !answered.contains(q.id())))
            throw incomplete();
        return result;
    }

    /** 只投影结构化有效提交；不会把参与者原文、身份或时间放入汇总对象。 */
    public static UsabilityStudyAggregate aggregate(
            PrototypeUsabilityStudyEntity study, List<PrototypeUsabilityResponseEntity> rows) {
        if (rows.size() > 200) throw limit();
        var tasks = new ArrayList<UsabilityStudyAggregate.TaskCounts>();
        for (Task task : study.getTasks()) {
            var counts = new HashMap<String, Long>();
            for (var row : rows)
                for (var a : row.getTaskAnswers())
                    if (a.taskId().equals(task.id())) counts.merge(a.outcome(), 1L, Long::sum);
            long complete = counts.getOrDefault("completed", 0L),
                    failed = counts.getOrDefault("not_completed", 0L),
                    skipped = counts.getOrDefault("skipped", 0L);
            tasks.add(
                    new UsabilityStudyAggregate.TaskCounts(
                            task.title(),
                            complete,
                            failed,
                            skipped,
                            rows.size() - complete - failed - skipped));
        }
        var questions = new ArrayList<UsabilityStudyAggregate.QuestionCounts>();
        long textCount = 0;
        for (Question q : study.getQuestions()) {
            if ("text".equals(q.type())) {
                textCount++;
                continue;
            }
            var counts = new HashMap<String, Long>();
            long answered = 0;
            for (var row : rows)
                for (var a : row.getAnswers())
                    if (a.questionId().equals(q.id())) {
                        String value =
                                "rating".equals(q.type()) ? a.rating().toString() : a.optionId();
                        counts.merge(value, 1L, Long::sum);
                        answered++;
                    }
            var options = new ArrayList<UsabilityStudyAggregate.OptionCount>();
            if ("rating".equals(q.type()))
                for (int i = 1; i <= 5; i++)
                    options.add(
                            new UsabilityStudyAggregate.OptionCount(
                                    Integer.toString(i),
                                    counts.getOrDefault(Integer.toString(i), 0L)));
            else
                for (var option : q.options())
                    options.add(
                            new UsabilityStudyAggregate.OptionCount(
                                    option.label(), counts.getOrDefault(option.id(), 0L)));
            questions.add(
                    new UsabilityStudyAggregate.QuestionCounts(
                            q.prompt(),
                            q.type(),
                            answered,
                            rows.size() - answered,
                            List.copyOf(options)));
        }
        return new UsabilityStudyAggregate(
                rows.size(), List.copyOf(tasks), List.copyOf(questions), textCount);
    }

    /** CSV只消费匿名DTO，固定作者标签也做公式与引号防护。 */
    public static byte[] csv(UsabilityStudyAggregate value) {
        var out = new StringBuilder("section,item,option,count\r\n");
        csvRow(out, "summary", "submitted", "", value.submittedCount());
        csvRow(out, "summary", "excludedTextQuestions", "", value.excludedTextQuestionCount());
        for (var t : value.tasks()) {
            csvRow(out, "task", t.title(), "completed", t.completed());
            csvRow(out, "task", t.title(), "not_completed", t.notCompleted());
            csvRow(out, "task", t.title(), "skipped", t.skipped());
            csvRow(out, "task", t.title(), "missing", t.missing());
        }
        for (var q : value.questions()) {
            csvRow(out, q.type(), q.prompt(), "answered", q.answered());
            csvRow(out, q.type(), q.prompt(), "missing", q.missing());
            for (var o : q.options()) csvRow(out, q.type(), q.prompt(), o.label(), o.count());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void csvRow(
            StringBuilder out, String section, String item, String option, long count) {
        out.append(cell(section))
                .append(',')
                .append(cell(item))
                .append(',')
                .append(cell(option))
                .append(',')
                .append(count)
                .append("\r\n");
    }

    private static String cell(String value) {
        String stripped = value.replaceFirst("^[\\s\\p{Z}\\uFEFF]*", "");
        if (!stripped.isEmpty()
                && ("=+-@".indexOf(stripped.charAt(0)) >= 0
                        || value.charAt(0) == '\t'
                        || value.charAt(0) == '\r'
                        || value.charAt(0) == '\n')) value = "'" + value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode study data", error);
        }
    }

    public static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.usability_invalid", "PROTOTYPE_HUB_USABILITY_INVALID");
    }

    public static PrototypeHubException invalidAnswers() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.usability_answers_invalid",
                "PROTOTYPE_HUB_USABILITY_ANSWERS_INVALID");
    }

    public static PrototypeHubException incomplete() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.usability_incomplete", "PROTOTYPE_HUB_USABILITY_INCOMPLETE");
    }

    public static PrototypeHubException limit() {
        return new PrototypeHubException(
                "error.prototype-hub.usability_limit", "PROTOTYPE_HUB_USABILITY_LIMIT", 409);
    }
}
