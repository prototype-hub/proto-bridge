package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

/** 线程状态CAS；客户端请求键支持同语义结果未知时重试。 */
@Getter
@Setter
@Schema(description = "线程状态CAS；客户端请求键支持同语义结果未知时重试。")
public class ReviewThreadStateRequest extends ReviewPageRequest {
    @NotBlank
    @Pattern(regexp = "open|resolved")
    @Schema(description = "目标状态，公开端仅允许open")
    private String state;

    @NotNull
    @PositiveOrZero
    @Schema(description = "读取到的状态并发版本")
    private Long expectedVersion;

    @NotBlank
    @Pattern(regexp = "[!-~]{1,128}")
    @Schema(description = "强制客户端幂等请求键")
    private String clientRequestId;
}
