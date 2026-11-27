package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import lombok.Getter;
import lombok.Setter;

/** 正式决策的精确输入；身份、范围、内容摘要和时间均由服务端确定，未知字段拒绝。 */
public final class ApprovalRequests {
    private ApprovalRequests() {}

    /** 字符串不接受数字、布尔或复合值的隐式强制转换。 */
    public static class Text extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
    }

    /** 即使全局允许未知属性，正式决策也不能默默忽略客户端注入的身份或范围。 */
    public abstract static class Exact {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown approval request field");
        }
    }

    /** 一个固定修订的显式幂等CAS动作；备注始终作为纯文本处理。 */
    @Getter
    @Setter
    @Schema(description = "固定修订批准与发布动作")
    public static class Act extends Exact {
        @NotBlank
        @Pattern(regexp = "approve|revoke|publish|withdraw")
        @JsonDeserialize(using = Text.class)
        private String action;

        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = Text.class)
        private String clientRequestId;

        @Size(max = 2000)
        @JsonDeserialize(using = Text.class)
        private String note;
    }

    /** 固定修订内的有界历史，排序与范围由服务端固定。 */
    @Getter
    @Setter
    @Schema(description = "正式决策历史分页")
    public static class History extends Exact {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageIndex = 0;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageSize = 20;
    }

    /** 分享已获得的读取凭据，不建立评审身份或授予批准能力。 */
    @Getter
    @Setter
    @Schema(description = "公开分享固定修订正式状态读取")
    public static class PublicContext extends Exact {
        @NotBlank
        @Size(max = 128)
        @JsonDeserialize(using = Text.class)
        private String revisionId;

        @NotBlank
        @Size(max = 256)
        @JsonDeserialize(using = Text.class)
        private String accessKey;
    }
}
