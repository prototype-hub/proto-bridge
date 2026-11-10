package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeLicenseProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.license.PrototypeLicenseVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 本地生成测试密钥，验证真实Ed25519和严格JSON边界；不包含任何发行方私钥。 */
class PrototypeLicenseVerificationTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String INSTALLATION = UUID.randomUUID().toString();
    static final KeyPair KEY = keyPair();
    static final long NOW = 1793404800000L;

    static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    static PrototypeLicenseProperties trust() {
        var value = new PrototypeLicenseProperties();
        value.setTrustedPublicKeys(
                Map.of("test", Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded())));
        return value;
    }

    static ObjectNode payload(String installation, long sequence) {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("schemaVersion", 2);
        value.put("licenseId", "test-lineage");
        value.put("sequence", sequence);
        value.put("orderId", "test-order");
        value.put("installationId", installation);
        value.put("customer", "测试客户😀\"\\");
        value.put("edition", "declaration");
        value.putArray("features").add("example-feature");
        value.put("maxProjects", 12);
        value.put("maxVersionsPerProject", 20);
        value.put("issuedAt", NOW - 1000);
        value.put("notBefore", NOW - 1000);
        value.putNull("trialEndsAt");
        value.putNull("maintenanceUntil");
        return value;
    }

    static ObjectNode uniformPayload(
            String installation, long sequence, int projects, int versions) {
        ObjectNode value = payload(installation, sequence);
        value.put("maxProjects", projects);
        value.put("maxVersionsPerProject", versions);
        return value;
    }

    static String sign(ObjectNode payload) {
        return sign(payload, "test", KEY);
    }

    static String sign(ObjectNode payload, String keyId, KeyPair key) {
        try {
            var verifier = new PrototypeLicenseVerifier(MAPPER, trust());
            ObjectNode signed = MAPPER.createObjectNode();
            signed.put("keyId", keyId);
            signed.set("payload", payload);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(verifier.canonical(signed).getBytes(StandardCharsets.UTF_8));
            signed.put(
                    "signature",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
            return signed.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private PrototypeLicenseVerifier verifier() {
        return new PrototypeLicenseVerifier(MAPPER, trust());
    }

    private void rejects(String code, String document) {
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> verifier().verify(document, INSTALLATION, NOW));
        assertEquals("PROTOTYPE_HUB_LICENSE_" + code, error.getCode());
    }

    @Test
    void validUnicodeEnvelopeNormalizesAndFingerprintsIndependentlyOfOuterWhitespace()
            throws Exception {
        String document = sign(payload(INSTALLATION, 1));
        var value = verifier().verify(document, INSTALLATION, NOW);
        assertEquals("测试客户😀\"\\", value.payload().path("customer").asText());
        assertEquals(64, value.fingerprint().length());
        assertEquals(
                value.fingerprint(),
                verifier().verify(" \n" + document + " ", INSTALLATION, NOW).fingerprint());
        assertEquals(value.document(), verifier().canonical(MAPPER.readTree(document)));
    }

    @Test
    void uniformModelHasOnlyAbsoluteTotalsAndRejectsMixedFields() {
        var verified =
                verifier().verify(sign(uniformPayload(INSTALLATION, 1, 12, 20)), INSTALLATION, NOW);
        var claims =
                com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus
                        .VerifiedEntitlements.from(verified.payload());
        assertEquals(2, claims.schemaVersion());
        assertEquals(20, claims.maxVersionsPerProject());
        ObjectNode mixed = uniformPayload(INSTALLATION, 1, 12, 20);
        mixed.putArray("projectVersionGrants");
        rejects("DOCUMENT_INVALID", sign(mixed));
        mixed = uniformPayload(INSTALLATION, 1, 12, 20);
        mixed.put("maxVersionsPerProject", 20.0);
        rejects("DOCUMENT_INVALID", sign(mixed));
        mixed.put("maxVersionsPerProject", 4);
        rejects("DOCUMENT_INVALID", sign(mixed));
    }

    @Test
    void legacyCredentialIsRejectedWithoutInterpretingProjectBlocks() {
        ObjectNode old = payload(INSTALLATION, 1);
        old.put("schemaVersion", 1);
        old.remove("maxVersionsPerProject");
        old.put("baseVersionsPerProject", 5);
        old.putArray("projectVersionGrants")
                .addObject()
                .put("projectId", "old-test-project")
                .put("extraVersionBlocks", 2);
        rejects("SCHEMA_UNSUPPORTED", sign(old));
    }

    @Test
    void tamperingAndNonCanonicalSignaturesFail() throws Exception {
        ObjectNode value = (ObjectNode) MAPPER.readTree(sign(payload(INSTALLATION, 1)));
        ((ObjectNode) value.get("payload")).put("maxProjects", 13);
        rejects("SIGNATURE_INVALID", value.toString());
        value = (ObjectNode) MAPPER.readTree(sign(payload(INSTALLATION, 1)));
        value.put("signature", value.path("signature").asText() + "=");
        rejects("DOCUMENT_INVALID", value.toString());
    }

    @Test
    void unsupportedWrongInstallationAndFutureCannotBeImported() {
        ObjectNode value = payload(INSTALLATION, 1);
        value.put("schemaVersion", 3);
        rejects("SCHEMA_UNSUPPORTED", sign(value));
        rejects("INSTALLATION_MISMATCH", sign(payload(UUID.randomUUID().toString(), 1)));
        value = payload(INSTALLATION, 1);
        value.put("notBefore", NOW + 1);
        rejects("NOT_YET_VALID", sign(value));
    }

    @Test
    void duplicateUnknownTrailingOrDeepFieldsAreRejected() {
        String document = sign(payload(INSTALLATION, 1));
        rejects(
                "DOCUMENT_INVALID",
                document.replace("\"keyId\":\"test\"", "\"keyId\":\"test\",\"keyId\":\"test\""));
        rejects("DOCUMENT_INVALID", document + " {}");
        ObjectNode value = payload(INSTALLATION, 1);
        value.put("expiresAt", NOW);
        rejects("DOCUMENT_INVALID", sign(value));
        rejects("DOCUMENT_INVALID", "[".repeat(20) + "0" + "]".repeat(20));
    }

    @Test
    void decimalsUnsafeIntegersAndUnknownAlgorithmCannotPassShapeValidation() {
        ObjectNode value = payload(INSTALLATION, 1);
        value.put("sequence", 1.0);
        rejects("DOCUMENT_INVALID", sign(value));
        value = payload(INSTALLATION, 1);
        value.put("sequence", 9007199254740992L);
        rejects("DOCUMENT_INVALID", sign(value));
        value = payload(INSTALLATION, 1);
        value.put("maxVersionsPerProject", 4);
        rejects("DOCUMENT_INVALID", sign(value));
        rejects(
                "DOCUMENT_INVALID",
                sign(payload(INSTALLATION, 1))
                        .replace(
                                "\"keyId\":\"test\"", "\"algorithm\":\"none\",\"keyId\":\"test\""));
    }

    @Test
    void duplicateGrantFeatureAndInvalidUnicodeOrControlTextAreRejected() {
        ObjectNode value = payload(INSTALLATION, 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) value.get("features"))
                .add("example-feature");
        rejects("DOCUMENT_INVALID", sign(value));
        value = payload(INSTALLATION, 1);
        value.put("customer", "bad\u001btext");
        rejects("DOCUMENT_INVALID", sign(value));
        value = payload(INSTALLATION, 1);
        value.put("customer", "bad\uD800text");
        rejects("DOCUMENT_INVALID", sign(value));
    }

    @Test
    void absentTrustNeverAcceptsInputProvidedKey() {
        String document = sign(payload(INSTALLATION, 1));
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () ->
                                new PrototypeLicenseVerifier(
                                                MAPPER, new PrototypeLicenseProperties())
                                        .verify(document, INSTALLATION, NOW));
        assertEquals("PROTOTYPE_HUB_LICENSE_UNTRUSTED_KEY", error.getCode());
    }

    @Test
    void oversizedUtf8RejectedBeforeParsing() {
        rejects("DOCUMENT_INVALID", "中".repeat(22000));
    }

    @Test
    void applicationWideLenientJsonOptionsCannotWeakenCredentialParsing() {
        ObjectMapper lenient =
                new ObjectMapper()
                        .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES);
        var verifier = new PrototypeLicenseVerifier(lenient, trust());
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> verifier.verify("{'keyId':'test'}", INSTALLATION, NOW));
        assertEquals("PROTOTYPE_HUB_LICENSE_DOCUMENT_INVALID", error.getCode());
    }

    @Test
    void datesBeyondBrowserRangeAreRejected() {
        ObjectNode value = payload(INSTALLATION, 1);
        value.put("maintenanceUntil", 8640000000000001L);
        rejects("DOCUMENT_INVALID", sign(value));
    }

    @Test
    void expiredTrialAndMaintenanceAreDeclarationsAndDoNotExpirePermanentLicense() {
        ObjectNode value = payload(INSTALLATION, 1);
        value.put("trialEndsAt", NOW - 1);
        value.put("maintenanceUntil", NOW - 1);
        assertEquals(
                12,
                verifier()
                        .verify(sign(value), INSTALLATION, NOW)
                        .payload()
                        .path("maxProjects")
                        .asInt());
    }
}
