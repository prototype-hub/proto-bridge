package com.gaiys.proto.prototypehub.enums;

import com.gaiys.proto.prototypehub.web.response.ProjectCapabilities;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.Optional;

/** 稳定的项目内角色标识；全局管理员角色仅为响应视图，不持久化为项目成员。 */
@Schema(description = "项目范围角色及能力，不代替 hsweb 通用动作授权")
public enum ProjectRole {
    owner(true, true, true),
    editor(true, true, false),
    viewer(true, false, false),
    admin(true, true, true);

    private final boolean read;
    private final boolean edit;
    private final boolean ownership;

    ProjectRole(boolean read, boolean edit, boolean ownership) {
        this.read = read;
        this.edit = edit;
        this.ownership = ownership;
    }

    public ProjectCapabilities capabilities() {
        return new ProjectCapabilities(read, edit, ownership, ownership, edit);
    }

    public static Optional<ProjectRole> of(String value) {
        return Arrays.stream(values()).filter(role -> role.name().equals(value)).findFirst();
    }
}
