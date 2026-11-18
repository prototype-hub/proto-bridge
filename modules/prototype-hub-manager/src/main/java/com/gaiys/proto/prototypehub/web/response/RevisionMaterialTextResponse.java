package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 当前获授权资料的有界派生纯文本；不返回文件路径、HTML或任何访问凭据。 */
@Schema(description = "固定修订资料正文及实际提取状态")
public record RevisionMaterialTextResponse(
        @Schema(description = "当前资料标识") String id,
        @Schema(
                        description =
                                "NOT_EXTRACTED、READY、EMPTY、UNSUPPORTED、ENCRYPTED、LIMIT_EXCEEDED或FAILED")
                String status,
        @Schema(description = "READY正文；其他状态为空串，不包含部分提取内容") String content,
        @Schema(description = "成功提取正文的Unicode码点数量") int characterCount,
        @Schema(description = "正文提取完成Unix毫秒；未提取为空") Long extractedAt,
        @Schema(description = "完成提取的规则版本；未提取为空") String extractorVersion) {
    public static String status(PrototypeRevisionMaterialEntity row) {
        if (!"document".equals(row.getMaterialType())) return "UNSUPPORTED";
        return row.getTextStatus() == null ? "NOT_EXTRACTED" : row.getTextStatus();
    }

    public static RevisionMaterialTextResponse of(PrototypeRevisionMaterialEntity row) {
        String state = status(row);
        return new RevisionMaterialTextResponse(
                row.getId(),
                state,
                "READY".equals(state) ? row.getTextContent() : "",
                row.getTextCharacterCount() == null ? 0 : row.getTextCharacterCount(),
                row.getTextExtractedAt(),
                row.getTextExtractorVersion());
    }
}
