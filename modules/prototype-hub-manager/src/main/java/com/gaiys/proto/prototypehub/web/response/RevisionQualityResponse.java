package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 固定修订的静态交付包报告；不包含磁盘根目录、凭据或动态业务正确性结论。 */
@Schema(description = "固定修订的有界静态交付包质量报告")
public record RevisionQualityResponse(
        @Schema(description = "逻辑版本标识") String versionId,
        @Schema(description = "本次检查的固定修订标识") String revisionId,
        @Schema(description = "发布时清单 SHA-256") String manifestSha256,
        @Schema(description = "包内入口相对路径") String entryFile,
        @Schema(description = "完整计数，包含未保留的问题") Summary summary,
        @Schema(description = "本次检查的固定资源预算") Limits limits,
        @Schema(description = "声明的本地静态检查是否完整；不代表动态功能或外部引用正确") boolean complete,
        @Schema(description = "按来源、代码、目标及引用稳定排序的有界问题列表") List<Finding> findings) {
    @Schema(description = "静态检查计数")
    public record Summary(
            @Schema(description = "清单文件数") int files,
            @Schema(description = "实际解析的 HTML/CSS 文档数") int scannedDocuments,
            @Schema(description = "实际解析的文档字节数") long scannedBytes,
            @Schema(description = "已处理的静态引用数，包含显式未检查类别") int checkedReferences,
            @Schema(description = "错误数量") int errors,
            @Schema(description = "警告数量") int warnings,
            @Schema(description = "去重后的问题总数") int findingsTotal,
            @Schema(description = "因结果上限省略的问题数") int omittedFindings,
            @Schema(description = "未读取或无法完整解析的文档数") int skippedDocuments,
            @Schema(description = "声明范围外的引用数量") UncheckedReferences uncheckedReferences) {}

    @Schema(description = "有界扫描预算，不支持客户端扩大")
    public record Limits(
            @Schema(description = "单文档最大字节数") int maxTextBytes,
            @Schema(description = "单次解析文档累计最大字节数") long maxTotalTextBytes,
            @Schema(description = "单次最多处理引用数") int maxReferences,
            @Schema(description = "最多保留问题数") int maxFindings) {}

    @Schema(description = "未核验的引用类别，均不会发起网络请求")
    public record UncheckedReferences(
            @Schema(description = "外部或协议相对引用") int external,
            @Schema(description = "站点根绝对地址") int rootAbsolute,
            @Schema(description = "纯片段或含片段的引用，片段目标不核验") int fragment,
            @Schema(description = "data、mailto、JavaScript 或其他非文件协议") int unsupportedScheme) {}

    @Schema(description = "单个静态交付包问题，路径和引用均为纯文本")
    public record Finding(
            @Schema(description = "稳定问题代码，供客户端国际化") Code code,
            @Schema(description = "error 错误、warning 警告") Severity severity,
            @Schema(description = "来源包内路径") String sourcePath,
            @Schema(description = "解析后目标包内路径，无目标时为空") String targetPath,
            @Schema(description = "最多 1024 字符的原始静态引用，无引用时为空") String reference) {}

    @Schema(description = "稳定问题代码")
    public enum Code {
        entry_missing,
        file_missing,
        missing_resource,
        broken_internal_link,
        chinese_path,
        text_unreadable,
        document_limit,
        reference_limit,
        findings_limit,
        invalid_reference
    }

    @Schema(description = "问题级别")
    public enum Severity {
        error,
        warning
    }
}
