package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 成员专用内部责任快照；与公开线程投影和状态版本完全分离。 */
@Schema(description = "线程内部负责人")
public record ReviewAssignmentResponse(
        @Schema(description = "线程标识") String threadId,
        @Schema(description = "独立分派并发版本") long assignmentVersion,
        @Schema(description = "最近分派Unix毫秒，历史未分派为null") Long updatedAt,
        @Schema(description = "当前责任快照，未分派为null") Assignee assignee) {
    /** 可用性按当前账号和项目角色计算，不表示授予权限或自动重新分派。 */
    @Schema(description = "已分派责任快照")
    public record Assignee(
            @Schema(description = "用户标识") String userId,
            @Schema(description = "分派时姓名快照") String name,
            @Schema(description = "当前仍是有效项目所有者或编辑者") boolean available) {}
}
