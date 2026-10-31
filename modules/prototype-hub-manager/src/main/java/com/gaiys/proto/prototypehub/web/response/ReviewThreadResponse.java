package com.gaiys.proto.prototypehub.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import io.swagger.v3.oas.annotations.media.Schema;

/** 页面评审的显式投影视图，不公开作者ID、邮箱或凭据。 */
@Schema(description = "页面评审投影视图")
public record ReviewThreadResponse(
        @Schema(description = "线程标识") String id,
        @Schema(description = "固定修订标识") String revisionId,
        @Schema(description = "稳定页面标识") String pageId,
        @Schema(description = "服务器清单页面名称") String pageName,
        @Schema(description = "服务器清单页面路径") String pagePath,
        @Schema(description = "首条公开正文摘要") String summary,
        @Schema(description = "首次访客自述姓名") String authorName,
        @Schema(description = "首次访客自述公司") String authorCompany,
        @Schema(description = "作者身份等级：email或unverified") String verificationLevel,
        @Schema(description = "open或resolved") String state,
        @Schema(description = "仅状态变更递增的并发版本") long configVersion,
        @Schema(description = "创建Unix毫秒") long createdAt,
        @Schema(description = "最近公开更新Unix毫秒，不含内部备注") long updatedAt,
        @Schema(description = "不可变根文档CSS像素位置快照，历史或未指定时为空") ReviewAnchor anchor,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "当前访客是否可显式重开；成员省略")
                Boolean canReopen,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "访客访问来源：own或associated；成员省略")
                String accessMode) {}
