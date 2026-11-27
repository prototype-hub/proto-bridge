package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 演示脚本的精确输入；固定范围、身份和时间由服务端拥有，正文仅为普通文本。 */
public final class DemoScriptRequests {
    private DemoScriptRequests() {}

    public abstract static class Exact {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown demo script request field");
        }
    }

    /** 开放分享必须是显式JSON布尔值，不把字符串或数字当作用户决定。 */
    public static class BooleanValue extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_TRUE)) return true;
            if (parser.hasToken(JsonToken.VALUE_FALSE)) return false;
            return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
        }
    }

    @Getter
    @Setter
    @Schema(description = "固定修订演示脚本有界分页")
    public static class Page extends Exact {
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
    @Schema(description = "演示脚本完整替换字段，默认仅内部可读")
    public static class Fields extends Exact {
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
        private String objective;

        @Size(max = 2000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String prerequisites;

        @Size(max = 2000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String resetNote;

        @NotNull
        @Size(min = 1, max = 50)
        @Valid
        private List<@NotNull DemoScriptStep> steps;

        @NotNull
        @JsonDeserialize(using = BooleanValue.class)
        private Boolean shareEnabled = false;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    @Schema(description = "完整替换演示脚本的幂等CAS更新")
    public static class Update extends Fields {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "删除脚本并保留版本清理前的持久回执")
    public static class Delete extends Exact {
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
    @Schema(description = "现有分享授权的精确修订读取，不建立访客身份")
    public static class PublicContext extends Exact {
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
    @Schema(description = "现有分享授权的演示脚本分页")
    public static class PublicQuery extends PublicContext {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageIndex = 0;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageSize = 20;
    }
}
