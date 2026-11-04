package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/** 固定范围来自路径；只接收人工文本和CSS视口，不接收身份、原型路径或附件。 */
public final class ViewportCheckRequests {
    private ViewportCheckRequests() {}

    /** 仅约束本接口的JSON整数，避免小数截断或字符串强转；范围仍由字段校验负责。 */
    public static class IntegerValue extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return parser.getIntValue();
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
    }

    /** CAS必须保留客户端读取的精确整数，不能把小数并发版本截断。 */
    public static class LongValue extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return parser.getLongValue();
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }

    @Getter
    @Setter
    @Schema(description = "人工视口自测的幂等写入")
    public static class Mutation {
        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @Schema(description = "未知结果重试必须保留原请求键与完整输入")
        private String clientRequestId;
    }

    @Getter
    @Setter
    @Schema(description = "人工场景、观察与视口参数")
    public static class Fields extends Mutation {
        @NotNull
        @Min(240)
        @Max(3840)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "CSS视口宽度，240至3840 CSS像素")
        private Integer viewportWidth;

        @NotNull
        @Min(240)
        @Max(3840)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "CSS视口高度，240至3840 CSS像素")
        private Integer viewportHeight;

        @NotNull
        @Min(25)
        @Max(200)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "舞台显示缩放百分比，默认100；不是DPR或CSS视口缩放")
        private Integer viewportScale = 100;

        @NotBlank
        @Pattern(regexp = "normal|empty|loading|permission|boundary|error")
        @Schema(description = "人工场景类别的稳定小写标识")
        private String category;

        @NotBlank
        @Size(max = 120)
        @Schema(description = "人工场景标题，1至120字符")
        private String title;

        @Size(max = 2000)
        @Schema(description = "可选人工操作步骤，纯文本")
        private String steps;

        @NotBlank
        @Size(max = 2000)
        @Schema(description = "人工填写的预期结果，1至2000字符")
        private String expected;

        @Size(max = 2000)
        @Schema(description = "人工观察；通过或不通过时必填")
        private String observed;

        @NotBlank
        @Pattern(regexp = "untested|passed|failed")
        @Schema(description = "人工结论，默认untested，不自动推断")
        private String result = "untested";

        @Size(max = 2000)
        @Schema(description = "可选人工纯文本证据，不是附件或自动截图")
        private String evidence;

        @AssertTrue
        @JsonIgnore
        @Schema(hidden = true)
        public boolean isObservationComplete() {
            return !("passed".equals(result) || "failed".equals(result))
                    || StringUtils.hasText(observed);
        }
    }

    @Getter
    @Setter
    @Schema(description = "按读取到的CAS版本替换人工视口自测记录")
    public static class Update extends Fields {
        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = LongValue.class)
        @Schema(description = "读取到的并发版本")
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "CAS删除人工记录，不改变原型或其他记录")
    public static class Delete extends Mutation {
        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = LongValue.class)
        @Schema(description = "读取到的并发版本")
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "固定页面人工记录的数据库分页")
    public static class Query {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "从零计页")
        private int pageIndex = 0;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "每页数量，默认20")
        private int pageSize = 20;

        @Pattern(regexp = "normal|empty|loading|permission|boundary|error")
        @Schema(description = "可选场景类别筛选，省略查询全部")
        private String category;

        @Pattern(regexp = "untested|passed|failed")
        @Schema(description = "可选人工结论筛选，省略查询全部")
        private String result;
    }
}
