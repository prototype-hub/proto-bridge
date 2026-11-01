package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 固定页面清单的有界输入；不接收路径、条目来源、权限或客户端总数。 */
public final class CopyInventoryRequests {
    private CopyInventoryRequests() {}

    @Getter
    @Setter
    @Schema(description = "提取固定页面文案")
    public static class Mutation {
        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @Schema(description = "未知结果重试保留原请求键与输入")
        private String clientRequestId;
    }

    @Getter
    @Setter
    @Schema(description = "维护文案纯文本字段")
    public static class Fields extends Mutation {
        @Size(max = 2000)
        @Schema(description = "人工条目原文；静态条目必须省略")
        private String text;

        @Size(max = 200)
        @Schema(description = "业务词，可空")
        private String businessTerm;

        @Size(max = 2000)
        @Schema(description = "纯文本备注，可空")
        private String note;

        @Size(max = 2048)
        @Schema(description = "HTTP(S)需求链接，可空，不抓取")
        private String requirementUrl;
    }

    @Getter
    @Setter
    @Schema(description = "按CAS版本维护文案")
    public static class Update extends Fields {
        @NotNull
        @PositiveOrZero
        @Schema(description = "读取到的条目版本")
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "按CAS版本删除人工补充")
    public static class Delete extends Mutation {
        @NotNull
        @PositiveOrZero
        @Schema(description = "读取到的条目版本")
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "固定页文案数据库分页")
    public static class Query {
        @Min(0)
        @Max(10000)
        @Schema(description = "从零计页")
        private int pageIndex = 0;

        @Min(1)
        @Max(100)
        @Schema(description = "每页条目数")
        private int pageSize = 20;

        @Size(max = 100)
        @Schema(description = "原文、业务词和备注的大小写不敏感字面包含")
        private String keyword;

        @Pattern(regexp = "STATIC|MANUAL")
        @Schema(description = "来源筛选；省略查全部")
        private String kind;

        @Schema(description = "按是否有需求链接筛选；省略查全部")
        private Boolean hasRequirement;
    }

    @Getter
    @Setter
    @Schema(description = "导出固定页全部文案，忽略列表筛选")
    public static class Export {
        @NotNull
        @Pattern(regexp = "csv|json")
        @Schema(description = "csv或json")
        private String format;

        @NotNull
        @PositiveOrZero
        @Schema(description = "读取到的整页清单版本")
        private Long expectedVersion;
    }
}
