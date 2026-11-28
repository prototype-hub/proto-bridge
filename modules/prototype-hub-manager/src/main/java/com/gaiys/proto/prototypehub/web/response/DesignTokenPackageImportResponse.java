package com.gaiys.proto.prototypehub.web.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 非空回执包络使已删除包的原键重试也不会重新创建。 */
public record DesignTokenPackageImportResponse(
        String id, String status, @JsonProperty("package") DesignTokenPackageResponse value) {}
