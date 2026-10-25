package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 成员专用分派CAS；空负责人表示清除，独立请求键不复用状态更新。 */
@Getter
@Setter
@Schema(description = "成员专用线程分派请求")
public class ReviewAssignmentRequest extends ReviewPageRequest {
    @Size(max = 64)
    @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{IsWhite_Space}].*")
    @Schema(description = "目标项目所有者或编辑者标识，null清除，空白非法")
    private String assigneeId;

    /** 仅该字段拒绝Jackson标量到字符串的隐式转换，防止数字被当成用户标识。 */
    public void setAssigneeId(Object value) {
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("assigneeId must be a string or null");
        this.assigneeId = (String) value;
    }

    @NotNull
    @PositiveOrZero
    @Schema(description = "读取到的独立分派版本")
    private Long expectedVersion;

    @NotBlank
    @Pattern(regexp = "[!-~]{1,128}")
    @Schema(description = "强制幂等请求键，重试保持目标和版本不变")
    private String clientRequestId;
}
