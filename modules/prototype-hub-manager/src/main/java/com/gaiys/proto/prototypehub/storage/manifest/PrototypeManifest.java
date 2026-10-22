package com.gaiys.proto.prototypehub.storage.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.util.List;

/** 固定字段顺序的文件清单；页面身份由外部 revisionId 和清单中的 pageId 共同确定。 */
@JsonPropertyOrder({"formatVersion", "entryFile", "files", "pages"})
public record PrototypeManifest(
        int formatVersion, String entryFile, List<FileEntry> files, List<PrototypePageInfo> pages) {
    public PrototypeManifest {
        files = List.copyOf(files);
        pages = List.copyOf(pages);
    }

    @JsonPropertyOrder({"path", "size", "sha256", "mediaType"})
    public record FileEntry(String path, long size, String sha256, String mediaType) {}
}
