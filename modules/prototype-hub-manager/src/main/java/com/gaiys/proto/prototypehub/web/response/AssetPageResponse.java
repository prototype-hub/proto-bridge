package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import java.util.List;

/** 当前固定修订的原始图片/SVG清单；total为筛选后数量，assetCount为全部资产数量。 */
public record AssetPageResponse(
        String projectId,
        String versionId,
        String revisionId,
        String manifestSha256,
        int pageIndex,
        int pageSize,
        long total,
        long totalBytes,
        long assetCount,
        long assetBytes,
        List<FileEntry> data) {}
