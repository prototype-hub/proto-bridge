package com.gaiys.proto.prototypehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests;
import com.gaiys.proto.prototypehub.web.response.DeliveryDocumentResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 无状态普通文本投影及离线导出；由FileWork调用，绝不解析HTML/Markdown、抓取URL或生成原型截图。 */
@Component
@RequiredArgsConstructor
public class DeliveryDocumentRenderer {
    public static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;
    public static final String CSP =
            "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'";
    private final ObjectMapper json;

    /** 所有实体均由服务端固定范围查询，request只提供选择及普通文本，不包含可信来源字段。 */
    public record Sources(
            PrototypePageInfo page,
            DeliveryDocumentRequests.Page request,
            List<PrototypeElementDescriptionEntity> descriptions,
            List<PrototypeRevisionMaterialEntity> materials,
            List<PrototypeRevisionMaterialEntity> images,
            List<PrototypeViewportCheckEntity> checks) {}

    public DeliveryDocumentResponse.Page page(
            String project,
            String version,
            String revision,
            String locale,
            Sources source,
            boolean export)
            throws Exception {
        String fingerprint = fingerprint(project, version, revision, locale, source);
        if (export && !fingerprint.equals(source.request().getSourceFingerprint()))
            throw DeliveryDocumentRequests.changed();
        String body = export ? source.request().getBody() : body(locale, source);
        if (body.length() > 20000) throw DeliveryDocumentRequests.limit();
        var req = source.request();
        return new DeliveryDocumentResponse.Page(
                source.page().pageId(),
                source.page().name(),
                source.page().path(),
                body,
                fingerprint,
                req.getDescriptions(),
                req.getMaterials(),
                req.getImages(),
                req.getChecks());
    }

    private String fingerprint(
            String project, String version, String revision, String locale, Sources source)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // 包含F13派生正文及提取元数据；它们可能更新而来源configVersion不变，不能仅比较CAS版本。
        try (var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            json.writeValue(
                    out,
                    Arrays.asList(
                            project,
                            version,
                            revision,
                            locale,
                            source.page(),
                            source.request().getDescriptions(),
                            source.request().getMaterials(),
                            source.request().getImages(),
                            source.request().getChecks(),
                            source.descriptions(),
                            source.materials(),
                            source.images(),
                            source.checks()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String body(String locale, Sources source) {
        boolean zh = "zh".equals(locale);
        var text = new StringBuilder();
        for (var row : source.descriptions()) {
            section(text, row.getTitle());
            field(text, zh ? "用途" : "Purpose", row.getPurpose());
            field(text, zh ? "输入规则" : "Input rules", row.getInputRules());
            field(text, zh ? "权限规则" : "Permission rules", row.getPermissionRules());
            field(text, zh ? "跳转规则" : "Navigation rules", row.getNavigationRules());
            field(text, zh ? "备注" : "Notes", row.getNotes());
        }
        for (int n = 0; n < source.materials().size(); n++) {
            var row = source.materials().get(n);
            section(text, (zh ? "资料：" : "Material: ") + materialName(row));
            field(text, zh ? "来源工具" : "Source tool", row.getSourceTool());
            field(text, zh ? "来源名称" : "Source name", row.getSourceName());
            field(text, zh ? "来源地址" : "Source URL", row.getSourceUrl());
            field(text, zh ? "来源备注" : "Source notes", row.getSourceNotes());
            if (source.request().getMaterials().get(n).isIncludeText())
                field(text, zh ? "已提取正文" : "Existing extracted text", row.getTextContent());
        }
        for (var row : source.checks()) {
            section(text, (zh ? "人工自测：" : "Manual check: ") + row.getTitle());
            field(
                    text,
                    zh ? "视口" : "Viewport",
                    row.getViewportWidth()
                            + " × "
                            + row.getViewportHeight()
                            + " CSS px, "
                            + row.getViewportScale()
                            + "%");
            field(text, zh ? "场景" : "Category", row.getCategory());
            field(text, zh ? "步骤" : "Steps", row.getSteps());
            field(text, zh ? "预期" : "Expected", row.getExpected());
            field(text, zh ? "观察" : "Observed", row.getObserved());
            field(text, zh ? "人工结论" : "Manual result", row.getResult());
            field(text, zh ? "人工证据" : "Manual evidence", row.getEvidence());
        }
        return text.toString().strip();
    }

    private static void section(StringBuilder text, String title) {
        if (!text.isEmpty()) text.append("\n\n");
        text.append(title == null ? "" : title).append('\n');
    }

    private static void field(StringBuilder text, String label, String value) {
        if (value != null && !value.isEmpty())
            text.append(label).append(": ").append(value).append('\n');
    }

    private static String materialName(PrototypeRevisionMaterialEntity row) {
        return "link".equals(row.getMaterialType()) ? row.getSourceName() : row.getFileName();
    }

    public void checkTextBudget(DeliveryDocumentResponse doc) {
        long bytes =
                DeliveryDocumentRequests.bytes(doc.title())
                        + DeliveryDocumentRequests.bytes(doc.introduction());
        for (var page : doc.pages()) {
            bytes +=
                    DeliveryDocumentRequests.bytes(page.name())
                            + DeliveryDocumentRequests.bytes(page.path())
                            + DeliveryDocumentRequests.bytes(page.body());
            for (var image : page.images())
                bytes += DeliveryDocumentRequests.bytes(image.getCaption());
        }
        if (bytes > DeliveryDocumentRequests.MAX_TEXT_BYTES) throw DeliveryDocumentRequests.limit();
    }

    public byte[] export(DeliveryDocumentResponse doc, Map<String, byte[]> images, String format)
            throws Exception {
        checkTextBudget(doc);
        if ("html".equals(format)) return html(doc, images).getBytes(StandardCharsets.UTF_8);
        if (!"markdown".equals(format)) throw DeliveryDocumentRequests.invalid();
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "说明书.md", markdown(doc, images).getBytes(StandardCharsets.UTF_8));
            int index = 0;
            for (var page : doc.pages())
                for (var image : page.images()) {
                    byte[] content = images.get(image.getId());
                    put(zip, imagePath(++index, content), content);
                }
        }
        return bytes.toByteArray();
    }

    private String markdown(DeliveryDocumentResponse doc, Map<String, byte[]> images) {
        var out =
                new StringBuilder("# ")
                        .append(md(doc.title()))
                        .append("\n\n")
                        .append(md(doc.introduction()))
                        .append("\n\n");
        out.append(md(identity(doc))).append("\n\n");
        int index = 0;
        for (var page : doc.pages()) {
            out.append("## ")
                    .append(md(page.name()))
                    .append("\n\n")
                    .append(md(page.path()))
                    .append("\n\n")
                    .append(md(page.body()))
                    .append("\n\n");
            for (var image : page.images()) {
                // 只有系统生成的相对图片路径成为Markdown语法，所有用户文本均先转义。
                out.append("![")
                        .append(md(image.getCaption()))
                        .append("](")
                        .append(imagePath(++index, images.get(image.getId())))
                        .append(")\n\n");
            }
            out.append(md(provenance(doc, page))).append("\n\n");
        }
        return out.toString();
    }

    private String html(DeliveryDocumentResponse doc, Map<String, byte[]> images) {
        var out =
                new StringBuilder("<!doctype html><html lang=\"")
                        .append(doc.locale())
                        .append(
                                "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"")
                        .append(escape(CSP))
                        .append("\"><title>")
                        .append(escape(doc.title()))
                        .append(
                                "</title><style>body{font-family:system-ui,sans-serif;max-width:900px;margin:40px auto;padding:0 24px;color:#18232f;line-height:1.65}pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere}img{max-width:100%;height:auto}figure{margin:24px 0}figcaption,small{color:#546273;overflow-wrap:anywhere}section{margin:40px 0}h1,h2{line-height:1.2;overflow-wrap:anywhere}@media(max-width:500px){body{margin:24px auto;padding:0 16px}}</style></head><body><h1>")
                        .append(escape(doc.title()))
                        .append("</h1><pre>")
                        .append(escape(doc.introduction()))
                        .append("</pre><small>")
                        .append(escape(identity(doc)))
                        .append("</small>");
        for (var page : doc.pages()) {
            out.append("<section><h2>")
                    .append(escape(page.name()))
                    .append("</h2><small>")
                    .append(escape(page.path()))
                    .append("</small><pre>")
                    .append(escape(page.body()))
                    .append("</pre>");
            for (var image : page.images()) {
                byte[] content = images.get(image.getId());
                out.append("<figure><img alt=\"")
                        .append(escape(image.getCaption()))
                        .append("\" src=\"data:image/")
                        .append(png(content) ? "png" : "jpeg")
                        .append(";base64,")
                        .append(Base64.getEncoder().encodeToString(content))
                        .append("\"><figcaption>")
                        .append(escape(image.getCaption()))
                        .append("</figcaption></figure>");
            }
            out.append("<small>")
                    .append(escape(provenance(doc, page)))
                    .append("</small></section>");
        }
        return out.append("</body></html>").toString();
    }

    private static String identity(DeliveryDocumentResponse doc) {
        return ("zh".equals(doc.locale())
                        ? "固定来源 / 项目、版本、修订："
                        : "Fixed source / project, version, revision: ")
                + doc.projectId()
                + " / "
                + doc.versionId()
                + " / "
                + doc.revisionId();
    }

    private static String provenance(
            DeliveryDocumentResponse doc, DeliveryDocumentResponse.Page page) {
        var out =
                new StringBuilder(
                        "zh".equals(doc.locale())
                                ? "来源引用（人工记录不代表审批）："
                                : "Source references (manual records are not approval): ");
        out.append(page.pageId()).append("; ");
        page.descriptions()
                .forEach(
                        ref ->
                                out.append("description:")
                                        .append(ref.getId())
                                        .append('@')
                                        .append(ref.getExpectedVersion())
                                        .append("; "));
        page.materials()
                .forEach(
                        ref ->
                                out.append("material:")
                                        .append(ref.getId())
                                        .append('@')
                                        .append(ref.getExpectedVersion())
                                        .append("; "));
        page.images()
                .forEach(
                        ref ->
                                out.append("image:")
                                        .append(ref.getId())
                                        .append('@')
                                        .append(ref.getExpectedVersion())
                                        .append("; "));
        page.checks()
                .forEach(
                        ref ->
                                out.append("manual-check:")
                                        .append(ref.getId())
                                        .append('@')
                                        .append(ref.getExpectedVersion())
                                        .append("; "));
        return out.toString();
    }

    private static String escape(String text) {
        return text == null
                ? ""
                : text.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                        .replace("'", "&#39;");
    }

    private static String md(String text) {
        if (text == null) return "";
        return escape(text).replace("\\", "\\\\").replaceAll("([`*_{}\\[\\]()#+.!|>~-])", "\\\\$1");
    }

    private static boolean png(byte[] content) {
        return content.length > 8 && content[0] == (byte) 0x89 && content[1] == 'P';
    }

    private static String imagePath(int index, byte[] bytes) {
        return "images/"
                + String.format(java.util.Locale.ROOT, "%03d", index)
                + (png(bytes) ? ".png" : ".jpg");
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
