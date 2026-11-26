package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests;
import com.gaiys.proto.prototypehub.web.response.DeliveryDocumentResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class DeliveryDocumentRendererTest {
    final DeliveryDocumentRenderer renderer = new DeliveryDocumentRenderer(new ObjectMapper());
    final String id = "a".repeat(64);
    final byte[] png =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jHdQAAAAASUVORK5CYII=");

    @Test
    void assemblesAllSixDescriptionFieldsReadyTextAndManualFactsInBothLanguages() throws Exception {
        var source = source();
        var zh = renderer.page("p", "v", "r", "zh", source, false);
        for (String text :
                List.of(
                        "区域名",
                        "用途文本",
                        "输入文本",
                        "权限文本",
                        "跳转文本",
                        "备注文本",
                        "已提取正文",
                        "真实正文",
                        "人工自测",
                        "390 × 844",
                        "failed",
                        "人工证据")) assertTrue(zh.body().contains(text), text);
        var en = renderer.page("p", "v", "r", "en", source, false);
        assertTrue(en.body().contains("Manual check: 场景"));
        assertTrue(en.body().contains("Permission rules: 权限文本"));
        assertNotEquals(zh.sourceFingerprint(), en.sourceFingerprint());
        source.request().getMaterials().getFirst().setIncludeText(false);
        assertFalse(renderer.page("p", "v", "r", "zh", source, false).body().contains("真实正文"));
    }

    @Test
    void fingerprintsActualDataIncludingExtractionWithoutCasChangeAndRejectsStaleExport()
            throws Exception {
        var source = source();
        var first = renderer.page("p", "v", "r", "zh", source, false);
        source.request().setBody("会话修改仍保留");
        source.request().setSourceFingerprint(first.sourceFingerprint());
        assertEquals("会话修改仍保留", renderer.page("p", "v", "r", "zh", source, true).body());
        source.materials().getFirst().setTextContent("后台重新提取内容，CAS未变化");
        var failure =
                assertThrows(
                        PrototypeHubException.class,
                        () -> renderer.page("p", "v", "r", "zh", source, true));
        assertEquals(409, failure.getStatus());
        assertEquals("PROTOTYPE_HUB_DELIVERY_DOCUMENT_SOURCE_CHANGED", failure.getCode());
        assertEquals("会话修改仍保留", source.request().getBody());
        source.request()
                .setSourceFingerprint(
                        renderer.page("p", "v", "r", "zh", source, false).sourceFingerprint());
        source.materials().getFirst().setTextExtractedAt(99L);
        assertThrows(
                PrototypeHubException.class,
                () -> renderer.page("p", "v", "r", "zh", source, true));
        source.request()
                .setSourceFingerprint(
                        renderer.page("p", "v", "r", "zh", source, false).sourceFingerprint());
        source.checks().getFirst().setObserved("changed");
        assertThrows(
                PrototypeHubException.class,
                () -> renderer.page("p", "v", "r", "zh", source, true));
        source.request()
                .setSourceFingerprint(
                        renderer.page("p", "v", "r", "zh", source, false).sourceFingerprint());
        source.request().getImages().getFirst().setCaption("changed");
        assertThrows(
                PrototypeHubException.class,
                () -> renderer.page("p", "v", "r", "zh", source, true));
    }

    @Test
    void markdownZipContainsOfflineBytesAndLiteralEscapedTextWithoutRemoteImagesOrHtml()
            throws Exception {
        var doc = hostileDoc();
        byte[] bytes = renderer.export(doc, Map.of("image", png), "markdown");
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip =
                new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(List.of("说明书.md", "images/001.png"), List.copyOf(entries.keySet()));
        assertArrayEquals(png, entries.get("images/001.png"));
        String md = new String(entries.get("说明书.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("](images/001.png)"));
        assertFalse(md.contains("<script>"));
        assertFalse(md.contains("<img"));
        assertFalse(md.contains("![remote](https://evil.test/x)"));
        assertTrue(md.contains("Source references \\(manual records are not approval\\)"));
        assertTrue(md.contains("material:material@0"));
        assertTrue(md.contains("Edited body"));
    }

    @Test
    void singleHtmlEscapesEveryOrdinaryStringAndAllowsOnlyBoundedEmbeddedImages() throws Exception {
        var doc = hostileDoc();
        String html =
                new String(
                        renderer.export(doc, Map.of("image", png), "html"), StandardCharsets.UTF_8);
        var parsed = Jsoup.parse(html);
        assertTrue(parsed.select("script,iframe,object,embed,form,a,link").isEmpty());
        assertEquals(
                DeliveryDocumentRenderer.CSP,
                parsed.selectFirst("meta[http-equiv=Content-Security-Policy]").attr("content"));
        assertEquals(1, parsed.select("img").size());
        String data = parsed.selectFirst("img").attr("src");
        assertTrue(data.startsWith("data:image/png;base64,"));
        assertArrayEquals(png, Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1)));
        assertEquals(
                doc.pages().getFirst().body(), parsed.select("section pre").getFirst().wholeText());
        assertEquals("en", parsed.selectFirst("html").attr("lang"));
        assertEquals(doc.title(), parsed.title());
        assertFalse(html.contains("src=\"https://evil.test"));
    }

    @Test
    void generatedPageAndMultibyteAggregateBudgetsRejectWithoutTruncation() throws Exception {
        var source = source();
        source.materials().getFirst().setTextContent("界".repeat(20001));
        assertEquals(
                413,
                assertThrows(
                                PrototypeHubException.class,
                                () -> renderer.page("p", "v", "r", "zh", source, false))
                        .getStatus());
        var page = hostileDoc().pages().getFirst();
        var large =
                new DeliveryDocumentResponse.Page(
                        page.pageId(),
                        page.name(),
                        page.path(),
                        "界".repeat(20000),
                        page.sourceFingerprint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        var doc =
                new DeliveryDocumentResponse(
                        "p", "v", "r", "zh", "title", "", java.util.Collections.nCopies(18, large));
        assertEquals(
                413,
                assertThrows(PrototypeHubException.class, () -> renderer.checkTextBudget(doc))
                        .getStatus());
    }

    private DeliveryDocumentResponse hostileDoc() throws Exception {
        var source = source();
        var page = renderer.page("p", "v", "r", "en", source, false);
        page =
                new DeliveryDocumentResponse.Page(
                        page.pageId(),
                        "<img src=https://evil.test>",
                        "../<script>.html",
                        "Edited body\n<script>alert(1)</script>\n![remote](https://evil.test/x)\n[click](javascript:evil)",
                        page.sourceFingerprint(),
                        page.descriptions(),
                        page.materials(),
                        page.images(),
                        page.checks());
        return new DeliveryDocumentResponse(
                "p",
                "v",
                "r",
                "en",
                "Title </title><script>bad</script>",
                "<img src=https://evil.test>",
                List.of(page));
    }

    private DeliveryDocumentRenderer.Sources source() {
        var request = new DeliveryDocumentRequests.Page();
        request.setPageId(id);
        var d = new PrototypeElementDescriptionEntity();
        d.setId("description");
        d.setConfigVersion(0L);
        d.setTitle("区域名");
        d.setPurpose("用途文本");
        d.setInputRules("输入文本");
        d.setPermissionRules("权限文本");
        d.setNavigationRules("跳转文本");
        d.setNotes("备注文本");
        var m = new PrototypeRevisionMaterialEntity();
        m.setId("material");
        m.setConfigVersion(0L);
        m.setMaterialType("document");
        m.setFileName("资料.txt");
        m.setTextStatus("READY");
        m.setTextContent("真实正文");
        m.setTextExtractedAt(1L);
        var i = new PrototypeRevisionMaterialEntity();
        i.setId("image");
        i.setConfigVersion(0L);
        i.setMaterialType("image");
        i.setMediaType("image/png");
        var c = new PrototypeViewportCheckEntity();
        c.setId("check");
        c.setConfigVersion(0L);
        c.setTitle("场景");
        c.setViewportWidth(390);
        c.setViewportHeight(844);
        c.setViewportScale(100);
        c.setCategory("error");
        c.setExpected("预期");
        c.setObserved("观察");
        c.setResult("failed");
        c.setEvidence("人工证据");
        var dr = new DeliveryDocumentRequests.SourceRef();
        dr.setId(d.getId());
        dr.setExpectedVersion(0L);
        request.setDescriptions(List.of(dr));
        var mr = new DeliveryDocumentRequests.MaterialRef();
        mr.setId(m.getId());
        mr.setExpectedVersion(0L);
        mr.setIncludeText(true);
        request.setMaterials(List.of(mr));
        var ir = new DeliveryDocumentRequests.ImageRef();
        ir.setId(i.getId());
        ir.setExpectedVersion(0L);
        ir.setCaption("截图 <img src=https://evil.test>");
        request.setImages(List.of(ir));
        var cr = new DeliveryDocumentRequests.SourceRef();
        cr.setId(c.getId());
        cr.setExpectedVersion(0L);
        request.setChecks(List.of(cr));
        return new DeliveryDocumentRenderer.Sources(
                new PrototypePageInfo("首页", id, "index.html", false, List.of()),
                request,
                List.of(d),
                List.of(m),
                List.of(i),
                List.of(c));
    }
}
