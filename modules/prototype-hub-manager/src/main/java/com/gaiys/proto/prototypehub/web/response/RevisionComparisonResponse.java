package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hswebframework.web.api.crud.entity.PagerResult;

/** 保存清单的只读差异视图；不包含存储位置、预览凭据或视觉差异结论。 */
@Schema(description = "从左侧修订到右侧修订的页面及文件清单差异")
public record RevisionComparisonResponse(
        @Schema(description = "所属逻辑版本标识") String versionId,
        @Schema(description = "左侧固定修订元数据") RevisionResponse left,
        @Schema(description = "右侧固定修订元数据") RevisionResponse right,
        @Schema(description = "过滤及分页前的完整差异汇总") Summary summary,
        @Schema(description = "按路径升序的页面差异；总数受 includeUnchanged 影响")
                PagerResult<PageDifference> pages,
        @Schema(description = "按路径升序的文件差异；总数受 includeUnchanged 影响")
                PagerResult<FileDifference> files) {
    /** 状态方向为左到右；同路径的大小或哈希变化属于 changed，不推断重命名。 */
    @Schema(description = "差异状态，added 新增、removed 删除、changed 变化、unchanged 未变化")
    public enum Status {
        added,
        removed,
        changed,
        unchanged
    }

    /** 页面和文件独立统计，目录不计入页面。 */
    @Schema(description = "页面与文件完整汇总")
    public record Summary(
            @Schema(description = "页面差异计数") Counts pages,
            @Schema(description = "文件差异计数") Counts files) {}

    /** 完整清单的四种状态计数，不受分页及未变化项过滤影响。 */
    @Schema(description = "四种差异状态的数量")
    public record Counts(
            @Schema(description = "右侧新增数量") long added,
            @Schema(description = "右侧删除数量") long removed,
            @Schema(description = "同路径变化数量") long changed,
            @Schema(description = "同路径未变化数量") long unchanged) {}

    /** 相同路径的页面配对；只有一侧存在时另一侧为空。 */
    @Schema(description = "单个页面的清单差异")
    public record PageDifference(
            @Schema(description = "规范化相对路径，区分大小写") String path,
            @Schema(description = "名称或对应文件大小、哈希的差异状态") Status status,
            @Schema(description = "左侧页面，删除后右侧为空") PageSide left,
            @Schema(description = "右侧页面，新增前左侧为空") PageSide right) {}

    /** 保留该修订原始页面身份，合法旧清单没有 pageId 时仍返回空值。 */
    @Schema(description = "单侧页面定位信息")
    public record PageSide(
            @Schema(description = "修订内页面标识，旧清单允许为空") String pageId,
            @Schema(description = "相同路径第一个页面别名的名称") String name,
            @Schema(description = "页面规范化相对路径") String path) {}

    /** 同路径的已保存文件元数据；不读取或重新散列磁盘内容。 */
    @Schema(description = "单个文件的清单差异")
    public record FileDifference(
            @Schema(description = "规范化相对路径，区分大小写") String path,
            @Schema(description = "按大小或 SHA-256 判定的差异状态") Status status,
            @Schema(description = "左侧文件，新增前为空") FileSide left,
            @Schema(description = "右侧文件，删除后为空") FileSide right) {}

    /** 不暴露服务器存储路径，仅携带发布时保存的文件摘要。 */
    @Schema(description = "单侧文件元数据")
    public record FileSide(
            @Schema(description = "发布时文件字节数") long size,
            @Schema(description = "发布时文件 SHA-256") String sha256,
            @Schema(description = "发布时媒体类型") String mediaType) {}
}
