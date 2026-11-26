package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import com.gaiys.proto.prototypehub.web.info.ReviewElementTarget;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

/** 仅初建线程可附一种不可变位置快照，回复不会修改已有位置或固定页面归属。 */
@Getter
@Setter
@Schema(description = "创建页面线程，可选根文档几何或DOM元素位置")
public class ReviewThreadCreateRequest extends ReviewTextRequest {
    @Schema(description = "可选根文档整数CSS像素位置快照，缺省或空值表示无锚点")
    private ReviewAnchor anchor;

    @Schema(description = "可选固定页DOM元素位置，与anchor互斥")
    private ReviewElementTarget elementTarget;

    @AssertTrue(message = "{error.prototype-hub.review_position_exclusive}")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isPositionExclusive() {
        return anchor == null || elementTarget == null;
    }
}
