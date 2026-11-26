package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
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

/** 人工说明只接收固定修订/页和普通文本，不接收调用者身份、文件路径或访客凭据；PG文本不支持的NUL在HTTP边界拒绝。 */
public final class ElementDescriptionRequests {
    private ElementDescriptionRequests() {}

    @Getter
    @Setter
    @Schema(description = "人工说明的固定页面范围")
    public static class Scope {
        @NotBlank
        @Size(max = 128)
        @Schema(description = "固定修订标识")
        @Pattern(regexp = "[^\\x00]*")
        private String revisionId;

        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        @Schema(description = "不可变清单中的叶子页面标识")
        private String pageId;
    }

    @Getter
    @Setter
    @Schema(description = "按标题字面包含筛选当前页说明")
    public static class Query extends Scope {
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        private String keyword;

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
    @Schema(description = "新建人工功能说明，空的可选文本规范为空串")
    public static class Create extends Scope {
        @NotBlank
        @Size(max = 120)
        @Schema(description = "对象名称，普通文本")
        @Pattern(regexp = "[^\\x00]*")
        @Pattern(regexp = "(?s).*[^\\s\\p{Z}\\uFEFF].*")
        private String title;

        @Size(max = 2000)
        @Schema(description = "对象用途，普通文本")
        @Pattern(regexp = "[^\\x00]*")
        private String purpose;

        @Size(max = 2000)
        @Schema(description = "输入规则，普通文本")
        @Pattern(regexp = "[^\\x00]*")
        private String inputRules;

        @Size(max = 2000)
        @Schema(description = "权限规则，普通文本")
        @Pattern(regexp = "[^\\x00]*")
        private String permissionRules;

        @Size(max = 2000)
        @Schema(description = "跳转规则，普通文本")
        @Pattern(regexp = "[^\\x00]*")
        private String navigationRules;

        @Size(max = 4000)
        @Schema(description = "业务备注，普通文本")
        @Pattern(regexp = "[^\\x00]*")
        private String notes;

        @Schema(description = "可选静态根文档几何快照")
        private ReviewAnchor anchor;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @Schema(description = "未知结果必须携原键及完整payload重试")
        private String clientRequestId;
    }

    @Getter
    @Setter
    @Schema(description = "说明更新的完整替换与CAS")
    public static class Update extends Create {
        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    @Schema(description = "仅删除当前固定页说明，保留幂等回执")
    public static class Delete extends Scope {
        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        private String clientRequestId;
    }
}
