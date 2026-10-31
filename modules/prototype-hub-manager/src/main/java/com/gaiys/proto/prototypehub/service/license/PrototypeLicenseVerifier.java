package com.gaiys.proto.prototypehub.service.license;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeLicenseProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 有界v1凭证解析与公钥验签。只验证声明，不签发凭证，也不执行容量或高级功能授权。 */
@Component
public class PrototypeLicenseVerifier {
    public static final int MAX_BYTES = 65536;
    private static final Set<String> PAYLOAD =
            Set.of(
                    "schemaVersion",
                    "licenseId",
                    "sequence",
                    "orderId",
                    "installationId",
                    "customer",
                    "edition",
                    "features",
                    "maxProjects",
                    "baseVersionsPerProject",
                    "projectVersionGrants",
                    "issuedAt",
                    "notBefore",
                    "trialEndsAt",
                    "maintenanceUntil");
    private final ObjectMapper mapper;
    private final JsonFactory factory;
    private final PrototypeLicenseProperties properties;

    public PrototypeLicenseVerifier(ObjectMapper mapper, PrototypeLicenseProperties properties) {
        this.mapper = mapper;
        this.factory = mapper.getFactory().copy();
        this.factory.setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(12)
                        .maxNumberLength(20)
                        .maxStringLength(MAX_BYTES)
                        .build());
        this.properties = properties;
    }

    /** 规范化封套和摘要用于持久化/未知提交结果核对；不读取任何客户端公钥。 */
    public Verified verify(String document, String installationId, long now) {
        try {
            if (document == null || document.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw failure("DOCUMENT_INVALID");
            JsonNode envelope;
            try (JsonParser parser = factory.createParser(document)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                for (JsonReadFeature feature : JsonReadFeature.values()) {
                    parser.disable(feature.mappedFeature());
                }
                envelope =
                        mapper.readerFor(JsonNode.class)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readValue(parser);
            }
            fields(envelope, Set.of("keyId", "payload", "signature"));
            String keyId = identifier(envelope, "keyId");
            JsonNode payload = envelope.get("payload");
            fields(payload, PAYLOAD);
            if (integer(payload, "schemaVersion", 1, 2147483647L) != 1)
                throw failure("SCHEMA_UNSUPPORTED");
            identifier(payload, "licenseId");
            identifier(payload, "orderId");
            identifier(payload, "edition");
            integer(payload, "sequence", 1, 9007199254740991L);
            String installation = text(payload, "installationId", 36);
            if (!UUID.fromString(installation).toString().equals(installation))
                throw failure("DOCUMENT_INVALID");
            text(payload, "customer", 256);
            integer(payload, "maxProjects", 5, Integer.MAX_VALUE);
            integer(payload, "baseVersionsPerProject", 5, 5);
            long issued = integer(payload, "issuedAt", 0, 8640000000000000L);
            long notBefore = integer(payload, "notBefore", 0, 8640000000000000L);
            nullableDate(payload, "trialEndsAt");
            nullableDate(payload, "maintenanceUntil");
            JsonNode features = payload.get("features");
            if (!features.isArray() || features.size() > 64) throw failure("DOCUMENT_INVALID");
            Set<String> unique = new HashSet<>();
            for (JsonNode feature : features) {
                if (!feature.isTextual()
                        || !feature.textValue().matches("[A-Za-z0-9._-]{1,64}")
                        || !unique.add(feature.textValue())) throw failure("DOCUMENT_INVALID");
            }
            JsonNode grants = payload.get("projectVersionGrants");
            if (!grants.isArray() || grants.size() > 512) throw failure("DOCUMENT_INVALID");
            unique.clear();
            for (JsonNode grant : grants) {
                fields(grant, Set.of("projectId", "extraVersionBlocks"));
                if (!unique.add(identifier(grant, "projectId"))) throw failure("DOCUMENT_INVALID");
                integer(grant, "extraVersionBlocks", 1, (Integer.MAX_VALUE - 5L) / 5);
            }
            var key = properties.key(keyId);
            if (key == null) throw failure("UNTRUSTED_KEY");
            String encodedSignature = text(envelope, "signature", 86);
            byte[] signature = Base64.getUrlDecoder().decode(encodedSignature);
            if (signature.length != 64
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(signature)
                            .equals(encodedSignature)) throw failure("SIGNATURE_INVALID");
            ObjectNode signed = mapper.createObjectNode();
            signed.put("keyId", keyId);
            signed.set("payload", payload);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonical(signed).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signature)) throw failure("SIGNATURE_INVALID");
            if (!installation.equals(installationId)) throw failure("INSTALLATION_MISMATCH");
            if (issued > now || notBefore > now) throw failure("NOT_YET_VALID");
            String canonical = canonical(envelope);
            String fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Verified(canonical, fingerprint, keyId, payload.deepCopy());
        } catch (PrototypeHubException error) {
            throw error;
        } catch (java.io.IOException
                | java.security.GeneralSecurityException
                | IllegalArgumentException error) {
            throw failure("DOCUMENT_INVALID");
        }
    }

    /** v1仅允许整数和固定层级；按UTF-16键序排序，数组顺序保留，签名不受外层空白或键顺序影响。 */
    public String canonical(JsonNode node) throws java.io.IOException {
        return mapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .without(JsonGenerator.Feature.ESCAPE_NON_ASCII)
                .writeValueAsString(sorted(node));
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var values = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));
            ObjectNode result = mapper.createObjectNode();
            values.forEach((key, value) -> result.set(key, sorted(value)));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size())
            throw failure("DOCUMENT_INVALID");
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!expected.contains(name)) throw failure("DOCUMENT_INVALID");
                        });
    }

    private static String identifier(JsonNode node, String field) {
        String value = text(node, field, 64);
        if (!value.matches("[A-Za-z0-9._-]{1,64}")) throw failure("DOCUMENT_INVALID");
        return value;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > max) throw failure("DOCUMENT_INVALID");
        String text = value.textValue();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current < 0x20) throw failure("DOCUMENT_INVALID");
            if (Character.isHighSurrogate(current)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw failure("DOCUMENT_INVALID");
            } else if (Character.isLowSurrogate(current)) throw failure("DOCUMENT_INVALID");
        }
        return text;
    }

    private static long integer(JsonNode node, String field, long min, long max) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < min
                || value.longValue() > max) throw failure("DOCUMENT_INVALID");
        return value.longValue();
    }

    private static void nullableDate(JsonNode node, String field) {
        if (!node.get(field).isNull()) integer(node, field, 0, 8640000000000000L);
    }

    public static PrototypeHubException failure(String suffix) {
        return new PrototypeHubException(
                "error.prototype-hub.license_" + suffix.toLowerCase(java.util.Locale.ROOT),
                "PROTOTYPE_HUB_LICENSE_" + suffix,
                suffix.endsWith("CONFLICT") ? 409 : 400);
    }

    /** 已验的规范封套只由持久层接收，不能直接从Web接口返回。 */
    public record Verified(String document, String fingerprint, String keyId, JsonNode payload) {}
}
