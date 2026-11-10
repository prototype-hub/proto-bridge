package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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

/** 路径拥有资料范围；请求仅接收人工来源和并发/重试前置条件。 */
public final class RevisionMaterialRequests {
    private RevisionMaterialRequests() {}

    @Getter
    @Setter
    @Schema(description = "人工来源声明，不代表已验证的来源")
    public static class Source {
        @Size(max = 80)
        @Schema(description = "可选来源工具")
        private String sourceTool;

        @Size(max = 200)
        @Schema(description = "可选来源名称")
        private String sourceName;

        @Size(max = 2048)
        @Schema(description = "可选HTTP或HTTPS来源，服务端不抓取")
        private String sourceUrl;

        @Size(max = 2000)
        @Schema(description = "可选来源备注，纯文本")
        private String sourceNotes;

        public static Source of(String tool, String name, String url, String notes) {
            var source = new Source();
            source.setSourceTool(tool);
            source.setSourceName(name);
            source.setSourceUrl(url);
            source.setSourceNotes(notes);
            return source;
        }
    }

    @Getter
    @Setter
    @Schema(description = "为旧修订登记人工来源")
    public static class CreateSource extends Source {
        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @Schema(description = "未知结果重试保留原请求键和输入")
        private String clientRequestId;
    }

    @Getter
    @Setter
    @Schema(description = "CAS替换人工来源，不改变原始文件")
    public static class Update extends CreateSource {
        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = ViewportCheckRequests.LongValue.class)
        @Schema(description = "读取到的并发版本")
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "CAS删除资料，原型类型仅移除来源声明")
    public static class Delete {
        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @Schema(description = "未知结果重试保留原请求键")
        private String clientRequestId;

        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = ViewportCheckRequests.LongValue.class)
        @Schema(description = "读取到的并发版本")
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "固定修订资料数据库分页")
    public static class Query {
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = ViewportCheckRequests.IntegerValue.class)
        @Schema(description = "从零计页")
        private int pageIndex;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = ViewportCheckRequests.IntegerValue.class)
        @Schema(description = "每页数量，默认20")
        private int pageSize = 20;

        @Pattern(regexp = "prototype|image|document")
        @Schema(description = "可选资料类别筛选")
        private String materialType;
    }
}
