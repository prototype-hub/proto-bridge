package com.gaiys.proto.prototypehub.web.request;

import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 仅初建线程可附位置快照，回复不会修改已有锚点。 */
@Getter
@Setter
@Schema(description = "创建页面线程，可选不可变根文档几何锚点")
public class ReviewThreadCreateRequest extends ReviewTextRequest {
    @Schema(description = "可选根文档整数CSS像素位置快照，缺省或空值表示无锚点")
    private ReviewAnchor anchor;
}
