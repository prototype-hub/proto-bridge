package com.gaiys.proto.prototypehub.storage.materials.text;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialTextExtractorTest {
    @TempDir Path root;
    private final MaterialTextExtractor extractor = new MaterialTextExtractor();
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    @Test
    void utf8AndMarkdownRemainLiteralAndCountUnicodeCodepoints() throws Exception {
        String text = "中文😀\r\n# Heading\n<script>literal</script>";
        for (String extension : new String[] {"txt", "md"}) {
            var result = extract("literal." + extension, text.getBytes(StandardCharsets.UTF_8));
            assertEquals(MaterialTextExtractor.Status.READY, result.status());
            assertEquals(text, result.content());
            assertEquals(text.codePointCount(0, text.length()), result.characterCount());
        }
    }

    @Test
    void malformedEncodingEmptyAndUnsupportedHaveNoInventedContent() throws Exception {
        assertStatus(
                "invalid.txt", new byte[] {(byte) 0xc3, 0x28}, MaterialTextExtractor.Status.FAILED);
        assertStatus(
                "empty.txt",
                " \n\t".getBytes(StandardCharsets.UTF_8),
                MaterialTextExtractor.Status.EMPTY);
        assertStatus("image.png", new byte[] {1}, MaterialTextExtractor.Status.UNSUPPORTED);
        Path file = Files.writeString(root.resolve("mismatch.txt"), "original");
        assertEquals(
                MaterialTextExtractor.Status.UNSUPPORTED,
                extractor.extract(file, "mismatch.txt", "image/png").status());
    }

    @Test
    void fullCharacterAndInputLimitsNeverReturnTruncatedReady() throws Exception {
        assertStatus(
                "over-limit.txt",
                "a".repeat(200_001).getBytes(StandardCharsets.UTF_8),
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        assertStatus(
                "surrogate-limit.txt",
                ("a".repeat(199_999) + "😀").getBytes(StandardCharsets.UTF_8),
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        assertStatus(
                "input-limit.txt",
                new byte[MaterialTextExtractor.MAX_INPUT_BYTES + 1],
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        var exact = extract("exact.txt", "a".repeat(200_000).getBytes(StandardCharsets.UTF_8));
        assertEquals(200_000, exact.characterCount());
        assertEquals(MaterialTextExtractor.Status.READY, exact.status());
    }

    @Test
    void docxParagraphsRunsAndTableCellsFollowDocumentOrder() throws Exception {
        byte[] bytes =
                office(
                        Map.of(
                                "word/document.xml",
                                "<w:document xmlns:w='"
                                        + W
                                        + "'><w:body><w:p><w:r><w:t>First 中文</w:t><w:tab/><w:t>second</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell one</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Cell two</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"));
        var result = extract("paragraph-table.docx", bytes);
        assertEquals(MaterialTextExtractor.Status.READY, result.status());
        assertEquals("First 中文\tsecond\nCell one\n\tCell two\n\t\n", result.content());
    }

    @Test
    void xlsxUsesWorkbookOrderSharedInlineAndOnlyCachedFormulaValues() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(
                "xl/workbook.xml",
                "<workbook xmlns='"
                        + S
                        + "' xmlns:r='"
                        + R
                        + "'><sheets><sheet name='Second first' r:id='r2'/><sheet name='First last' r:id='r1'/></sheets></workbook>");
        entries.put(
                "xl/_rels/workbook.xml.rels",
                relationships(
                        "<Relationship Id='r1' Target='worksheets/sheet1.xml'/><Relationship Id='r2' Target='worksheets/sheet2.xml'/>"));
        entries.put(
                "xl/sharedStrings.xml",
                "<sst xmlns='" + S + "'><si><r><t>Shared </t></r><r><t>中文</t></r></si></sst>");
        entries.put(
                "xl/worksheets/sheet1.xml",
                "<worksheet xmlns='"
                        + S
                        + "'><sheetData><row><c t='inlineStr'><is><t>Inline</t></is></c><c><f>99+1</f><v>100</v></c><c><f>DO_NOT_CALCULATE()</f></c></row></sheetData></worksheet>");
        entries.put(
                "xl/worksheets/sheet2.xml",
                "<worksheet xmlns='"
                        + S
                        + "'><sheetData><row><c t='s'><v>0</v></c><c t='b'><v>1</v></c></row></sheetData></worksheet>");
        var result = extract("ordered-cells.xlsx", office(entries));
        assertEquals(MaterialTextExtractor.Status.READY, result.status());
        assertEquals(
                "Second first\nShared 中文\t1\t\nFirst last\nInline\t100\t\t\n", result.content());
        assertFalse(result.content().contains("DO_NOT_CALCULATE"));
    }

    @Test
    void pptxUsesPresentationRelationshipsRatherThanZipOrFilenameOrder() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(
                "ppt/presentation.xml",
                "<p:presentation xmlns:p='"
                        + P
                        + "' xmlns:r='"
                        + R
                        + "'><p:sldIdLst><p:sldId r:id='r2'/><p:sldId r:id='r1'/></p:sldIdLst></p:presentation>");
        entries.put(
                "ppt/_rels/presentation.xml.rels",
                relationships(
                        "<Relationship Id='r1' Target='slides/slide1.xml'/><Relationship Id='r2' Target='slides/slide10.xml'/>"));
        entries.put("ppt/slides/slide1.xml", slide("Actual second"));
        entries.put("ppt/slides/slide10.xml", slide("Actual first 中文"));
        assertEquals(
                "Actual first 中文\nActual second\n",
                extract("ordered-slides.pptx", office(entries)).content());
    }

    @Test
    void pptxEmbeddedFontRelationshipsDoNotRequireBinaryFontToBeXml() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(
                "ppt/presentation.xml",
                "<p:presentation xmlns:p='"
                        + P
                        + "' xmlns:r='"
                        + R
                        + "'><p:sldIdLst><p:sldId r:id='r1'/></p:sldIdLst><p:embeddedFontLst><p:embeddedFont><p:font typeface='Liberation Sans'/><p:regular r:id='font'/></p:embeddedFont></p:embeddedFontLst></p:presentation>");
        entries.put(
                "ppt/_rels/presentation.xml.rels",
                relationships(
                        "<Relationship Id='r1' Target='slides/slide1.xml'/><Relationship Id='font' Type='"
                                + R
                                + "/font' Target='fonts/font1.fntdata'/>"));
        entries.put("ppt/slides/slide1.xml", slide("Embedded font slide 中文"));
        try (var font =
                PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font);
            Map<String, byte[]> binary = Map.of("ppt/fonts/font1.fntdata", font.readAllBytes());
            assertEquals(
                    "Embedded font slide 中文\n",
                    extract("embedded-font.pptx", office(entries, binary)).content());
            entries.replaceAll(
                    (name, xml) ->
                            xml.replace(
                                    R, "http://purl.oclc.org/ooxml/officeDocument/relationships"));
            assertEquals(
                    "Embedded font slide 中文\n",
                    extract("embedded-font-strict-rels.pptx", office(entries, binary)).content());
        }
    }

    @Test
    void consumedSlideRelationshipsStillRejectNonlocalMissingOrWrongTypeTargets() throws Exception {
        String[] relationships = {
            "<Relationship Id='r1' Type='" + R + "/font' Target='/ppt/slides/slide1.xml'/>",
            "<Relationship Id='r1' Type='" + R + "/slide' Target='missing.xml'/>",
            "<Relationship Id='r1' Type='" + R + "/slide' Target='../../../outside.xml'/>",
            "<Relationship Id='r1' Type='" + R + "/slide' Target='file:///etc/passwd'/>",
            "<Relationship Id='r1' Type='"
                    + R
                    + "/slide' TargetMode='External' Target='http://127.0.0.1/private'/>",
            "<Relationship Id='r1' Type='" + R + "/slide' Target='fonts/font1.fntdata'/>"
        };
        for (int i = 0; i < relationships.length; i++) {
            assertStatus(
                    "invalid-slide-relationship-" + i + ".pptx",
                    office(
                            Map.of(
                                    "ppt/presentation.xml",
                                    "<p:presentation xmlns:p='"
                                            + P
                                            + "' xmlns:r='"
                                            + R
                                            + "'><p:sldIdLst><p:sldId r:id='r1'/></p:sldIdLst></p:presentation>",
                                    "ppt/_rels/presentation.xml.rels",
                                    relationships(relationships[i]),
                                    "ppt/slides/slide1.xml",
                                    slide("Must not become ready")),
                            Map.of("ppt/fonts/font1.fntdata", new byte[] {0, 1, 0, 0})),
                    MaterialTextExtractor.Status.FAILED);
        }
    }

    @Test
    void dtdEntitiesBadXmlAndBadZipAreControlledFailures() throws Exception {
        String[] xml = {
            "<!DOCTYPE w:document [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><w:document xmlns:w='"
                    + W
                    + "'><w:p><w:t>&x;</w:t></w:p></w:document>",
            "<w:document xmlns:w='" + W + "'><w:p></w:document>"
        };
        for (int i = 0; i < xml.length; i++)
            assertStatus(
                    "invalid-xml-" + i + ".docx",
                    office(Map.of("word/document.xml", xml[i])),
                    MaterialTextExtractor.Status.FAILED);
        assertStatus(
                "broken-archive.docx",
                new byte[] {'P', 'K', 3, 4, 0},
                MaterialTextExtractor.Status.FAILED);
    }

    @Test
    void externalRelationshipsAndEntitiesNeverRequestNetwork() throws Exception {
        var hits = new AtomicInteger();
        var server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/private";
            byte[] bytes =
                    office(
                            Map.of(
                                    "word/document.xml",
                                    "<w:document xmlns:w='"
                                            + W
                                            + "'><w:p><w:t>Local only</w:t></w:p></w:document>",
                                    "word/_rels/document.xml.rels",
                                    relationships(
                                            "<Relationship Id='external' TargetMode='External' Target='"
                                                    + target
                                                    + "'/>")));
            assertEquals("Local only\n", extract("external-link.docx", bytes).content());
            assertStatus(
                    "external-entity.docx",
                    office(
                            Map.of(
                                    "word/document.xml",
                                    "<!DOCTYPE w:document SYSTEM '"
                                            + target
                                            + "'><w:document xmlns:w='"
                                            + W
                                            + "'/>")),
                    MaterialTextExtractor.Status.FAILED);
            for (String mode : new String[] {"", "TargetMode='External' "})
                assertStatus(
                        "external-slide-" + (mode.isEmpty() ? "unmarked" : "marked") + ".pptx",
                        office(
                                Map.of(
                                        "ppt/presentation.xml",
                                        "<p:presentation xmlns:p='"
                                                + P
                                                + "' xmlns:r='"
                                                + R
                                                + "'><p:sldIdLst><p:sldId r:id='r1'/></p:sldIdLst></p:presentation>",
                                        "ppt/_rels/presentation.xml.rels",
                                        relationships(
                                                "<Relationship Id='r1' Type='"
                                                        + R
                                                        + "/slide' "
                                                        + mode
                                                        + "Target='"
                                                        + target
                                                        + "'/>"))),
                        MaterialTextExtractor.Status.FAILED);
            assertEquals(0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void xmlPartCombinedDepthAndTraversalBudgetsAreEnforced() throws Exception {
        assertStatus(
                "part-limit.docx",
                office(
                        Map.of(
                                "word/document.xml",
                                "<d>" + " ".repeat(OfficeText.MAX_PART_BYTES) + "</d>")),
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        var combined = new LinkedHashMap<String, String>();
        for (int i = 0; i < 4; i++)
            combined.put("part" + i + ".xml", "<d>" + " ".repeat(3 * 1024 * 1024) + "</d>");
        assertStatus(
                "combined-limit.docx",
                office(combined),
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        assertStatus(
                "deep.docx",
                office(Map.of("word/document.xml", "<d>".repeat(65) + "</d>".repeat(65))),
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        assertStatus(
                "traversal.docx",
                office(Map.of("../outside.xml", "<x/>", "word/document.xml", "<d/>")),
                MaterialTextExtractor.Status.FAILED);
    }

    @Test
    void pdfTextLayerEmptyEncryptionAndPageLimitsAreExplicit() throws Exception {
        assertEquals(
                "First page\nSecond page\n",
                extract("text-layer.pdf", pdf(2, false, true)).content());
        assertStatus("empty.pdf", pdf(1, false, false), MaterialTextExtractor.Status.EMPTY);
        assertStatus("encrypted.pdf", pdf(1, true, true), MaterialTextExtractor.Status.ENCRYPTED);
        assertStatus(
                "page-limit.pdf",
                pdf(101, false, false),
                MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        assertStatus(
                "broken.pdf",
                "%PDF-1.7\ninvalid".getBytes(StandardCharsets.US_ASCII),
                MaterialTextExtractor.Status.FAILED);
    }

    @Test
    void pdfTimeoutAndCancellationDestroyWorkerAndDoNotChangeOriginal() throws Exception {
        Path file = Files.write(root.resolve("text.pdf"), pdf(1, false, true));
        byte[] original = Files.readAllBytes(file);
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () ->
                        assertEquals(
                                MaterialTextExtractor.Status.LIMIT_EXCEEDED,
                                PdfTextProcess.extract(file, 0).status()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> PdfTextProcess.extract(file));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(
                ProcessHandle.current()
                        .children()
                        .noneMatch(
                                p ->
                                        p.info()
                                                .commandLine()
                                                .orElse("")
                                                .contains(PdfTextWorker.class.getName())));
    }

    @Test
    void pdfWorkerDoesNotInheritApplicationSecretsOrLoaderSettings() {
        ProcessBuilder builder = new ProcessBuilder("unused");
        builder.environment()
                .putAll(
                        Map.of(
                                "DB_PASSWORD",
                                "sentinel-secret",
                                "ADMIN_PASSWORD",
                                "sentinel-secret",
                                "LOADER_PATH",
                                "malicious",
                                "JAVA_TOOL_OPTIONS",
                                "-javaagent:malicious"));
        PdfTextProcess.isolate(builder, root);
        assertEquals(root.toFile(), builder.directory());
        assertFalse(builder.environment().containsValue("sentinel-secret"));
        assertFalse(builder.environment().containsKey("LOADER_PATH"));
        assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void compressedPdfTextExpansionCannotExhaustTheServerHeap() throws Exception {
        try (var document = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            var content = new org.apache.pdfbox.pdmodel.common.PDStream(document);
            try (var stream =
                    content.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
                stream.write("BT (".getBytes(StandardCharsets.US_ASCII));
                byte[] block = "a".repeat(8192).getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; i < 25_600; i++) stream.write(block);
                stream.write(") Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            document.save(output);
            assertStatus(
                    "compressed-limit.pdf",
                    output.toByteArray(),
                    MaterialTextExtractor.Status.LIMIT_EXCEEDED);
        }
    }

    private MaterialTextExtractor.Result extract(String name, byte[] bytes) throws Exception {
        Path file = Files.write(root.resolve(name), bytes);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        String media =
                switch (extension) {
                    case "txt" -> "text/plain";
                    case "md" -> "text/markdown";
                    case "docx" ->
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    case "xlsx" ->
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    case "pptx" ->
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    case "pdf" -> "application/pdf";
                    default -> "image/png";
                };
        var result = extractor.extract(file, name, media);
        String fixtures = System.getProperty("material.fixtures.directory");
        if (fixtures != null) {
            Path directory = Files.createDirectories(Path.of(fixtures));
            Files.copy(
                    file,
                    directory.resolve(name),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(directory.resolve(name + ".expected.txt"), result.content());
            Files.writeString(
                    directory.resolve(name + ".result"),
                    result.status()
                            + "\n"
                            + result.characterCount()
                            + "\n"
                            + media
                            + "\n"
                            + java.util.HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(bytes))
                            + "\n");
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
        return result;
    }

    private void assertStatus(String name, byte[] bytes, MaterialTextExtractor.Status status)
            throws Exception {
        var result = extract(name, bytes);
        assertEquals(status, result.status(), name);
        assertEquals("", result.content());
        assertEquals(0, result.characterCount());
        assertEquals(MaterialTextExtractor.VERSION, result.extractorVersion());
    }

    private static byte[] office(Map<String, String> entries) throws IOException {
        return office(entries, Map.of());
    }

    private static byte[] office(Map<String, String> entries, Map<String, byte[]> binary)
            throws IOException {
        var output = new ByteArrayOutputStream();
        String main =
                entries.containsKey("xl/workbook.xml")
                        ? "xl/workbook.xml"
                        : entries.containsKey("ppt/presentation.xml")
                                ? "ppt/presentation.xml"
                                : "word/document.xml";
        StringBuilder types =
                new StringBuilder(
                        "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/>");
        if (binary.keySet().stream().anyMatch(name -> name.endsWith(".fntdata")))
            types.append("<Default Extension='fntdata' ContentType='application/x-fontdata'/>");
        for (String name : entries.keySet()) {
            String contentType =
                    name.equals("word/document.xml")
                            ? "wordprocessingml.document.main"
                            : name.equals("xl/workbook.xml")
                                    ? "spreadsheetml.sheet.main"
                                    : name.startsWith("xl/worksheets/")
                                            ? "spreadsheetml.worksheet"
                                            : name.equals("xl/sharedStrings.xml")
                                                    ? "spreadsheetml.sharedStrings"
                                                    : name.equals("ppt/presentation.xml")
                                                            ? "presentationml.presentation.main"
                                                            : name.startsWith("ppt/slides/")
                                                                    ? "presentationml.slide"
                                                                    : null;
            if (contentType != null)
                types.append("<Override PartName='/")
                        .append(name)
                        .append("' ContentType='application/vnd.openxmlformats-officedocument.")
                        .append(contentType)
                        .append("+xml'/>");
        }
        types.append("</Types>");
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(types.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(
                    relationships(
                                    "<Relationship Id='rId1' Type='"
                                            + R
                                            + "/officeDocument' Target='"
                                            + main
                                            + "'/>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (var entry : binary.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String relationships(String body) {
        body =
                body.replace(
                                "Target='worksheets/",
                                "Type='" + R + "/worksheet' Target='worksheets/")
                        .replace("Target='slides/", "Type='" + R + "/slide' Target='slides/");
        return "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + body
                + "</Relationships>";
    }

    private static String slide(String text) {
        return "<p:sld xmlns:p='"
                + P
                + "' xmlns:a='"
                + A
                + "'><a:p><a:r><a:t>"
                + text
                + "</a:t></a:r></a:p></p:sld>";
    }

    private static byte[] pdf(int pages, boolean encrypted, boolean text) throws IOException {
        try (var document = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage();
                document.addPage(page);
                if (text)
                    try (var stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(20, 700);
                        stream.showText(i == 0 ? "First page" : "Second page");
                        stream.endText();
                    }
            }
            if (encrypted)
                document.protect(
                        new StandardProtectionPolicy(
                                "owner-password", "reader-password", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
    }
}
