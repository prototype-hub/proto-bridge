package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 路径拥有固定修订范围；来源CAS沿用资料的同一输入边界。 */
public final class DesignTokenPackageRequests {
    private DesignTokenPackageRequests() {}

    @Getter
    @Setter
    public static class Query {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = ViewportCheckRequests.IntegerValue.class)
        private int pageIndex;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = ViewportCheckRequests.IntegerValue.class)
        private int pageSize = 20;
    }

    @Getter
    @Setter
    public static class Document {
        // null/省略选择初始视图；显式空Map仍是严格的上下文输入。
        @JsonDeserialize(contentUsing = ContextValue.class)
        private Map<String, String> inputs;
    }

    /** Resolver上下文必须是JSON字符串；局部拒绝Jackson数字/布尔强转与空值，不改变全局Mapper。 */
    public static class ContextValue extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
            return (String) context.handleUnexpectedToken(String.class, parser);
        }

        @Override
        public String getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(String.class, "Context input must be a string");
        }
    }
}
