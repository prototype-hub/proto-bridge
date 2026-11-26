package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 会话说明书只接收普通文本及真实来源引用；无数据库保存、调用者身份或文件路径。 */
public final class DeliveryDocumentRequests {
    public static final int MAX_TEXT_BYTES = 1024 * 1024;

    private DeliveryDocumentRequests() {}

    @Getter
    @Setter
    public static class SourceRef {
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[^\\x00]*")
        private String id;

        @NotNull
        @PositiveOrZero
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;
    }

    @Getter
    @Setter
    public static class MaterialRef extends SourceRef {
        private boolean includeText;
    }

    @Getter
    @Setter
    public static class ImageRef extends SourceRef {
        @Size(max = 500)
        @Pattern(regexp = "[^\\x00]*")
        private String caption = "";
    }

    @Getter
    @Setter
    public static class Page {
        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        private String pageId;

        @NotNull
        @Valid
        @Size(max = 200)
        private List<SourceRef> descriptions = new ArrayList<>();

        @NotNull
        @Valid
        @Size(max = 200)
        private List<MaterialRef> materials = new ArrayList<>();

        @NotNull
        @Valid
        @Size(max = 10)
        private List<ImageRef> images = new ArrayList<>();

        @NotNull
        @Valid
        @Size(max = 200)
        private List<SourceRef> checks = new ArrayList<>();

        @Size(max = 20000)
        @Pattern(regexp = "[^\\x00]*")
        private String body;

        @Pattern(regexp = "[a-f0-9]{64}")
        private String sourceFingerprint;
    }

    @Getter
    @Setter
    public static class Document {
        @NotNull
        @Pattern(regexp = "zh|en")
        private String locale = "zh";

        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = "[^\\x00]*")
        private String title;

        @Size(max = 4000)
        @Pattern(regexp = "[^\\x00]*")
        private String introduction = "";

        @NotNull
        @Valid
        @Size(min = 1, max = 50)
        private List<Page> pages = new ArrayList<>();

        /** HTTP跨字段限制的唯一边界；每次引用计入预算，不能借同源重复绕过上限。 */
        public void validate(boolean export) {
            int refs = 0, images = 0;
            long bytes = bytes(title) + bytes(introduction);
            var ids = new HashSet<String>();
            if (pages == null || pages.isEmpty() || pages.size() > 50) throw invalid();
            for (var page : pages) {
                if (page == null || !ids.add(page.pageId)) throw invalid();
                unique(page.descriptions);
                unique(page.materials);
                unique(page.images);
                unique(page.checks);
                refs += page.descriptions.size() + page.materials.size() + page.checks.size();
                images += page.images.size();
                if (export && (page.body == null || page.sourceFingerprint == null))
                    throw invalid();
                if (page.body != null && page.body.length() > 20000) throw limit();
                bytes += bytes(page.body);
                for (var image : page.images) bytes += bytes(image.caption);
            }
            if (refs > 200 || images > 10 || bytes > MAX_TEXT_BYTES) throw limit();
        }
    }

    private static void unique(List<? extends SourceRef> refs) {
        if (refs == null) throw invalid();
        var ids = new HashSet<String>();
        for (var ref : refs) if (ref == null || !ids.add(ref.getId())) throw invalid();
    }

    public static long bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static PrototypeHubException invalid() {
        return new PrototypeHubException(
                "error.prototype-hub.delivery_document_invalid",
                "PROTOTYPE_HUB_DELIVERY_DOCUMENT_INVALID",
                400);
    }

    public static PrototypeHubException limit() {
        return new PrototypeHubException(
                "error.prototype-hub.delivery_document_limit",
                "PROTOTYPE_HUB_DELIVERY_DOCUMENT_LIMIT",
                413);
    }

    public static PrototypeHubException changed() {
        return new PrototypeHubException(
                "error.prototype-hub.delivery_document_source_changed",
                "PROTOTYPE_HUB_DELIVERY_DOCUMENT_SOURCE_CHANGED",
                409);
    }
}
