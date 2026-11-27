package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** 代码卡只接收精确有界普通文本和同页说明指针，未知字段、标量强制转换与隐式覆盖均拒绝。 */
public final class CodeMappingRequests {
    private CodeMappingRequests() {}

    /** 不继承其他业务的允许字段，防止调用方注入范围、身份或来源正文。 */
    public abstract static class Exact {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown code mapping request field");
        }
    }

    /** 同页F27指针；响应的来源状态不能原样提交为写入字段。 */
    @Getter
    @Setter
    public static class Description extends Exact {
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String id;

        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    /** 七个字符串均必须显式提供；空的可选内容用空串，片段不作trim或解释。 */
    @Getter
    @Setter
    public static class Draft extends Exact {
        @NotNull
        @Size(min = 1, max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String alias;

        @NotNull
        @Size(min = 1, max = 2048)
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String repositoryPath;

        @NotNull
        @Size(max = 2048)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String repositoryUrl;

        @NotNull
        @Size(max = 2048)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String documentationUrl;

        @NotNull
        @Size(max = 16000)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String snippet;

        @NotNull
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String framework;

        @NotNull
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String frameworkVersion;

        // An omitted description fails nested validation; explicit null means no association.
        @Valid private Description description = new Description();
    }

    /** 固定页的稳定数据库分页；没有外部任意QueryParam。 */
    @Getter
    @Setter
    public static class Query extends Exact {
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String keyword;

        @NotNull
        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private Integer pageIndex = 0;

        @NotNull
        @Min(1)
        @Max(50)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private Integer pageSize = 20;
    }

    /** 新建卡；请求键也决定资源身份，未知结果只允许原payload重试。 */
    @Getter
    @Setter
    public static class Create extends Draft {
        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    /** 完整替换内容，卡版本独立于所关联说明版本。 */
    @Getter
    @Setter
    public static class Update extends Create {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    /** 仅删除当前卡；已有说明、仓库文件与回执不因此删除。 */
    @Getter
    @Setter
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

    /** 只读导出显式选中的当前页卡，任何缺失都会阻止整包下载。 */
    @Getter
    @Setter
    public static class Export extends Exact {
        @NotNull
        @Size(min = 1, max = 50)
        @JsonFormat(without = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        @JsonDeserialize(contentUsing = ApprovalRequests.Text.class)
        private List<@NotBlank @Size(max = 128) @Pattern(regexp = "[^\\x00]*") String> ids;
    }

    /** 文件预览后在目标页显式选取F27；所有卡与父回执单事务只新增。 */
    @Getter
    @Setter
    @Schema(description = "当前固定页只新增的有界JSON卡包")
    public static class Import extends Exact {
        @NotNull
        @Pattern(regexp = "prototype-hub-code-mapping-cards")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String format;

        @NotNull
        @Min(1)
        @Max(1)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private Integer schemaVersion;

        @NotNull
        @Size(min = 1, max = 50)
        @Valid
        @JsonFormat(without = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        private List<@NotNull @Valid Draft> cards;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }
}
