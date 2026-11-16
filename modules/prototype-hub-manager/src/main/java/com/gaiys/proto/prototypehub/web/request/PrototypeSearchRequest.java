package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 内部基础搜索的固定条件，不接受字段名、任意查询表达式或排序。 */
@Getter
@Setter
@Schema(description = "权限范围内的基础搜索")
public class PrototypeSearchRequest {
    public enum Type {
        COPY,
        MATERIAL,
        FEEDBACK
    }

    @NotNull
    @Schema(description = "已收录文案、资料元数据或评审消息")
    private Type type;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[^\\x00\\p{Cs}]*")
    @Schema(description = "去除首尾空白后的1到100字符字面关键词，不作为SQL或通配符")
    private String keyword;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    @Schema(description = "可选项目标识，仅收窄当前授权范围")
    private String projectId;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    @Schema(description = "可选逻辑版本标识")
    private String versionId;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    @Schema(description = "可选固定修订标识")
    private String revisionId;

    @NotNull
    @Min(0)
    @Max(10000)
    @JsonDeserialize(using = ViewportCheckRequests.IntegerValue.class)
    @Schema(description = "从零计页，默认0")
    private Integer pageIndex = 0;

    @NotNull
    @Min(1)
    @Max(50)
    @JsonDeserialize(using = ViewportCheckRequests.IntegerValue.class)
    @Schema(description = "每页1到50项，默认20")
    private Integer pageSize = 20;

    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? null : keyword.strip();
    }
}
