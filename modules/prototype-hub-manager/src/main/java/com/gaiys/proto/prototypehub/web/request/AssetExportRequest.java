package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 导出只接收已列出清单的精确路径；全部选择与查询筛选无关，不接受外部URL。 */
@Getter
@Setter
@Schema(description = "固定修订原始资产导出")
public class AssetExportRequest {
    @NotNull
    @Pattern(regexp = "[a-f0-9]{64}")
    private String expectedManifestSha256;

    private boolean all;

    @NotNull
    @Size(max = 20000)
    private List<@NotNull @Size(min = 1, max = 1024) String> paths;
}
