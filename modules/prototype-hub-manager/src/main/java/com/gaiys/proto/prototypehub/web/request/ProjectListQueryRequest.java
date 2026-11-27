package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Project-only filters; omitted archive retains the published all-nondeleted query. */
@Getter
@Setter
public class ProjectListQueryRequest extends PrototypeListQueryRequest {
    @JsonDeserialize(using = DemoScriptRequests.BooleanValue.class)
    private Boolean archived;

    @Size(max = 64)
    @Pattern(regexp = "[^\\x00]*")
    @JsonDeserialize(using = ApprovalRequests.Text.class)
    private String groupName;

    @Size(max = 32)
    @Pattern(regexp = "[^\\x00]+")
    @JsonDeserialize(using = ApprovalRequests.Text.class)
    private String tag;

    @JsonDeserialize(using = DemoScriptRequests.BooleanValue.class)
    private Boolean favoriteOnly = false;

    public void setGroupName(String value) {
        groupName = ProjectOrganizationRequests.normalize(value);
    }

    public void setTag(String value) {
        tag = ProjectOrganizationRequests.normalize(value);
    }

    @Override
    @JsonAnySetter
    public void unknown(String field, Object value) {
        if (!java.util.Set.of("total", "rePaging").contains(field))
            throw new IllegalArgumentException("Unknown project query field");
    }
}
