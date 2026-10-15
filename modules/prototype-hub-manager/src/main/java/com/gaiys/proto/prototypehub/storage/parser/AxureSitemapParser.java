package com.gaiys.proto.prototypehub.storage.parser;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.storage.injector.HtmlInspectorInjector;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Interprets only Axure's data literals and _() pair builder; never evaluates JavaScript. */
@Component
public class AxureSitemapParser {
    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                    .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                    .build();

    public List<PrototypePageInfo> parse(Path root, int depth) throws IOException {
        try (var files = Files.walk(root, depth)) {
            Path document =
                    files.filter(
                                    p ->
                                            !Files.isSymbolicLink(p)
                                                    && Files.isRegularFile(p)
                                                    && p.getFileName()
                                                            .toString()
                                                            .equals("document.js")
                                                    && p.getParent()
                                                            .getFileName()
                                                            .toString()
                                                            .equals("data"))
                            .min(Comparator.comparingInt(p -> root.relativize(p).getNameCount()))
                            .orElse(null);
            if (document != null && Files.size(document) <= 20 * 1_048_576L) {
                try {
                    String source = Files.readString(document);
                    Map<String, JsonNode> aliases = new LinkedHashMap<>();
                    var declarations =
                            Pattern.compile(
                                            "([A-Za-z_$][\\w$]*)\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')")
                                    .matcher(source);
                    while (declarations.find())
                        aliases.put(declarations.group(1), JSON.readTree(declarations.group(2)));
                    String rootKey =
                            aliases.entrySet().stream()
                                    .filter(e -> e.getValue().asText().equals("rootNodes"))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .orElse("rootNodes");
                    var roots =
                            Pattern.compile(
                                            "(?:\\b"
                                                    + Pattern.quote(rootKey)
                                                    + "\\b|['\"]rootNodes['\"])\\s*[, :=]\\s*\\[")
                                    .matcher(source);
                    if (roots.find()) {
                        JsonNode nodes =
                                new LiteralReader(
                                                source, source.indexOf('[', roots.start()), aliases)
                                        .value(0);
                        List<PrototypePageInfo> result =
                                convert(nodes, document.getParent().getParent(), root);
                        if (!result.isEmpty()) return result;
                    }
                } catch (IllegalArgumentException
                        | IndexOutOfBoundsException
                        | IOException ignored) {
                    /* Fall back to safe HTML discovery. */
                }
            }
        }
        List<PrototypePageInfo> pages = new ArrayList<>();
        try (var files = Files.walk(root, depth)) {
            for (Path page :
                    files.filter(
                                    p ->
                                            !Files.isSymbolicLink(p)
                                                    && Files.isRegularFile(p)
                                                    && HtmlInspectorInjector.isHtml(p))
                            .sorted()
                            .limit(500)
                            .toList()) {
                String path = root.relativize(page).toString().replace('\\', '/');
                String title = page.getFileName().toString().replaceFirst("(?i)\\.html?$", "");
                if (Files.size(page) <= 1_048_576) {
                    var match =
                            Pattern.compile("(?is)<title[^>]*>(.*?)</title>")
                                    .matcher(Files.readString(page));
                    if (match.find()) title = match.group(1).replaceAll("\\s+", " ").trim();
                }
                pages.add(PrototypePageInfo.page(title, path));
            }
        }
        return directoryTree(pages, "");
    }

    private static List<PrototypePageInfo> directoryTree(
            List<PrototypePageInfo> pages, String prefix) {
        List<PrototypePageInfo> result = new ArrayList<>();
        Map<String, List<PrototypePageInfo>> folders = new LinkedHashMap<>();
        for (PrototypePageInfo page : pages) {
            String remaining = page.path().substring(prefix.length());
            int slash = remaining.indexOf('/');
            if (slash < 0) result.add(page);
            else
                folders.computeIfAbsent(remaining.substring(0, slash), k -> new ArrayList<>())
                        .add(page);
        }
        folders.forEach(
                (name, children) ->
                        result.add(
                                PrototypePageInfo.folder(
                                        name, directoryTree(children, prefix + name + "/"))));
        return result.stream().sorted(Comparator.comparing(PrototypePageInfo::name)).toList();
    }

    private static List<PrototypePageInfo> convert(JsonNode nodes, Path prototype, Path site) {
        List<PrototypePageInfo> result = new ArrayList<>();
        if (!nodes.isArray()) return result;
        for (JsonNode node : nodes) {
            List<PrototypePageInfo> children = convert(node.path("children"), prototype, site);
            String title = node.path("pageName").asText();
            String url = node.path("url").asText();
            if (node.path("type").asText().equals("Folder")
                    || (url.isBlank() && !children.isEmpty())) {
                if (!title.isBlank()) result.add(PrototypePageInfo.folder(title, children));
                else result.addAll(children);
            } else {
                if (title.isBlank() || url.isBlank()) {
                    result.addAll(children);
                    continue;
                }
                Path file;
                try {
                    file = SafePaths.within(prototype, url);
                } catch (com.gaiys.proto.prototypehub.exception.PrototypeHubException invalid) {
                    // Child URLs are independently checked against the same extracted site.
                    result.addAll(children);
                    continue;
                }
                if (file.startsWith(site)
                        && file.startsWith(prototype)
                        && Files.isRegularFile(file)
                        && HtmlInspectorInjector.isHtml(file)
                        && !Files.isSymbolicLink(file))
                    result.add(
                            new PrototypePageInfo(
                                    title,
                                    site.relativize(file).toString().replace('\\', '/'),
                                    false,
                                    children));
                else result.addAll(children);
            }
        }
        return result;
    }

    private static final class LiteralReader {
        final String text;
        final Map<String, JsonNode> aliases;
        int pos;
        int count;

        LiteralReader(String text, int pos, Map<String, JsonNode> aliases) {
            this.text = text;
            this.pos = pos;
            this.aliases = aliases;
        }

        void whitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        char take() {
            whitespace();
            if (pos >= text.length()) throw new IllegalArgumentException();
            return text.charAt(pos++);
        }

        void require(char c) {
            if (take() != c) throw new IllegalArgumentException();
        }

        boolean consume(char c) {
            whitespace();
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        String key(int depth) throws IOException {
            whitespace();
            char c = text.charAt(pos);
            if (c == '\'' || c == '"') return value(depth).asText();
            int start = pos;
            while (pos < text.length()
                    && (Character.isLetterOrDigit(text.charAt(pos))
                            || "_$".indexOf(text.charAt(pos)) >= 0)) pos++;
            if (start == pos) throw new IllegalArgumentException();
            return text.substring(start, pos);
        }

        JsonNode value(int depth) throws IOException {
            if (depth > 64 || ++count > 100_000) throw new IllegalArgumentException();
            whitespace();
            char c = text.charAt(pos);
            if (c == '[') {
                pos++;
                ArrayNode array = JSON.createArrayNode();
                if (!consume(']'))
                    do {
                        array.add(value(depth + 1));
                        if (consume(']')) break;
                        require(',');
                    } while (true);
                return array;
            }
            if (c == '\'' || c == '"') {
                int start = pos++;
                while (pos < text.length()) {
                    char next = text.charAt(pos++);
                    if (next == '\\') pos++;
                    else if (next == c) return JSON.readTree(text.substring(start, pos));
                }
                throw new IllegalArgumentException();
            }
            if (c == '{') {
                pos++;
                ObjectNode object = JSON.createObjectNode();
                if (!consume('}'))
                    do {
                        String key = key(depth + 1);
                        require(':');
                        object.set(key, value(depth + 1));
                        if (consume('}')) break;
                        require(',');
                    } while (true);
                return object;
            }
            int start = pos;
            while (pos < text.length()
                    && (Character.isLetterOrDigit(text.charAt(pos))
                            || "_$.-".indexOf(text.charAt(pos)) >= 0)) pos++;
            if (pos == start) throw new IllegalArgumentException();
            String token = text.substring(start, pos);
            if (token.equals("_") && consume('(')) {
                ObjectNode object = JSON.createObjectNode();
                if (!consume(')'))
                    do {
                        String key = value(depth + 1).asText();
                        require(',');
                        object.set(key, value(depth + 1));
                        if (consume(')')) break;
                        require(',');
                    } while (true);
                return object;
            }
            if (aliases.containsKey(token)) return aliases.get(token);
            if (token.equals("true")
                    || token.equals("false")
                    || token.equals("null")
                    || token.matches("-?[0-9]+(?:\\.[0-9]+)?")) return JSON.readTree(token);
            throw new IllegalArgumentException();
        }
    }
}
