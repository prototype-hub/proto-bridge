package com.gaiys.proto.prototypehub.storage.materials.text;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** 有界读取OOXML正文部分；按工作簿/演示文稿关系排序，不解压文件或解析公式、宏、外部关系。 */
final class OfficeText {
    static final int MAX_PART_BYTES = 4 * 1024 * 1024;
    static final int MAX_XML_BYTES = 12 * 1024 * 1024;
    private static final String WORD =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String PRESENTATION =
            "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private final Map<String, byte[]> parts = new HashMap<>();

    OfficeText(Path file) throws IOException, XMLStreamException {
        int xmlBytes = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile archive = new ZipFile(file.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (names.size() >= 4096) throw new MaterialTextExtractor.LimitExceeded();
                if (!names.add(name)
                        || name.startsWith("/")
                        || name.indexOf('\\') >= 0
                        || name.chars().anyMatch(Character::isISOControl)
                        || !Path.of(name)
                                .normalize()
                                .toString()
                                .replace('\\', '/')
                                .equals(
                                        name.endsWith("/")
                                                ? name.substring(0, name.length() - 1)
                                                : name)
                        || name.startsWith("../")
                        || entry.getSize() < 0) throw new IOException("invalid archive entry");
                expanded = Math.addExact(expanded, entry.getSize());
                if (expanded > 200L * 1024 * 1024) throw new MaterialTextExtractor.LimitExceeded();
                if (entry.isDirectory() || !(name.endsWith(".xml") || name.endsWith(".rels")))
                    continue;
                if (entry.getSize() > MAX_PART_BYTES || entry.getSize() > MAX_XML_BYTES - xmlBytes)
                    throw new MaterialTextExtractor.LimitExceeded();
                byte[] bytes;
                try (var input = archive.getInputStream(entry)) {
                    bytes = input.readNBytes(MAX_PART_BYTES + 1);
                }
                if (bytes.length > MAX_PART_BYTES || bytes.length > MAX_XML_BYTES - xmlBytes)
                    throw new MaterialTextExtractor.LimitExceeded();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("invalid archive content");
                xmlBytes += bytes.length;
                parts.put(name, bytes);
            }
        }
        if (!parts.containsKey("[Content_Types].xml"))
            throw new IOException("missing content types");
        // 未用于正文的XML也不允许藏DTD/实体；总字节、节点和深度都受限。
        for (String part : parts.keySet()) parse(part, reader -> {});
    }

    void extract(String extension, MaterialTextExtractor.Text text)
            throws IOException, XMLStreamException {
        switch (extension) {
            case "docx" -> document("word/document.xml", WORD, text);
            case "xlsx" -> spreadsheet(text);
            case "pptx" -> presentation(text);
            default -> throw new IOException("unsupported office document");
        }
    }

    private void document(String part, String namespace, MaterialTextExtractor.Text text)
            throws IOException, XMLStreamException {
        boolean[] literal = {false};
        parse(
                part,
                reader -> {
                    String local = reader.hasName() ? reader.getLocalName() : "";
                    boolean matching = reader.hasName() && namespace(reader, namespace);
                    if (reader.isStartElement() && matching) {
                        if (local.equals("t")) literal[0] = true;
                        else if (local.equals("tab")) text.append("\t");
                        else if (local.equals("br") || local.equals("cr")) text.append("\n");
                    } else if (reader.isCharacters() && literal[0]) {
                        text.append(reader.getText());
                    } else if (reader.isEndElement() && matching) {
                        if (local.equals("t")) literal[0] = false;
                        else if (local.equals("p") || local.equals("tr")) text.append("\n");
                        else if (local.equals("tc")) text.append("\t");
                    }
                });
    }

    private void spreadsheet(MaterialTextExtractor.Text text)
            throws IOException, XMLStreamException {
        List<String> strings = new ArrayList<>();
        if (parts.containsKey("xl/sharedStrings.xml")) {
            var item = new StringBuilder();
            boolean[] literal = {false};
            parse(
                    "xl/sharedStrings.xml",
                    reader -> {
                        if (reader.isStartElement() && namespace(reader, SHEET)) {
                            if (reader.getLocalName().equals("si")) item.setLength(0);
                            if (reader.getLocalName().equals("t")) literal[0] = true;
                        } else if (reader.isCharacters() && literal[0])
                            item.append(reader.getText());
                        else if (reader.isEndElement() && namespace(reader, SHEET)) {
                            if (reader.getLocalName().equals("t")) literal[0] = false;
                            if (reader.getLocalName().equals("si")) strings.add(item.toString());
                        }
                    });
        }
        Map<String, String> relationships = relationships("xl/workbook.xml", "worksheet");
        List<String[]> sheets = new ArrayList<>();
        parse(
                "xl/workbook.xml",
                reader -> {
                    if (reader.isStartElement()
                            && namespace(reader, SHEET)
                            && reader.getLocalName().equals("sheet"))
                        sheets.add(new String[] {attribute(reader, "name"), relationId(reader)});
                });
        for (String[] sheet : sheets) {
            text.append(sheet[0] + "\n");
            String part = relationships.get(sheet[1]);
            if (part == null) throw new IOException("missing sheet relationship");
            cellText(part, strings, text);
        }
    }

    private void cellText(String part, List<String> strings, MaterialTextExtractor.Text text)
            throws IOException, XMLStreamException {
        String[] type = {""};
        StringBuilder value = new StringBuilder();
        boolean[] literal = {false};
        parse(
                part,
                reader -> {
                    if (reader.isStartElement() && namespace(reader, SHEET)) {
                        String local = reader.getLocalName();
                        if (local.equals("c")) {
                            type[0] = attribute(reader, "t");
                            value.setLength(0);
                        }
                        if (local.equals("v") || (local.equals("t") && type[0].equals("inlineStr")))
                            literal[0] = true;
                    } else if (reader.isCharacters() && literal[0]) value.append(reader.getText());
                    else if (reader.isEndElement() && namespace(reader, SHEET)) {
                        String local = reader.getLocalName();
                        if (local.equals("v") || local.equals("t")) literal[0] = false;
                        if (local.equals("c")) {
                            String content = value.toString();
                            if (type[0].equals("s") && !content.isEmpty()) {
                                int index = Integer.parseInt(content.trim());
                                if (index < 0 || index >= strings.size())
                                    throw new IOException("invalid shared string");
                                content = strings.get(index);
                            }
                            text.append(content);
                            text.append("\t");
                        } else if (local.equals("row")) text.append("\n");
                    }
                });
    }

    private void presentation(MaterialTextExtractor.Text text)
            throws IOException, XMLStreamException {
        Map<String, String> relationships = relationships("ppt/presentation.xml", "slide");
        List<String> slides = new ArrayList<>();
        parse(
                "ppt/presentation.xml",
                reader -> {
                    if (reader.isStartElement()
                            && namespace(reader, PRESENTATION)
                            && reader.getLocalName().equals("sldId"))
                        slides.add(relationId(reader));
                });
        for (String id : slides) {
            String part = relationships.get(id);
            if (part == null) throw new IOException("missing slide relationship");
            document(part, DRAWING, text);
        }
    }

    private Map<String, String> relationships(String source, String kind)
            throws IOException, XMLStreamException {
        Map<String, String> result = new LinkedHashMap<>();
        int slash = source.lastIndexOf('/');
        String part =
                source.substring(0, slash + 1) + "_rels/" + source.substring(slash + 1) + ".rels";
        parse(
                part,
                reader -> {
                    if (!reader.isStartElement() || !reader.getLocalName().equals("Relationship"))
                        return;
                    // 正文只消费工作表/幻灯片关系；嵌入字体等二进制关系不进入XML目标校验。
                    String type = attribute(reader, "Type");
                    if (!type.equals(REL + "/" + kind)
                            && !type.equals(
                                    "http://purl.oclc.org/ooxml/officeDocument/relationships/"
                                            + kind)) return;
                    if (attribute(reader, "TargetMode").equalsIgnoreCase("External")) return;
                    URI target = URI.create(attribute(reader, "Target"));
                    if (target.isAbsolute()
                            || target.getAuthority() != null
                            || target.getQuery() != null
                            || target.getFragment() != null)
                        throw new IOException("nonlocal relationship");
                    String path = URI.create("/" + source).resolve(target).normalize().getPath();
                    if (path == null
                            || path.contains("\\")
                            || path.startsWith("/../")
                            || !parts.containsKey(path.substring(1)))
                        throw new IOException("missing relationship target");
                    if (result.put(attribute(reader, "Id"), path.substring(1)) != null)
                        throw new IOException("duplicate relationship");
                });
        return result;
    }

    private static String relationId(XMLStreamReader reader) {
        String id = reader.getAttributeValue(REL, "id");
        return id == null
                ? reader.getAttributeValue(
                        "http://purl.oclc.org/ooxml/officeDocument/relationships", "id")
                : id;
    }

    private static String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static boolean namespace(XMLStreamReader reader, String expected) {
        String namespace = reader.getNamespaceURI();
        return expected.equals(namespace)
                || expected.replace(
                                "http://schemas.openxmlformats.org/", "http://purl.oclc.org/ooxml/")
                        .replace("/2006", "")
                        .equals(namespace);
    }

    @FunctionalInterface
    private interface XmlConsumer {
        void accept(XMLStreamReader reader) throws IOException;
    }

    private void parse(String part, XmlConsumer consumer) throws IOException, XMLStreamException {
        byte[] bytes = parts.get(part);
        if (bytes == null) throw new IOException("missing XML part");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver(
                (publicId, systemId, baseUri, namespace) -> {
                    throw new XMLStreamException("external entity rejected");
                });
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        int depth = 0;
        int events = 0;
        try {
            while (reader.hasNext()) {
                if (Thread.currentThread().isInterrupted())
                    throw new IOException("extraction interrupted");
                int event = reader.next();
                if (++events > 500_000
                        || (event == XMLStreamConstants.START_ELEMENT && ++depth > 64))
                    throw new MaterialTextExtractor.LimitExceeded();
                if (event == XMLStreamConstants.END_ELEMENT) depth--;
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                    throw new XMLStreamException("DTD/entity rejected");
                consumer.accept(reader);
            }
        } finally {
            reader.close();
        }
    }
}
