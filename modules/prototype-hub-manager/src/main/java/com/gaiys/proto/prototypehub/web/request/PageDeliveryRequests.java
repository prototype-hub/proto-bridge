package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** 内部成员的固定修订交付状态请求；不接收访客凭据、页面路径或调用者身份。 */
public final class PageDeliveryRequests {
    private PageDeliveryRequests() {}

    /** CAS整数不接受小数截断或字符串强制转换。 */
    public static class LongValue extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return parser.getLongValue();
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }

    /** 分页整数不接受小数、字符串或null强制转换。 */
    public static class IntegerValue extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return parser.getIntValue();
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }

        @Override
        public Integer getNullValue(DeserializationContext context) throws JsonMappingException {
            throw JsonMappingException.from(context.getParser(), "Pagination requires an integer");
        }
    }

    @Getter
    @Setter
    @Schema(description = "交付状态固定修订范围")
    public static class Scope {
        @NotBlank
        @Size(max = 128)
        @Schema(description = "固定修订标识")
        private String revisionId;
    }

    @Getter
    @Setter
    @Schema(description = "交付状态的有界分页")
    public static class Page extends Scope {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "零起始页号")
        private int pageIndex = 0;

        @Min(1)
        @Max(50)
        @JsonDeserialize(using = IntegerValue.class)
        @Schema(description = "每页一至五十项，默认二十")
        private int pageSize = 20;
    }

    @Getter
    @Setter
    @Schema(description = "只查询已设置交付状态的固定修订页面")
    public static class Query extends Page {
        @Pattern(regexp = "DRAFT|READY|IN_PROGRESS|DONE")
        @Schema(description = "可选单一人工状态，不传时返回全部已设置页面")
        private String state;
    }

    @Getter
    @Setter
    @Schema(description = "当前项目活跃成员负责人候选")
    public static class Candidates extends Page {
        @Size(max = 100)
        @Schema(description = "按姓名字面包含查找，不解释通配符")
        private String keyword;
    }

    @Getter
    @Setter
    @Schema(description = "固定页面人工交付状态的幂等CAS保存")
    public static class Save extends Scope {
        @NotBlank
        @Pattern(regexp = "DRAFT|READY|IN_PROGRESS|DONE")
        @Schema(description = "四种独立人工状态，不自动推断或转换")
        private String state;

        @Size(max = 64)
        @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{IsWhite_Space}].*")
        @Schema(description = "当前项目活跃所有者、编辑者或查看者，null清除")
        private String assigneeId;

        public void setAssigneeId(Object value) {
            if (value != null && !(value instanceof String))
                throw new IllegalArgumentException("assigneeId must be a string or null");
            this.assigneeId = (String) value;
        }

        @Size(max = 2000)
        @Schema(description = "可选纯文本理由，不执行HTML，空值规范为空串")
        private String reason;

        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = LongValue.class)
        @Schema(description = "读取到的精确CAS版本，未设置页面为零")
        private Long expectedVersion;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @Schema(description = "未知结果重试须保持原请求键及全部输入")
        private String clientRequestId;
    }
}
