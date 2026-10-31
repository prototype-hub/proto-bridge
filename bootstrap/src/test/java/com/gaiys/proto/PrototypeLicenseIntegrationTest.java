package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeLicenseProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import com.gaiys.proto.prototypehub.service.PrototypeInstallationService;
import com.gaiys.proto.prototypehub.service.PrototypeLicenseService;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实HTTP和PostgreSQL的安装锁、单调序列、动作权限及实时ADMIN边界。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000",
            "prototype-hub.capacity.project-limit=-1",
            "prototype-hub.capacity.version-limit=999"
        })
@Testcontainers
class PrototypeLicenseIntegrationTest {
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final String PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        p.add("spring.r2dbc.username", DATABASE::getUsername);
        p.add("spring.r2dbc.password", DATABASE::getPassword);
        p.add("proto.admin.password", () -> PASSWORD);
        p.add("spring.r2dbc.pool.max-size", () -> 6);
        p.add(
                "prototype-hub.license.trusted-public-keys[test]",
                () ->
                        Base64.getEncoder()
                                .encodeToString(
                                        PrototypeLicenseVerificationTest.KEY
                                                .getPublic()
                                                .getEncoded()));
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PrototypeInstallationService installations;
    @Autowired PrototypeLicenseService licenses;
    @Autowired PrototypeLicenseProperties trust;
    String admin, installation;

    @BeforeEach
    void reset() {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
        trust.setTrustedPublicKeys(PrototypeLicenseVerificationTest.trust().getTrustedPublicKeys());
        installations
                .createUpdate()
                .setNull(PrototypeInstallationEntity::getLicenseDocument)
                .setNull(PrototypeInstallationEntity::getLicenseId)
                .setNull(PrototypeInstallationEntity::getLicenseSequence)
                .setNull(PrototypeInstallationEntity::getLicenseFingerprint)
                .setNull(PrototypeInstallationEntity::getLicenseAcceptedAt)
                .setNull(PrototypeInstallationEntity::getLicenseKeyId)
                .where(PrototypeInstallationEntity::getId, "default")
                .execute()
                .block(TIMEOUT);
        installation = installations.current().block(TIMEOUT).getInstallationId();
    }

    JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        var response = body == null ? request.exchange() : request.bodyValue(body).exchange();
        var value =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(value);
        return value;
    }

    JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    String login(String username) {
        return result(
                        HttpMethod.POST,
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD))
                .path("token")
                .asText();
    }

    JsonNode status() {
        return result(HttpMethod.GET, "/prototype-hub/license", admin, null);
    }

    String document(long sequence) {
        return PrototypeLicenseVerificationTest.sign(
                PrototypeLicenseVerificationTest.payload(installation, sequence));
    }

    JsonNode upload(String document) {
        return result(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", document));
    }

    @Test
    void missingCredentialKeepsFreeDefaultAndReturnsNoSecrets() throws Exception {
        JsonNode value = status();
        assertEquals("missing", value.path("status").asText());
        assertEquals(installation, value.path("installationId").asText());
        assertTrue(value.path("acceptedCredential").isNull());
        assertTrue(value.path("verifiedEntitlements").isNull());
        assertEquals(5, value.path("freeEntitlements").path("maxProjects").asInt());
        assertEquals(5, value.path("enforcedCapacity").path("projectLimit").asInt());
        String json =
                PrototypeLicenseVerificationTest.MAPPER.writeValueAsString(
                        installations.current().block(TIMEOUT));
        assertFalse(json.contains("statisticsSigningKey"));
        assertFalse(json.contains("licenseDocument"));
    }

    @Test
    void atomicImportAndReplayKeepOriginalAcceptanceAndNeverExecuteClaims() {
        JsonNode first = upload(document(1));
        assertEquals("imported", first.path("result").asText());
        assertEquals("valid", first.path("status").path("status").asText());
        JsonNode accepted = first.path("status").path("acceptedCredential");
        assertEquals("unchanged", upload(document(1)).path("result").asText());
        assertEquals(accepted, status().path("acceptedCredential"));
        assertEquals(12, status().path("verifiedEntitlements").path("maxProjects").asInt());
        assertEquals(5, status().path("enforcedCapacity").path("projectLimit").asInt());
        assertFalse(status().path("verifiedEntitlements").has("orderId"));
        assertFalse(status().toString().contains("signature"));
    }

    @Test
    void invalidAndStaleImportsPreserveTheLastAcceptedDocument() {
        upload(document(2));
        JsonNode accepted = status().path("acceptedCredential");
        var response =
                call(
                        HttpMethod.POST,
                        "/prototype-hub/license/_import",
                        admin,
                        Map.of("document", document(1)),
                        409);
        assertEquals("PROTOTYPE_HUB_LICENSE_SEQUENCE_CONFLICT", response.path("code").asText());
        var payload = PrototypeLicenseVerificationTest.payload(installation, 2);
        payload.put("customer", "Changed");
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", PrototypeLicenseVerificationTest.sign(payload)),
                409);
        payload.put("licenseId", "other-lineage");
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", PrototypeLicenseVerificationTest.sign(payload)),
                409);
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", "invalid"),
                400);
        assertEquals(accepted, status().path("acceptedCredential"));
    }

    @Test
    void simultaneousSameEnvelopeCreatesOneDurableAcceptance() {
        String document = document(1);
        List<CompletableFuture<JsonNode>> futures =
                java.util.stream.IntStream.range(0, 12)
                        .mapToObj(i -> CompletableFuture.supplyAsync(() -> upload(document)))
                        .toList();
        var values = futures.stream().map(CompletableFuture::join).toList();
        assertEquals(
                1,
                values.stream()
                        .filter(value -> value.path("result").asText().equals("imported"))
                        .count());
        assertEquals(
                11,
                values.stream()
                        .filter(value -> value.path("result").asText().equals("unchanged"))
                        .count());
        assertEquals(1, status().path("acceptedCredential").path("sequence").asLong());
    }

    @Test
    void trustRemovalAndRestorationRevalidatePersistedClaimsWithoutDataLoss() {
        upload(document(1));
        JsonNode accepted = status().path("acceptedCredential");
        trust.setTrustedPublicKeys(Map.of());
        assertEquals("untrusted", status().path("status").asText());
        assertTrue(status().path("verifiedEntitlements").isNull());
        assertEquals(accepted, status().path("acceptedCredential"));
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", document(2)),
                400);
        trust.setTrustedPublicKeys(PrototypeLicenseVerificationTest.trust().getTrustedPublicKeys());
        installations.initialize().block(TIMEOUT);
        assertEquals("valid", status().path("status").asText());
        assertEquals(accepted, status().path("acceptedCredential"));
    }

    @Test
    void untrustedWrongInstallationAndFutureAreRejectedBeforeAcceptance() {
        trust.setTrustedPublicKeys(Map.of());
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", document(1)),
                400);
        assertTrue(status().path("acceptedCredential").isNull());
        trust.setTrustedPublicKeys(PrototypeLicenseVerificationTest.trust().getTrustedPublicKeys());
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of(
                        "document",
                        PrototypeLicenseVerificationTest.sign(
                                PrototypeLicenseVerificationTest.payload(
                                        UUID.randomUUID().toString(), 1))),
                400);
        var payload = PrototypeLicenseVerificationTest.payload(installation, 1);
        payload.put("notBefore", System.currentTimeMillis() + 600000);
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                admin,
                Map.of("document", PrototypeLicenseVerificationTest.sign(payload)),
                400);
        assertEquals("missing", status().path("status").asText());
    }

    @Test
    void projectManagersAndAnonymousCannotReadOrImportInstallationCredentials() {
        call(HttpMethod.GET, "/prototype-hub/license", null, null, 401);
        String key = UUID.randomUUID().toString().replace("-", "");
        result(HttpMethod.POST, "/role", admin, Map.of("id", key, "name", "License boundary test"));
        result(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        UUID.randomUUID().toString().replace("-", ""),
                        "permission",
                        "prototype-hub-license",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        key,
                        "actions",
                        List.of("query", "save"),
                        "state",
                        1));
        String username = "license-" + key;
        String id =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "License boundary test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + key + "/users/_bind", admin, List.of(id));
        String token = login(username);
        call(HttpMethod.GET, "/prototype-hub/license", token, null, 403);
        call(
                HttpMethod.POST,
                "/prototype-hub/license/_import",
                token,
                Map.of("document", document(1)),
                403);
        result(HttpMethod.POST, "/role/ADMIN/users/_bind", admin, List.of(id));
        token = login(username);
        assertEquals(
                installation,
                result(HttpMethod.GET, "/prototype-hub/license", token, null)
                        .path("installationId")
                        .asText());
        database.sql("delete from s_dimension_user where dimension_id=:role and user_id=:user")
                .bind("role", "ADMIN")
                .bind("user", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, "/prototype-hub/license", token, null, 403);
    }
}
