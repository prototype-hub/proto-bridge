package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gaiys.proto.prototypehub.web.info.PageTraceSourceRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 固定修订页面追踪的精确输入；权限、身份、时间与固定范围由服务端拥有。 */
public final class PageTraceRequests {
    private PageTraceRequests() {}

    @Getter
    @Setter
    public static class Page extends DemoScriptRequests.Exact {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageIndex = 0;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageSize = 20;
    }

    @Getter
    @Setter
    public static class RelationQuery extends Page {
        @Size(max = 128)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String pageId;

        @NotNull
        @Pattern(regexp = "all|incoming|outgoing")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String direction = "all";

        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String keyword;
    }

    @Getter
    @Setter
    public static class SourceQuery extends Page {
        @NotNull
        @Pattern(regexp = "work-item|material|description|check")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String kind;
    }

    @Getter
    @Setter
    public static class Key extends DemoScriptRequests.Exact {
        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    public static class RelationFields extends Key {
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String fromPageId;

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String toPageId;

        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String label;

        @Size(max = 2000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String note;
    }

    @Getter
    @Setter
    public static class RelationUpdate extends RelationFields {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    public static class Delete extends Key {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    public static class ContextUpdate extends Key {
        @NotNull
        @JsonDeserialize(using = DemoScriptRequests.BooleanValue.class)
        private Boolean entry;

        @NotNull
        @Size(max = 40)
        @Valid
        private List<@NotNull PageTraceSourceRef> references;

        @NotNull
        @Min(-1)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    public static class DecisionFields extends Key {
        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String title;

        @NotBlank
        @Size(max = 2000)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String decision;

        @NotBlank
        @Size(max = 2000)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String rationale;
    }

    @Getter
    @Setter
    public static class DecisionUpdate extends DecisionFields {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }
}
