package com.gaiys.proto.prototypehub.web.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded, inspectable DTCG projection; arbitrary metadata remains inert JSON. */
@Data
public class DesignTokenDocument {
    private String format;
    private String standardVersion;
    private String validatorVersion;
    private boolean valid;
    private int tokenCount;
    private int groupCount;
    private List<Issue> issues = new ArrayList<>();
    private boolean truncated;
    private List<SetView> sets = new ArrayList<>();
    private List<ModifierView> modifiers = new ArrayList<>();
    private Map<String, String> inputs = new LinkedHashMap<>();
    private List<GroupView> groups = new ArrayList<>();
    private List<TokenView> tokens = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        private String code;
        private String severity;
        private String pointer;
        private String message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SetView {
        private String name;
        private String description;
        private List<List<String>> tokenPaths;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModifierView {
        private String name;
        private String description;
        private List<String> contexts;
        private String defaultContext;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupView {
        private List<String> path;
        private String pointer;
        private String description;
        private String type;
        private JsonNode extensions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenView {
        private List<String> path;
        private String pointer;
        private String name;
        private String type;
        private JsonNode declaredValue;
        private JsonNode resolvedValue;
        private String description;
        private JsonNode extensions;
        private JsonNode deprecated;
        private List<AliasView> aliases = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AliasView {
        private String reference;
        private List<String> targetPath;
        private String status;
    }
}
