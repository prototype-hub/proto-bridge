package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityResponseEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityStudyEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.validation.UsabilityStudyValidator;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.*;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HTTP精确标量、定义冻结前校验和匿名白名单独立于数据库验证。 */
class UsabilityStudyRequestsTest {
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final UsabilityStudyValidator validator = new UsabilityStudyValidator(mapper);

    @Test
    void exactRequestsAndNestedRecordsRejectUnknownFieldsAndScalarCoercion() {
        for (Class<?> type :
                List.of(
                        UsabilityStudyRequests.Fields.class,
                        UsabilityStudyRequests.Update.class,
                        UsabilityStudyRequests.Mutation.class,
                        UsabilityStudyRequests.Page.class,
                        UsabilityStudyRequests.PublicGrant.class,
                        UsabilityStudyRequests.PublicQuery.class,
                        UsabilityStudyRequests.Consent.class,
                        UsabilityStudyRequests.Progress.class,
                        UsabilityStudyRequests.Withdraw.class)) {
            for (String field : List.of("actor", "guestId", "state", "submittedAt", "shareId"))
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"" + field + "\":null}", type));
        }
        for (String value : List.of("1", "true", "{}", "[]")) {
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"title\":" + value + "}",
                                    UsabilityStudyRequests.Fields.class));
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"accessKey\":" + value + "}",
                                    UsabilityStudyRequests.PublicGrant.class));
        }
        for (String value : List.of("\"true\"", "0", "1", "{}", "[]"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"agreed\":" + value + "}",
                                    UsabilityStudyRequests.Consent.class));
        for (String value : List.of("\"1\"", "1.5", "true", "9223372036854775808"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + value + "}",
                                    UsabilityStudyRequests.Progress.class));
        for (String value : List.of("\"1\"", "1.5", "null", "2147483648"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"pageSize\":" + value + "}",
                                    UsabilityStudyRequests.Page.class));
        for (String value : List.of("\"1\"", "1.5", "true", "0", "6"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"questionId\":\"r\",\"rating\":" + value + "}",
                                    Answer.class));
        for (String source :
                List.of(
                        "{\"id\":\"q\",\"prompt\":\"Q\",\"type\":\"rating\",\"required\":\"true\",\"options\":[]}",
                        "{\"id\":\"q\",\"prompt\":\"Q\",\"type\":\"rating\",\"required\":true,\"options\":[],\"hidden\":null}",
                        "{\"id\":\"q\",\"prompt\":\"Q\",\"type\":\"rating\",\"required\":true,\"options\":[{\"id\":\"a\",\"label\":\"A\"}]}",
                        "{\"id\":\"q\",\"prompt\":\"Q\",\"type\":\"singleChoice\",\"required\":true,\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"a\",\"label\":\"B\"}]}"))
            assertThrows(
                    JsonProcessingException.class, () -> mapper.readValue(source, Question.class));
        assertThrows(
                JsonProcessingException.class,
                () ->
                        mapper.readValue(
                                "{\"taskId\":\"t\",\"outcome\":\"automatic\"}", TaskAnswer.class));
    }

    @Test
    void draftCanBeIncompleteButOpeningAndSubmissionEnforceTheFrozenDefinition() throws Exception {
        var fields = new UsabilityStudyRequests.Fields();
        fields.setTitle("Research");
        fields.setConsentText("I agree");
        fields.setClientRequestId("key");
        var definition = validator.definition(fields);
        var study = study();
        study.setTasks(List.of());
        study.setQuestions(List.of());
        assertThrows(PrototypeHubException.class, () -> validator.opening(study));
        fields.setTasks(List.of(task("t", List.of())));
        validator.definition(fields);
        study.setTasks(fields.getTasks());
        assertThrows(PrototypeHubException.class, () -> validator.opening(study));
        study.setTasks(List.of(task("t", List.of(new DemoScriptStep("s", "page", "")))));
        study.setQuestions(questions());
        validator.opening(study);
        var progress = new UsabilityStudyRequests.Progress();
        assertTrue(validator.answers(study, progress, false).answers().isEmpty());
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, true));
        progress.setTaskAnswers(List.of(new TaskAnswer("t", "skipped", "")));
        progress.setAnswers(
                List.of(
                        new Answer("single", "a", null, null),
                        new Answer("rating", null, 4, null),
                        new Answer("text", null, null, "\u00a0 \ufeff")));
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, true));
        progress.setAnswers(
                List.of(
                        new Answer("single", "a", null, null),
                        new Answer("rating", null, 4, null),
                        new Answer("text", null, null, "Explicit reply")));
        assertEquals(3, validator.answers(study, progress, true).answers().size());
        progress.setAnswers(List.of(new Answer("single", "wrong", null, null)));
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, false));
        progress.setAnswers(List.of(new Answer("single", "a", 3, null)));
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, false));
        progress.setAnswers(
                List.of(new Answer("rating", null, 1, null), new Answer("rating", null, 2, null)));
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, false));
        progress.setTaskAnswers(List.of(new TaskAnswer("foreign", "completed", "")));
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, false));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var consent = new UsabilityStudyRequests.Consent();
            consent.setRevisionId("r");
            consent.setAccessKey("a");
            consent.setContentVersion(1L);
            consent.setClientRequestId("k");
            consent.setAgreed(false);
            assertFalse(factory.getValidator().validate(consent).isEmpty());
            consent.setAgreed(true);
            assertTrue(factory.getValidator().validate(consent).isEmpty());
        }
        fields.setTasks(List.of(task("same", List.of()), task("same", List.of())));
        assertThrows(PrototypeHubException.class, () -> validator.definition(fields));
        var many = new ArrayList<Task>();
        for (int t = 0; t < 5; t++) {
            var steps = new ArrayList<DemoScriptStep>();
            for (int s = 0; s < 10; s++) steps.add(new DemoScriptStep("s" + s, "page", ""));
            many.add(task("t" + t, steps));
        }
        fields.setTasks(many);
        assertThrows(PrototypeHubException.class, () -> validator.definition(fields));
    }

    @Test
    void serializedDefinitionAndAnswerCapsMeasureUtf8Bytes() {
        var fields = new UsabilityStudyRequests.Fields();
        fields.setTitle("Research");
        fields.setConsentText("Consent");
        fields.setClientRequestId("key");
        var tasks = new ArrayList<Task>();
        for (int t = 0; t < 12; t++) {
            var steps = new ArrayList<DemoScriptStep>();
            for (int i = 0; i < 4; i++)
                steps.add(new DemoScriptStep("s" + i, "page", "界".repeat(2000)));
            tasks.add(task("t" + t, steps));
        }
        fields.setTasks(tasks);
        assertThrows(PrototypeHubException.class, () -> validator.definition(fields));
        var study = study();
        study.setTasks(tasks);
        study.setQuestions(List.of());
        var progress = new UsabilityStudyRequests.Progress();
        var answers = new ArrayList<TaskAnswer>();
        for (int t = 0; t < 12; t++)
            answers.add(new TaskAnswer("t" + t, "completed", "界".repeat(2000)));
        progress.setTaskAnswers(answers);
        assertThrows(PrototypeHubException.class, () -> validator.answers(study, progress, false));
        progress.setTaskAnswers(List.of(new TaskAnswer("t0", "completed", "界".repeat(1000))));
        assertEquals(1, validator.answers(study, progress, false).taskAnswers().size());
    }

    @Test
    void aggregateAndCsvNeverCarryParticipantTextIdentityTimeOrOrdinalAndGuardFormulaLabels()
            throws Exception {
        var study = study();
        study.setTasks(List.of(task("t", List.of(new DemoScriptStep("s", "page", "")))));
        study.setQuestions(questions());
        var one = new PrototypeUsabilityResponseEntity();
        one.setId("private-id");
        one.setGuestId("private-guest");
        one.setFeedback("PRIVATE free feedback");
        one.setCreatedAt(1777777777777L);
        one.setTaskAnswers(List.of(new TaskAnswer("t", "completed", "PRIVATE task text")));
        one.setAnswers(
                List.of(
                        new Answer("single", "a", null, null),
                        new Answer("rating", null, 5, null),
                        new Answer("text", null, null, "PRIVATE answer text")));
        var two = new PrototypeUsabilityResponseEntity();
        two.setTaskAnswers(List.of(new TaskAnswer("t", "skipped", "")));
        two.setAnswers(List.of());
        var aggregate = UsabilityStudyValidator.aggregate(study, List.of(one, two));
        assertEquals(2, aggregate.submittedCount());
        assertEquals(1, aggregate.tasks().getFirst().completed());
        assertEquals(1, aggregate.tasks().getFirst().skipped());
        assertEquals(1, aggregate.questions().getFirst().missing());
        assertEquals(1, aggregate.excludedTextQuestionCount());
        var json = mapper.writeValueAsString(aggregate);
        var csv = new String(UsabilityStudyValidator.csv(aggregate), StandardCharsets.UTF_8);
        for (String secret :
                List.of(
                        "PRIVATE",
                        "private-id",
                        "private-guest",
                        "1777777777777",
                        "createdAt",
                        "submittedAt",
                        "responseId",
                        "guestId")) {
            assertFalse(json.contains(secret));
            assertFalse(csv.contains(secret));
        }
        assertTrue(csv.contains("\"'=Research\""));
        assertTrue(csv.contains("\"' +choice\""));
        assertTrue(csv.contains("\"'\u00a0\ufeff=SUM(1,2)\""));
        assertEquals(4, mapper.readTree(json).size());
        assertThrows(
                PrototypeHubException.class,
                () ->
                        UsabilityStudyValidator.aggregate(
                                study, java.util.Collections.nCopies(201, one)));
    }

    private static Task task(String id, List<DemoScriptStep> steps) {
        return new Task(id, "=Research", "", "", "", steps);
    }

    private static List<Question> questions() {
        return List.of(
                new Question(
                        "single",
                        "Question",
                        "singleChoice",
                        true,
                        List.of(
                                new Option("a", " +choice"),
                                new Option("b", "\u00a0\ufeff=SUM(1,2)"))),
                new Question("rating", "Rating", "rating", true, List.of()),
                new Question("text", "Private feedback", "text", true, List.of()));
    }

    private static PrototypeUsabilityStudyEntity study() {
        var study = new PrototypeUsabilityStudyEntity();
        study.setTitle("Research");
        study.setConsentText("I agree");
        study.setIntroduction("");
        return study;
    }
}
