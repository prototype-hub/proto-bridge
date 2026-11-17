package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上传成功回执视图；not_found仅说明没有成功回执，不代表请求没有在途。 */
@Schema(description = "版本替换上传结果")
public record UploadOperationResponse(
        @Schema(description = "succeeded或not_found") String status,
        @Schema(description = "成功逻辑版本标识，未找到为空") String versionId,
        @Schema(description = "本请求首次成功的固定修订标识，未找到为空") String revisionId,
        @Schema(description = "查询时当前真实修订标识，未找到为空") String currentRevisionId) {
    public static UploadOperationResponse notFound() {
        return new UploadOperationResponse("not_found", null, null, null);
    }
}
