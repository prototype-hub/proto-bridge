package com.gaiys.proto.prototypehub.service.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.validation.dtcg.DtcgTypes;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument.AliasView;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument.GroupView;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument.Issue;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument.ModifierView;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument.SetView;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument.TokenView;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single-document 2025.10 DTCG parser. Never dereferences URLs, files, schemas or extensions. */
public final class DesignTokenParser {
    public static final String VERSION = "2025.10";
    public static final String VALIDATOR_VERSION = "2025.10-1";
    public static final int MAX_BYTES = 1_048_576;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_TOKENS = 5000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_REFERENCES = 64;
    private static final int MAX_PERMUTATIONS = 16;
    private static final int MAX_ISSUES = 128;
    private static final int MAX_RESULT_BYTES = 4_194_304;
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(MAX_DEPTH)
                                                    .maxStringLength(MAX_BYTES)
                                                    .maxNumberLength(1000)
                                                    .build())
                                    .build())
                    .enable(
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public DesignTokenDocument parse(byte[] bytes) {
        return parseDocument(bytes, null);
    }

    public DesignTokenDocument parse(byte[] bytes, Map<String, String> inputs) {
        return parseDocument(bytes, inputs == null ? Map.of() : inputs);
    }

    private DesignTokenDocument parseDocument(byte[] bytes, Map<String, String> inputs) {
        if (bytes == null || bytes.length > MAX_BYTES) throw limit("rawBytes");
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException error) {
            throw fatal("invalid_utf8");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (StreamConstraintsException error) {
            throw limit("jsonDepthOrScalar");
        } catch (JsonProcessingException error) {
            JsonLocation location = error.getLocation();
            throw fatal(
                    "invalid_json",
                    location == null ? 0 : location.getLineNr(),
                    location == null ? 0 : location.getColumnNr());
        }
        if (root == null || !root.isObject()) throw fatal("root_object");
        Work work = new Work((ObjectNode) root);
        work.count(root, 0);
        DesignTokenDocument document = work.parse(inputs);
        try {
            JSON.writeValue(
                    new OutputStream() {
                        private long bytes;

                        @Override
                        public void write(int value) throws IOException {
                            add(1);
                        }

                        @Override
                        public void write(byte[] value, int offset, int length) throws IOException {
                            add(length);
                        }

                        private void add(int length) throws IOException {
                            bytes += length;
                            if (bytes > MAX_RESULT_BYTES) throw new IOException("resultBytes");
                        }
                    },
                    document);
        } catch (IOException error) {
            throw limit("resultBytes");
        }
        return document;
    }

    private static PrototypeHubException fatal(String reason, Object... arguments) {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.token_package_" + reason,
                "PROTOTYPE_HUB_TOKEN_PACKAGE_" + reason.toUpperCase(java.util.Locale.ROOT),
                arguments);
    }

    private static PrototypeHubException limit(String name) {
        return fatal("limit_exceeded", name);
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    private static String pointer(List<String> path) {
        return "#"
                + path.stream()
                        .map(s -> "/" + escape(s))
                        .collect(java.util.stream.Collectors.joining());
    }

    private static List<String> append(List<String> path, String name) {
        List<String> result = new ArrayList<>(path);
        result.add(name);
        return List.copyOf(result);
    }

    private static String string(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.get(field).textValue() : null;
    }

    private static boolean curly(JsonNode node) {
        return node != null
                && node.isTextual()
                && node.textValue().startsWith("{")
                && node.textValue().endsWith("}");
    }

    private static boolean reference(JsonNode node) {
        return curly(node) || node != null && node.isObject() && node.has("$ref");
    }

    private record Located(JsonNode node, String pointer) {}

    private record Entry(String name, String type, JsonNode node, String pointer) {}

    private record Token(JsonNode node, TokenView view, String inheritedType) {}

    private record Value(JsonNode node, String type, String status) {}

    private static final class Work {
        private final ObjectNode source;
        private final DesignTokenDocument report = new DesignTokenDocument();
        private final Set<String> issueKeys = new HashSet<>();
        private final List<Entry> order = new ArrayList<>();
        private final LinkedHashMap<String, Entry> modifiers = new LinkedHashMap<>();
        private final Map<String, List<String>> sourceTokenPaths = new HashMap<>();
        private final Set<String> declarations = new HashSet<>();
        private int traversed;
        private boolean invalid;
        private boolean analyzeAll;
        private String context = "";

        private Work(ObjectNode source) {
            this.source = source;
            report.setStandardVersion(VERSION);
            report.setValidatorVersion(VALIDATOR_VERSION);
        }

        private void node() {
            if (++traversed > MAX_NODES) throw limit("nodes");
        }

        private void count(JsonNode value, int depth) {
            node();
            if (depth > MAX_DEPTH) throw limit("jsonDepth");
            if (value.isContainerNode()) for (JsonNode child : value) count(child, depth + 1);
        }

        private void chain(int length) {
            if (length > MAX_REFERENCES) throw limit("referenceDepth");
        }

        private void issue(String code, String at, String message) {
            invalid = true;
            String key = code + at + context;
            if (!issueKeys.add(key)) return;
            if (report.getIssues().size() == MAX_ISSUES) {
                report.setTruncated(true);
                return;
            }
            report.getIssues()
                    .add(
                            new Issue(
                                    code,
                                    "error",
                                    at,
                                    context.isEmpty()
                                            ? message
                                            : message + " [inputs " + context + "]"));
        }

        private DesignTokenDocument parse(Map<String, String> requested) {
            analyzeAll = requested == null;
            boolean resolver =
                    source.path("version").isValueNode()
                            || source.path("resolutionOrder").isArray()
                            || source.has("resolutionOrder")
                                    && !source.get("resolutionOrder").isObject();
            report.setFormat(resolver ? "resolver" : "tokens");
            if (!resolver) {
                if (requested != null && !requested.isEmpty())
                    issue("UNKNOWN_INPUT", "#", "Token documents do not define modifier inputs.");
                Projection projection = new Projection(source);
                projection.read();
                projection.publish();
            } else {
                prepareResolver();
                long permutationCount = 1;
                for (Entry modifier : modifiers.values()) {
                    permutationCount *= modifier.node.path("contexts").size();
                    if (permutationCount > MAX_PERMUTATIONS) throw limit("permutations");
                }
                List<Map<String, String>> combinations = permutations();
                Map<String, String> selected = select(requested);
                Projection chosen = null;
                if (requested == null) {
                    for (Map<String, String> inputs : combinations) {
                        context = inputs.toString();
                        Projection projection = resolve(inputs);
                        if (inputs.equals(selected)) chosen = projection;
                    }
                }
                context = "";
                if (chosen == null) chosen = resolve(selected);
                report.setInputs(selected);
                chosen.publish();
            }
            report.setValid(!invalid);
            return report;
        }

        private void metadata(JsonNode value, String at) {
            if (value.has("$description") && !value.get("$description").isTextual())
                issue("INVALID_METADATA", at + "/$description", "$description must be a string.");
            if (value.has("$extensions") && !value.get("$extensions").isObject())
                issue("INVALID_METADATA", at + "/$extensions", "$extensions must be an object.");
            if (value.has("$deprecated")
                    && !(value.get("$deprecated").isTextual()
                            || value.get("$deprecated").isBoolean()))
                issue(
                        "INVALID_METADATA",
                        at + "/$deprecated",
                        "$deprecated must be a boolean or a string.");
            if (value.has("$type")
                    && (!value.get("$type").isTextual()
                            || !DtcgTypes.TYPES.contains(value.get("$type").asText())))
                issue(
                        "INVALID_TYPE",
                        at + "/$type",
                        "$type must be a standard 2025.10 token type.");
        }

        private List<String> refPath(String ref, String at) {
            if (ref == null) {
                issue("INVALID_REFERENCE", at, "A reference must be a string.");
                return null;
            }
            if (ref.startsWith("{") && ref.endsWith("}")) {
                String content = ref.substring(1, ref.length() - 1);
                if (content.isEmpty()) {
                    issue("INVALID_REFERENCE", at, "A token reference must contain a token path.");
                    return null;
                }
                return List.of(content.split("\\.", -1));
            }
            if (!ref.startsWith("#")) {
                issue(
                        "EXTERNAL_REFERENCE_UNSUPPORTED",
                        at,
                        "External references are unsupported; bundle sources into this document. No URL or file is fetched.");
                return null;
            }
            if (ref.equals("#")) return List.of();
            if (!ref.startsWith("#/")) {
                issue(
                        "INVALID_REFERENCE",
                        at,
                        "Use a same-document RFC6901 JSON Pointer beginning with #/.");
                return null;
            }
            String decoded;
            try {
                // URI fragments use percent encoding; a literal plus is not a space.
                decoded =
                        java.net.URLDecoder.decode(
                                ref.substring(2).replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException error) {
                issue(
                        "INVALID_REFERENCE",
                        at,
                        "The JSON Pointer contains invalid percent encoding.");
                return null;
            }
            List<String> path = new ArrayList<>();
            for (String segment : decoded.split("/", -1)) {
                if (segment.matches(".*~(?:[^01]|$).*")) {
                    issue(
                            "INVALID_REFERENCE",
                            at,
                            "JSON Pointer escape sequences must be ~0 or ~1.");
                    return null;
                }
                path.add(segment.replace("~1", "/").replace("~0", "~"));
            }
            return List.copyOf(path);
        }

        private JsonNode at(JsonNode root, List<String> path) {
            JsonNode current = root;
            for (String part : path) {
                node();
                if (current == null) return null;
                if (current.isObject()) current = current.get(part);
                else if (current.isArray() && part.matches("0|[1-9][0-9]*") && part.length() < 10) {
                    try {
                        current = current.get(Integer.parseInt(part));
                    } catch (NumberFormatException error) {
                        return null;
                    }
                } else return null;
            }
            return current;
        }

        private Located dereference(
                JsonNode value, String at, Set<String> stack, boolean allowModifier) {
            node();
            if (!value.isObject() || !value.has("$ref")) return new Located(value, at);
            String ref = string(value, "$ref");
            List<String> path = refPath(ref, at + "/$ref");
            if (path == null) return new Located(JsonNodeFactory.instance.objectNode(), at);
            if (!path.isEmpty()
                    && (path.get(0).equals("resolutionOrder")
                            || path.get(0).equals("modifiers") && !allowModifier)) {
                issue(
                        "INVALID_REFERENCE_TARGET",
                        at,
                        "Only resolutionOrder may reference modifiers; resolutionOrder items cannot be referenced.");
                return new Located(JsonNodeFactory.instance.objectNode(), at);
            }
            String targetPointer = pointer(path);
            chain(stack.size() + 1);
            if (at.equals(targetPointer)
                    || at.startsWith(targetPointer + "/")
                    || !stack.add(targetPointer)) {
                issue(
                        "CIRCULAR_REFERENCE",
                        at,
                        "The resolver reference is circular or references its ancestor.");
                return new Located(JsonNodeFactory.instance.objectNode(), at);
            }
            JsonNode target = at(source, path);
            if (target == null) {
                issue(
                        "MISSING_REFERENCE",
                        at,
                        "The same-document reference target does not exist.");
                stack.remove(targetPointer);
                return new Located(JsonNodeFactory.instance.objectNode(), at);
            }
            Located resolved = dereference(target, targetPointer, stack, allowModifier);
            stack.remove(targetPointer);
            if (value.size() == 1) return resolved;
            if (!resolved.node.isObject()) {
                issue(
                        "INVALID_REFERENCE_TARGET",
                        at,
                        "Reference overrides require an object target.");
                return new Located(value, at);
            }
            count(resolved.node, 0);
            ObjectNode overrides = ((ObjectNode) resolved.node).deepCopy();
            value.fields()
                    .forEachRemaining(
                            field -> {
                                if (!field.getKey().equals("$ref"))
                                    overrides.set(field.getKey(), field.getValue());
                            });
            count(overrides, 0);
            return new Located(overrides, at);
        }

        private void prepareResolver() {
            resolverMetadata(source, "#");
            if (!VERSION.equals(string(source, "version")))
                issue("INVALID_STANDARD_VERSION", "#/version", "Resolver version must be 2025.10.");
            if (source.has("sets") && !source.get("sets").isObject())
                issue("INVALID_SET", "#/sets", "sets must be an object.");
            if (source.has("modifiers") && !source.get("modifiers").isObject())
                issue("INVALID_MODIFIER", "#/modifiers", "modifiers must be an object.");
            JsonNode rootModifiers = source.path("modifiers");
            if (rootModifiers.isObject())
                rootModifiers
                        .fields()
                        .forEachRemaining(
                                field -> {
                                    String at = "#/modifiers/" + escape(field.getKey());
                                    Located value =
                                            dereference(
                                                    field.getValue(),
                                                    at,
                                                    new LinkedHashSet<>(),
                                                    true);
                                    registerModifier(
                                            new Entry(
                                                    field.getKey(),
                                                    "modifier",
                                                    value.node,
                                                    value.pointer));
                                });
            JsonNode rootSets = source.path("sets");
            if (rootSets.isObject())
                rootSets.fields()
                        .forEachRemaining(
                                field -> {
                                    Located value =
                                            dereference(
                                                    field.getValue(),
                                                    "#/sets/" + escape(field.getKey()),
                                                    new LinkedHashSet<>(),
                                                    false);
                                    if (!value.node.isObject()
                                            || !value.node.path("sources").isArray())
                                        issue(
                                                "INVALID_SET",
                                                value.pointer,
                                                "A set must contain a sources array.");
                                    else {
                                        resolverMetadata(value.node, value.pointer);
                                        ObjectNode setTokens =
                                                analyzeAll
                                                        ? sources(
                                                                value.node.get("sources"),
                                                                value.pointer + "/sources",
                                                                new LinkedHashSet<>())
                                                        : JsonNodeFactory.instance.objectNode();
                                        List<List<String>> paths = new ArrayList<>();
                                        collectPaths(setTokens, List.of(), paths);
                                        report.getSets()
                                                .add(
                                                        new SetView(
                                                                field.getKey(),
                                                                string(value.node, "description"),
                                                                paths));
                                    }
                                });
            JsonNode resolutionOrder = source.path("resolutionOrder");
            if (!resolutionOrder.isArray()) {
                issue(
                        "INVALID_RESOLUTION_ORDER",
                        "#/resolutionOrder",
                        "resolutionOrder must be an array.");
                return;
            }
            Set<String> names = new HashSet<>();
            int index = 0;
            for (JsonNode raw : resolutionOrder) {
                String at = "#/resolutionOrder/" + index++;
                Located resolved = dereference(raw, at, new LinkedHashSet<>(), true);
                JsonNode value = resolved.node;
                String name = null, type = null;
                if (raw.isObject() && raw.has("$ref")) {
                    List<String> path = refPath(string(raw, "$ref"), at);
                    if (path != null
                            && path.size() == 2
                            && Set.of("sets", "modifiers").contains(path.get(0))) {
                        name = path.get(1);
                        type = path.get(0).equals("sets") ? "set" : "modifier";
                    }
                }
                if (name == null) {
                    name = string(value, "name");
                    type = string(value, "type");
                }
                if (name == null || !("set".equals(type) || "modifier".equals(type))) {
                    issue(
                            "INVALID_RESOLUTION_ORDER",
                            at,
                            "Inline resolution entries require name and type (set or modifier).");
                    continue;
                }
                if (!names.add(name))
                    issue(
                            "DUPLICATE_RESOLUTION_NAME",
                            at,
                            "Names in resolutionOrder must be unique.");
                Entry entry = new Entry(name, type, value, resolved.pointer);
                order.add(entry);
                if (type.equals("modifier")) registerModifier(entry);
                else if (report.getSets().stream()
                        .noneMatch(set -> set.getName().equals(entry.name)))
                    report.getSets()
                            .add(
                                    new SetView(
                                            name, string(value, "description"), new ArrayList<>()));
            }
        }

        private void resolverMetadata(JsonNode value, String at) {
            if (value.has("description") && !value.get("description").isTextual())
                issue("INVALID_METADATA", at + "/description", "description must be a string.");
            if (value.has("$extensions") && !value.get("$extensions").isObject())
                issue("INVALID_METADATA", at + "/$extensions", "$extensions must be an object.");
        }

        private void registerModifier(Entry entry) {
            resolverMetadata(entry.node, entry.pointer);
            JsonNode contexts = entry.node.path("contexts");
            if (!contexts.isObject() || contexts.isEmpty()) {
                issue(
                        "INVALID_MODIFIER",
                        entry.pointer,
                        "A modifier must declare a nonempty contexts map.");
                return;
            }
            if (contexts.size() == 1)
                issue(
                        "SINGLE_CONTEXT_MODIFIER",
                        entry.pointer,
                        "A modifier should declare at least two contexts; use a set for one context.");
            List<String> names = new ArrayList<>();
            contexts.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!contexts.get(name).isArray())
                    issue(
                            "INVALID_MODIFIER",
                            entry.pointer + "/contexts/" + escape(name),
                            "A context must be an array of token sources.");
                else if (analyzeAll)
                    sources(
                            contexts.get(name),
                            entry.pointer + "/contexts/" + escape(name),
                            new LinkedHashSet<>());
            }
            String defaultContext = string(entry.node, "default");
            if (entry.node.has("default")
                    && (defaultContext == null || !contexts.has(defaultContext)))
                issue(
                        "INVALID_DEFAULT_CONTEXT",
                        entry.pointer + "/default",
                        "The default must name a declared context.");
            if (!modifiers.containsKey(entry.name))
                report.getModifiers()
                        .add(
                                new ModifierView(
                                        entry.name,
                                        string(entry.node, "description"),
                                        names,
                                        defaultContext));
            for (ModifierView view : report.getModifiers()) {
                if (view.getName().equals(entry.name)) {
                    view.setDescription(string(entry.node, "description"));
                    view.setContexts(names);
                    view.setDefaultContext(defaultContext);
                }
            }
            modifiers.put(entry.name, entry);
        }

        private List<Map<String, String>> permutations() {
            List<Map<String, String>> result = new ArrayList<>();
            result.add(new LinkedHashMap<>());
            for (Entry modifier : modifiers.values()) {
                List<Map<String, String>> next = new ArrayList<>();
                for (Map<String, String> existing : result) {
                    Iterator<String> contexts = modifier.node.path("contexts").fieldNames();
                    while (contexts.hasNext()) {
                        node();
                        if (next.size() == MAX_PERMUTATIONS) throw limit("permutations");
                        Map<String, String> combination = new LinkedHashMap<>(existing);
                        combination.put(modifier.name, contexts.next());
                        next.add(combination);
                    }
                }
                result = next;
            }
            return result;
        }

        private Map<String, String> select(Map<String, String> requested) {
            Map<String, String> selected = new LinkedHashMap<>();
            if (requested != null)
                for (String key : requested.keySet())
                    if (!modifiers.containsKey(key))
                        issue(
                                "UNKNOWN_INPUT",
                                "#/modifiers",
                                "Input names must match declared modifiers.");
            for (Entry modifier : modifiers.values()) {
                String chosen = requested == null ? null : requested.get(modifier.name);
                JsonNode contexts = modifier.node.path("contexts");
                if (chosen == null) chosen = string(modifier.node, "default");
                if (chosen == null && requested == null) chosen = contexts.fieldNames().next();
                if (chosen == null)
                    issue(
                            "MISSING_INPUT",
                            modifier.pointer,
                            "This modifier has no default; supply an explicit context input.");
                else if (!contexts.has(chosen))
                    issue(
                            "INVALID_INPUT",
                            modifier.pointer,
                            "The input must name one of this modifier's declared contexts.");
                else selected.put(modifier.name, chosen);
            }
            return selected;
        }

        private Projection resolve(Map<String, String> inputs) {
            ObjectNode merged = JsonNodeFactory.instance.objectNode();
            for (Entry entry : order) {
                JsonNode sources =
                        entry.type.equals("set")
                                ? entry.node.path("sources")
                                : entry.node
                                        .path("contexts")
                                        .path(inputs.getOrDefault(entry.name, ""));
                String at =
                        entry.pointer
                                + (entry.type.equals("set")
                                        ? "/sources"
                                        : "/contexts/"
                                                + escape(inputs.getOrDefault(entry.name, "")));
                ObjectNode tokens = sources(sources, at, new LinkedHashSet<>());
                merged = merge(merged, tokens);
                if (entry.type.equals("set")) {
                    List<List<String>> paths = new ArrayList<>();
                    collectPaths(tokens, List.of(), paths);
                    for (SetView set : report.getSets())
                        if (set.getName().equals(entry.name)) set.setTokenPaths(paths);
                }
            }
            Projection projection = new Projection(merged);
            projection.read();
            return projection;
        }

        private ObjectNode sources(JsonNode array, String at, Set<String> stack) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            if (!array.isArray()) {
                issue("INVALID_SOURCES", at, "Token sources must be an array.");
                return result;
            }
            int index = 0;
            for (JsonNode raw : array) {
                String itemPointer = at + "/" + index++;
                Located value = dereference(raw, itemPointer, new LinkedHashSet<>(), false);
                chain(stack.size() + 1);
                if (!stack.add(value.pointer)) {
                    issue(
                            "CIRCULAR_REFERENCE",
                            itemPointer,
                            "Set sources form a circular reference.");
                    continue;
                }
                if (!value.node.isObject())
                    issue(
                            "INVALID_SOURCES",
                            itemPointer,
                            "Each source must be a token document or a reference to a set or token document.");
                else if (value.node.has("contexts"))
                    issue(
                            "INVALID_REFERENCE_TARGET",
                            itemPointer,
                            "Sets and modifier contexts cannot reference a modifier.");
                else if (value.node.has("sources"))
                    result =
                            merge(
                                    result,
                                    sources(
                                            value.node.get("sources"),
                                            value.pointer + "/sources",
                                            stack));
                else {
                    noteSource(value.node, List.of(), value.pointer);
                    result = merge(result, value.node);
                }
                stack.remove(value.pointer);
            }
            return result;
        }

        private void noteSource(JsonNode group, List<String> path, String at) {
            node();
            if (!group.isObject()) return;
            if (group.has("$value")) {
                if (declarations.add(at) && declarations.size() > MAX_TOKENS) throw limit("tokens");
                sourceTokenPaths.put(at, path);
                return;
            }
            group.fields()
                    .forEachRemaining(
                            field -> {
                                if (!field.getKey().startsWith("$")
                                        || field.getKey().equals("$root"))
                                    noteSource(
                                            field.getValue(),
                                            append(path, field.getKey()),
                                            at + "/" + escape(field.getKey()));
                            });
        }

        private void collectPaths(JsonNode group, List<String> path, List<List<String>> result) {
            node();
            if (!group.isObject()) return;
            if (group.has("$value")) {
                result.add(path);
                return;
            }
            group.fields()
                    .forEachRemaining(
                            field -> {
                                if (!field.getKey().startsWith("$")
                                        || field.getKey().equals("$root"))
                                    collectPaths(
                                            field.getValue(), append(path, field.getKey()), result);
                            });
        }

        private ObjectNode merge(JsonNode base, JsonNode local) {
            node();
            count(base, 0);
            ObjectNode result = ((ObjectNode) base).deepCopy();
            local.fields()
                    .forEachRemaining(
                            field -> {
                                node();
                                JsonNode previous = result.get(field.getKey()),
                                        next = field.getValue();
                                if (!field.getKey().startsWith("$")
                                        && previous != null
                                        && previous.isObject()
                                        && next.isObject()
                                        && !previous.has("$value")
                                        && !next.has("$value"))
                                    result.set(field.getKey(), merge(previous, next));
                                else result.set(field.getKey(), next);
                            });
            return result;
        }

        private final class Projection {
            private final ObjectNode declared;
            private ObjectNode effective;
            private final LinkedHashMap<List<String>, Token> tokens = new LinkedHashMap<>();
            private final List<GroupView> groups = new ArrayList<>();
            private final Map<List<String>, Value> values = new HashMap<>();

            private Projection(ObjectNode declared) {
                this.declared = declared;
            }

            private void read() {
                effective = extend(declared, List.of(), new LinkedHashSet<>());
                walk(effective, List.of(), null, null);
                for (List<String> path : tokens.keySet()) resolveToken(path, new LinkedHashSet<>());
            }

            private void publish() {
                report.setGroups(groups);
                report.setTokens(tokens.values().stream().map(Token::view).toList());
                report.setTokenCount(tokens.size());
                report.setGroupCount(groups.size());
            }

            private boolean valueReference(JsonNode value, Set<String> visited) {
                if (!value.isObject() || !value.has("$ref")) return false;
                List<String> path = refPath(string(value, "$ref"), "#");
                if (path == null) return true;
                chain(visited.size() + 1);
                if (!visited.add(pointer(path))) return true;
                JsonNode target = at(declared, path);
                if (target == null) target = at(source, path);
                if (target == null) return true;
                if (path.contains("$value") || !target.isObject() || target.has("$value"))
                    return true;
                if (target.has("$ref")) return valueReference(target, visited);
                if (value.has("$type")) {
                    Iterator<Map.Entry<String, JsonNode>> fields = target.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        if (!field.getKey().startsWith("$") && !field.getValue().isObject())
                            return true;
                    }
                }
                return false;
            }

            private ObjectNode extend(JsonNode group, List<String> path, Set<String> stack) {
                node();
                if (!group.has("$value")
                        && !group.has("$extends")
                        && valueReference(group, new LinkedHashSet<>())) {
                    count(group, 0);
                    ObjectNode token = ((ObjectNode) group).deepCopy();
                    JsonNode ref = token.remove("$ref");
                    token.set("$value", JsonNodeFactory.instance.objectNode().set("$ref", ref));
                    return token;
                }
                String at = pointer(path);
                chain(stack.size() + 1);
                if (!stack.add(at)) {
                    issue("CIRCULAR_REFERENCE", at, "Group extension is circular.");
                    return JsonNodeFactory.instance.objectNode();
                }
                ObjectNode result = JsonNodeFactory.instance.objectNode();
                JsonNode ref = group.has("$extends") ? group.get("$extends") : group.get("$ref");
                if (ref != null) {
                    List<String> targetPath =
                            refPath(
                                    ref.isTextual()
                                            ? ref.textValue()
                                            : ref.isObject() ? string(ref, "$ref") : null,
                                    at);
                    if (targetPath != null) {
                        JsonNode target = at(declared, targetPath);
                        if (target == null) target = at(source, targetPath);
                        if (target == null)
                            issue("MISSING_REFERENCE", at, "The inherited group does not exist.");
                        else if (!target.isObject() || target.has("$value"))
                            issue(
                                    "INVALID_REFERENCE_TARGET",
                                    at,
                                    "$extends must reference a group, not a token or primitive.");
                        else if (path.size() >= targetPath.size()
                                && path.subList(0, targetPath.size()).equals(targetPath))
                            issue(
                                    "CIRCULAR_REFERENCE",
                                    at,
                                    "A group cannot extend itself or an ancestor.");
                        else result = extend(target, targetPath, stack);
                    }
                }
                result = merge(result, group);
                result.remove("$extends");
                result.remove("$ref");
                List<String> keys = new ArrayList<>();
                result.fieldNames().forEachRemaining(keys::add);
                for (String key : keys) {
                    JsonNode child = result.get(key);
                    if ((!key.startsWith("$") || key.equals("$root"))
                            && child.isObject()
                            && !child.has("$value"))
                        result.set(key, extend(child, append(path, key), stack));
                }
                stack.remove(at);
                return result;
            }

            private void walk(
                    JsonNode group,
                    List<String> path,
                    String inheritedType,
                    JsonNode inheritedDeprecated) {
                node();
                String at = pointer(path);
                if (!group.isObject()) {
                    issue("INVALID_STRUCTURE", at, "Groups and tokens must be JSON objects.");
                    return;
                }
                metadata(group, at);
                String type = group.has("$type") ? string(group, "$type") : inheritedType;
                JsonNode deprecated =
                        group.has("$deprecated") ? group.get("$deprecated") : inheritedDeprecated;
                if (group.has("$value")) {
                    if (path.isEmpty()) {
                        issue(
                                "INVALID_STRUCTURE",
                                at,
                                "The document root must be a group containing named tokens.");
                        return;
                    }
                    group.fieldNames()
                            .forEachRemaining(
                                    key -> {
                                        if (!key.startsWith("$"))
                                            issue(
                                                    "TOKEN_GROUP_CONFLICT",
                                                    at,
                                                    "A token cannot also contain child groups or tokens.");
                                    });
                    if (tokens.size() == MAX_TOKENS) throw limit("tokens");
                    TokenView view =
                            new TokenView(
                                    path,
                                    at,
                                    path.get(path.size() - 1),
                                    string(group, "$type"),
                                    group.get("$value"),
                                    null,
                                    string(group, "$description"),
                                    group.get("$extensions"),
                                    deprecated,
                                    new ArrayList<>());
                    tokens.put(path, new Token(group, view, inheritedType));
                    return;
                }
                if (!path.isEmpty())
                    groups.add(
                            new GroupView(
                                    path,
                                    at,
                                    string(group, "$description"),
                                    type,
                                    group.get("$extensions")));
                group.fields()
                        .forEachRemaining(
                                field -> {
                                    String key = field.getKey();
                                    if (key.equals("$root")) {
                                        if (!field.getValue().isObject()
                                                || !field.getValue().has("$value"))
                                            issue(
                                                    "INVALID_ROOT_TOKEN",
                                                    at + "/$root",
                                                    "$root must be a token object with $value.");
                                        else
                                            walk(
                                                    field.getValue(),
                                                    append(path, key),
                                                    type,
                                                    deprecated);
                                    } else if (!key.startsWith("$")) {
                                        if (key.contains(".")
                                                || key.contains("{")
                                                || key.contains("}"))
                                            issue(
                                                    "INVALID_NAME",
                                                    at + "/" + escape(key),
                                                    "Token and group names must not contain periods or curly braces.");
                                        walk(field.getValue(), append(path, key), type, deprecated);
                                    } else if (!Set.of(
                                                    "$description",
                                                    "$type",
                                                    "$extensions",
                                                    "$deprecated",
                                                    "$schema",
                                                    "$defs")
                                            .contains(key))
                                        issue(
                                                "INVALID_NAME",
                                                at + "/" + escape(key),
                                                "Token and group names must not begin with $.");
                                });
            }

            private Value resolveToken(List<String> path, Set<String> stack) {
                if (values.containsKey(path)) return values.get(path);
                Token token = tokens.get(path);
                String at = pointer(path);
                if (token == null) return new Value(null, null, "missing");
                chain(stack.size() + 1);
                if (!stack.add(at + "/$value")) {
                    issue("CIRCULAR_REFERENCE", at, "Token aliases form a circular reference.");
                    return new Value(null, token.view.getType(), "cycle");
                }
                String explicit = string(token.node, "$type");
                String expected =
                        explicit != null
                                ? explicit
                                : reference(token.node.get("$value")) ? null : token.inheritedType;
                Value resolved =
                        resolveValue(
                                token.node.get("$value"),
                                expected,
                                token.view,
                                at + "/$value",
                                stack);
                String type =
                        explicit != null
                                ? explicit
                                : resolved.type != null ? resolved.type : token.inheritedType;
                token.view.setType(type);
                JsonNode result = resolved.node;
                if (type == null || !DtcgTypes.TYPES.contains(type)) {
                    issue(
                            "MISSING_TYPE",
                            at,
                            "A token needs a standard explicit, inherited or referenced type.");
                    result = null;
                } else if (result == null && resolved.status.equals("invalid")
                        || result != null && !DtcgTypes.valid(type, result)) {
                    issue(
                            "INVALID_VALUE",
                            at + "/$value",
                            "The effective value is invalid for token type " + type + ".");
                    result = null;
                }
                token.view.setResolvedValue(result);
                stack.remove(at + "/$value");
                Value value =
                        new Value(
                                result,
                                type,
                                result == null
                                        ? resolved.status.equals("resolved")
                                                ? "invalid"
                                                : resolved.status
                                        : "resolved");
                values.put(path, value);
                return value;
            }

            private Value resolveValue(
                    JsonNode raw, String expected, TokenView owner, String at, Set<String> stack) {
                node();
                if (reference(raw)) return alias(raw, expected, owner, at, stack);
                if (raw == null || raw.isNull()) return new Value(null, expected, "invalid");
                if (raw.isArray()) {
                    ArrayNode array = JsonNodeFactory.instance.arrayNode();
                    int index = 0;
                    String failed = null;
                    for (JsonNode item : raw) {
                        Value child =
                                resolveValue(
                                        item,
                                        DtcgTypes.childType(expected, "", true),
                                        owner,
                                        at + "/" + index++,
                                        stack);
                        if (child.node == null) failed = child.status;
                        else array.add(child.node);
                    }
                    return new Value(
                            failed == null ? array : null,
                            expected,
                            failed == null ? "resolved" : failed);
                }
                if (raw.isObject()) {
                    ObjectNode object = JsonNodeFactory.instance.objectNode();
                    String failed = null;
                    Iterator<Map.Entry<String, JsonNode>> fields = raw.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        Value child =
                                resolveValue(
                                        field.getValue(),
                                        DtcgTypes.childType(expected, field.getKey(), false),
                                        owner,
                                        at + "/" + escape(field.getKey()),
                                        stack);
                        if (child.node == null) {
                            failed = child.status;
                            continue;
                        }
                        JsonNode value = child.node;
                        if ("gradient".equals(expected)
                                && "position".equals(field.getKey())
                                && value.isNumber())
                            value =
                                    JsonNodeFactory.instance.numberNode(
                                            value.decimalValue()
                                                    .max(BigDecimal.ZERO)
                                                    .min(BigDecimal.ONE));
                        object.set(field.getKey(), value);
                    }
                    return new Value(
                            failed == null ? object : null,
                            expected,
                            failed == null ? "resolved" : failed);
                }
                return new Value(raw, expected, "resolved");
            }

            private Value alias(
                    JsonNode raw, String expected, TokenView owner, String at, Set<String> stack) {
                String ref = curly(raw) ? raw.textValue() : string(raw, "$ref");
                List<String> path = refPath(ref, at);
                AliasView alias = new AliasView(ref, null, "invalid");
                owner.getAliases().add(alias);
                if (path == null) return new Value(null, expected, "invalid");
                if (raw.isObject() && raw.size() != 1) {
                    issue(
                            "INVALID_REFERENCE",
                            at,
                            "Token value reference objects must contain only $ref.");
                    return new Value(null, expected, "invalid");
                }
                boolean tokenReference = curly(raw);
                List<String> tokenPath = tokenReference ? path : null;
                List<String> remainder = List.of();
                if (!tokenReference) {
                    for (int size = path.size(); size > 0; size--) {
                        List<String> prefix = path.subList(0, size);
                        List<String> canonical =
                                tokens.containsKey(prefix)
                                        ? List.copyOf(prefix)
                                        : sourceTokenPaths.get(pointer(prefix));
                        if (canonical != null && tokens.containsKey(canonical)) {
                            tokenPath = canonical;
                            remainder = List.copyOf(path.subList(size, path.size()));
                            break;
                        }
                    }
                }
                if (tokenPath != null) alias.setTargetPath(List.copyOf(tokenPath));
                Value target;
                if (tokenReference
                        || tokenPath != null
                                && (remainder.isEmpty() || remainder.equals(List.of("$value"))))
                    target = resolveToken(tokenPath, stack);
                else {
                    String referencePointer = pointer(path);
                    chain(stack.size() + 1);
                    if (!stack.add(referencePointer)) {
                        issue(
                                "CIRCULAR_REFERENCE",
                                at,
                                "Property references form a circular reference.");
                        target = new Value(null, expected, "cycle");
                    } else {
                        JsonNode value =
                                tokenPath != null
                                        ? at(tokens.get(tokenPath).node, remainder)
                                        : at(effective, path);
                        if (value == null && tokenPath == null) value = at(source, path);
                        if (value == null) target = new Value(null, expected, "missing");
                        else target = resolveValue(value, expected, owner, referencePointer, stack);
                        stack.remove(referencePointer);
                    }
                }
                if (target.node == null) {
                    alias.setStatus(target.status);
                    issue(
                            target.status.equals("cycle")
                                    ? "CIRCULAR_REFERENCE"
                                    : target.status.equals("missing")
                                            ? "MISSING_REFERENCE"
                                            : "INVALID_REFERENCE_TARGET",
                            at,
                            "The alias target is missing, circular or invalid.");
                    return target;
                }
                if (expected != null && target.type != null && !expected.equals(target.type)) {
                    alias.setStatus("invalid");
                    issue(
                            "REFERENCE_TYPE_MISMATCH",
                            at,
                            "The alias target type must match the receiving token or component type.");
                    return new Value(null, expected, "invalid");
                }
                alias.setStatus("resolved");
                return new Value(
                        target.node, target.type == null ? expected : target.type, "resolved");
            }
        }
    }
}
