package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 手工关联外部工作项，未知结果重试须保留原始输入和请求键。 */
@Getter
@Setter
@Schema(description = "新增工作项关联")
public class WorkItemCreateRequest extends WorkItemPageRequest {
    @NotBlank
    @Size(max = 2048)
    @Schema(description = "HTTP或HTTPS地址，禁止账号信息及控制字符")
    private String url;

    @Size(max = 2000)
    @Schema(description = "可选纯文本备注，空值保存为空串")
    private String note;

    @NotBlank
    @Pattern(regexp = "[!-~]{1,128}")
    @Schema(description = "强制幂等请求键，重试保留原输入")
    private String clientRequestId;
}
