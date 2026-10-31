package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 只接收已授权历史线程作为来源；访客标识及固定范围由服务端推导。 */
@Getter
@Setter
public class ReviewAssociationContextRequest {
    @NotBlank
    @Size(max = 64)
    @Schema(description = "历史未验证访客的线程标识")
    private String sourceThreadId;

    public void setSourceThreadId(Object value) {
        if (!(value instanceof String))
            throw new IllegalArgumentException("sourceThreadId must be a string");
        sourceThreadId = (String) value;
    }
}
