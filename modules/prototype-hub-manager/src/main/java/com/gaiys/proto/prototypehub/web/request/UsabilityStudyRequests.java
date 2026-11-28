package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Answer;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Question;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Task;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.TaskAnswer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 有界研究输入；身份、固定范围、状态及时间不接受客户端覆盖。 */
public final class UsabilityStudyRequests {
    private UsabilityStudyRequests() {}

    public abstract static class Exact {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown usability study request field");
        }
    }

    @Getter
    @Setter
    public static class Page extends Exact {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageIndex;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageSize = 20;
    }

    @Getter
    @Setter
    public static class Fields extends Exact {
        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String title;

        @Size(max = 4000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String introduction = "";

        @NotBlank
        @Size(max = 4000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String consentText;

        @NotNull
        @Size(max = 12)
        @Valid
        private List<@NotNull Task> tasks = List.of();

        @NotNull
        @Size(max = 8)
        @Valid
        private List<@NotNull Question> questions = List.of();

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    public static class Update extends Fields {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    public static class Mutation extends Exact {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    public static class PublicGrant extends Exact {
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String revisionId;

        @NotBlank
        @Size(max = 256)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String accessKey;
    }

    @Getter
    @Setter
    public static class PublicQuery extends PublicGrant {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageIndex;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageSize = 20;
    }

    @Getter
    @Setter
    public static class Consent extends PublicGrant {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long contentVersion;

        @NotNull
        @AssertTrue
        @JsonDeserialize(using = DemoScriptRequests.BooleanValue.class)
        private Boolean agreed;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    public static class Withdraw extends PublicGrant {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long contentVersion;

        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    public static class Progress extends Withdraw {
        @NotNull
        @Size(max = 12)
        @Valid
        private List<@NotNull TaskAnswer> taskAnswers = List.of();

        @NotNull
        @Size(max = 8)
        @Valid
        private List<@NotNull Answer> answers = List.of();

        @Size(max = 2000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String feedback = "";
    }
}
