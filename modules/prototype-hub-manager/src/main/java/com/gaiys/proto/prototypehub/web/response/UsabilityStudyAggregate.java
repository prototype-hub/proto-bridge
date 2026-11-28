package com.gaiys.proto.prototypehub.web.response;

import java.util.List;

/** 匿名汇总的唯一显式白名单；不能从原始答卷序列化再删除身份列。 */
public record UsabilityStudyAggregate(
        long submittedCount,
        List<TaskCounts> tasks,
        List<QuestionCounts> questions,
        long excludedTextQuestionCount) {
    public record TaskCounts(
            String title, long completed, long notCompleted, long skipped, long missing) {}

    public record QuestionCounts(
            String prompt, String type, long answered, long missing, List<OptionCount> options) {}

    public record OptionCount(String label, long count) {}
}
