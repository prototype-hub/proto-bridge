package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 内部成员显式选择对客回复或内部备注。 */
@Getter
@Setter
@Schema(description = "内部成员显式选择对客回复或内部备注。")
public class ReviewMemberTextRequest extends ReviewTextRequest {
    @NotNull
    @Pattern(regexp = "public|internal")
    @Schema(description = "public对客回复或internal内部备注")
    private String visibility;
}
