package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.logging.store.LogStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=classpath:application-default.yml")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InfrastructureIntegrationTest {
    static final String PASSWORD = UUID.randomUUID().toString(),
            REDIS_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine")
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                    .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> ES =
            new GenericContainer<>("elasticsearch:8.19.19")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200)
                    .waitingFor(
                            Wait.forHttp("/")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(5)));

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry p) {
        p.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DB.getHost()
                                + ":"
                                + DB.getMappedPort(5432)
                                + "/"
                                + DB.getDatabaseName());
        p.add("spring.r2dbc.username", DB::getUsername);
        p.add("spring.r2dbc.password", DB::getPassword);
        p.add("spring.data.redis.host", REDIS::getHost);
        p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        p.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        p.add("proto.redis.enabled", () -> true);
        p.add("hsweb.cache.type", () -> "redis");
        p.add(
                "spring.elasticsearch.uris",
                () -> "http://" + ES.getHost() + ":" + ES.getMappedPort(9200));
        p.add("proto.logging.elasticsearch.enabled", () -> true);
        p.add("proto.logging.elasticsearch.data-stream", () -> "proto-integration-logs");
        p.add("proto.logging.elasticsearch.ilm-policy", () -> "proto-integration-retention");
        p.add("proto.logging.elasticsearch.flush-interval", () -> "100ms");
        p.add("proto.logging.elasticsearch.retry-backoff", () -> "50ms");
        p.add("captcha.enabled", () -> true);
        p.add("proto.security.login-encryption-enabled", () -> true);
        p.add("proto.admin.password", () -> PASSWORD);
        p.add("hsweb.cors.enable", () -> true);
        p.add("logging.file.name", () -> "target/test-logs/infrastructure.log");
        p.add("prototype-hub.storage.root", () -> "target/infrastructure-storage");
    }

    @Autowired WebTestClient web;
    @Autowired ElasticsearchAsyncClient es;
    @Autowired LogStore logs;
    @Autowired org.hswebframework.web.authorization.token.UserTokenManager tokens;
    @Autowired ReactiveRedisTemplate<Object, Object> operations;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

    @MockitoBean(name = "captchaRandom")
    java.security.SecureRandom random;

    @BeforeEach
    void deterministicCaptcha() {
        web = web.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        when(random.nextInt(anyInt())).thenReturn(0);
    }

    JsonNode result(WebTestClient.ResponseSpec response) {
        return response.expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result");
    }

    Map<String, String> credentials() throws Exception {
        return credentials("admin", PASSWORD);
    }

    Map<String, String> credentials(String username, String password) throws Exception {
        var config = result(web.get().uri("/authorize/login/configs").exchange()).path("encrypt");
        assertTrue(config.path("enabled").asBoolean());
        var key =
                java.security.KeyFactory.getInstance("RSA")
                        .generatePublic(
                                new java.security.spec.X509EncodedKeySpec(
                                        Base64.getDecoder()
                                                .decode(config.path("publicKey").asText())));
        var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        var captcha =
                result(web.get().uri("/authorize/captcha/image?width=130&height=40").exchange());
        assertTrue(captcha.path("base64").asText().startsWith("data:image/png;base64,"));
        return Map.of(
                "username",
                username,
                "password",
                Base64.getEncoder()
                        .encodeToString(
                                cipher.doFinal(
                                        password.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8))),
                "encryptId",
                config.path("id").asText(),
                "verifyKey",
                captcha.path("key").asText(),
                "verifyCode",
                "AAAAA");
    }

    String login() throws Exception {
        return result(web.post().uri("/authorize/login").bodyValue(credentials()).exchange())
                .path("token")
                .asText();
    }

    JsonNode loginWithExpires(String username, String password, long expires) throws Exception {
        Map<String, Object> parameters =
                new java.util.LinkedHashMap<>(credentials(username, password));
        parameters.put("expires", expires);
        return result(web.post().uri("/authorize/login").bodyValue(parameters).exchange());
    }

    long redisTokenTtl(String token) {
        // 原始 PTTL 保留 -1（无过期）与 -2（键不存在），不经 Duration 的哨兵值转换。
        ByteBuffer key = ByteBuffer.wrap(("user-token:" + token).getBytes(StandardCharsets.UTF_8));
        Long ttl =
                operations
                        .execute(connection -> connection.keyCommands().pTtl(key))
                        .single()
                        .block();
        assertNotNull(ttl);
        return ttl;
    }

    @Test
    @Order(1)
    void rememberedRedisLoginKeepsPermanentTtlThroughTouchesAndCacheReloads() throws Exception {
        JsonNode ordinary = loginWithExpires("admin", PASSWORD, 3600000);
        assertEquals(1800000, ordinary.path("expires").asLong());
        long ordinaryTtl = redisTokenTtl(ordinary.path("token").asText());
        assertTrue(ordinaryTtl > 0);
        assertTrue(ordinaryTtl <= Duration.ofMinutes(30).toMillis());

        JsonNode remembered = loginWithExpires("admin", PASSWORD, -1);
        String token = remembered.path("token").asText();
        assertEquals(-1, remembered.path("expires").asLong());
        assertEquals(-1, tokens.getByToken(token).block().getMaxInactiveInterval());
        assertEquals(-1, redisTokenTtl(token));

        var cache =
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                        .maximumSize(10000)
                        .expireAfterAccess(Duration.ofMinutes(1))
                        .<String, org.hswebframework.web.authorization.token.redis.SimpleUserToken>
                                build();
        var second =
                new org.hswebframework.web.authorization.token.redis.RedisUserTokenManager(
                        operations);
        second.setLocalCache(cache.asMap());
        assertEquals(
                tokens.getByToken(token).block().getUserId(),
                second.getByToken(token).block().getUserId());
        assertNotNull(cache.getIfPresent(token));
        cache.invalidateAll();
        cache.cleanUp();
        assertNull(cache.getIfPresent(token));
        assertEquals(-1, second.getByToken(token).block().getMaxInactiveInterval());
        assertNotNull(cache.getIfPresent(token));

        tokens.touch(token).block();
        second.touch(token).block();
        result(web.get().uri("/user/detail").header("X-Access-Token", token).exchange());
        tokens.checkExpiredToken().block();
        assertEquals(-1, redisTokenTtl(token));

        JsonNode shortLived = loginWithExpires("admin", PASSWORD, 100);
        assertEquals(100, shortLived.path("expires").asLong());
        org.awaitility.Awaitility.await()
                .pollDelay(Duration.ofMillis(150))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                web.get()
                                        .uri("/user/detail")
                                        .header("X-Access-Token", shortLived.path("token").asText())
                                        .exchange()
                                        .expectStatus()
                                        .isUnauthorized()
                                        .expectBody()
                                        .consumeWith(response -> {}));
        result(web.get().uri("/user/detail").header("X-Access-Token", token).exchange());
        assertEquals(-1, redisTokenTtl(token));
        assertTrue(
                result(
                                web.post()
                                        .uri("/user-token/reset")
                                        .header("X-Access-Token", token)
                                        .exchange())
                        .asBoolean());
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertFalse(second.tokenIsLoggedIn(token).block()));
        web.get()
                .uri("/user/detail")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(response -> {});
    }

    @Test
    @Order(1)
    void permanentRedisSessionsStillRevokeForPasswordDisableAndAdministratorSignOut()
            throws Exception {
        String admin = login();
        for (String reason : java.util.List.of("password", "disabled", "administrator")) {
            String username = "remember-" + UUID.randomUUID().toString().replace("-", "");
            String id =
                    result(
                                    web.post()
                                            .uri("/user/detail/_create")
                                            .header("X-Access-Token", admin)
                                            .bodyValue(
                                                    Map.of(
                                                            "user",
                                                            Map.of(
                                                                    "username", username,
                                                                    "name", "Remember " + reason,
                                                                    "password", PASSWORD),
                                                            "roleIdList",
                                                            java.util.List.of("MEMBER")))
                                            .exchange())
                            .path("id")
                            .asText();
            String token = loginWithExpires(username, PASSWORD, -1).path("token").asText();
            result(web.get().uri("/user/detail").header("X-Access-Token", token).exchange());
            switch (reason) {
                case "password" ->
                        assertTrue(
                                result(
                                                web.put()
                                                        .uri("/user/passwd")
                                                        .header("X-Access-Token", token)
                                                        .bodyValue(
                                                                Map.of(
                                                                        "oldPassword",
                                                                        PASSWORD,
                                                                        "newPassword",
                                                                        UUID.randomUUID()
                                                                                .toString()))
                                                        .exchange())
                                        .asBoolean());
                case "disabled" ->
                        result(
                                web.put()
                                        .uri("/user/" + id + "/0")
                                        .header("X-Access-Token", admin)
                                        .exchange());
                case "administrator" ->
                        web.delete()
                                .uri("/user-token/token/" + token)
                                .header("X-Access-Token", admin)
                                .exchange()
                                .expectStatus()
                                .is2xxSuccessful()
                                .expectBody()
                                .consumeWith(response -> {});
                default -> fail("Unknown lifecycle scenario");
            }
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertFalse(tokens.tokenIsLoggedIn(token).block()));
            web.get()
                    .uri("/user/detail")
                    .header("X-Access-Token", token)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody()
                    .consumeWith(response -> {});
        }
    }

    @Test
    @Order(1)
    void captchaRsaLoginRedisSharingAndLogoutWork() throws Exception {
        assertTrue(
                tokens
                        instanceof
                        org.hswebframework.web.authorization.token.redis.RedisUserTokenManager);
        var request = credentials();
        String token =
                result(web.post().uri("/authorize/login").bodyValue(request).exchange())
                        .path("token")
                        .asText();
        web.post()
                .uri("/authorize/login")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("LOGIN_ENCRYPTION_INVALID");
        var second =
                new org.hswebframework.web.authorization.token.redis.RedisUserTokenManager(
                        operations);
        assertEquals(
                "21232f297a57a5a743894a0e4a801fc3", second.getByToken(token).block().getUserId());
        assertNotNull(tokens.getByToken(token).block());
        result(web.get().uri("/user/detail").header("X-Access-Token", token).exchange());
        second.signOutByToken(token).block();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertFalse(tokens.tokenIsLoggedIn(token).block()));
        web.get()
                .uri("/authorize/me")
                .header("X-Access-Token", token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(body -> {});
        web.options()
                .uri("/api/prototype-hub/projects")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Access-Token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectBody()
                .consumeWith(body -> {});
        var wrong = new java.util.HashMap<>(credentials());
        wrong.put("verifyCode", "wrong");
        web.post()
                .uri("/authorize/login")
                .bodyValue(wrong)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("CAPTCHA_INVALID");
    }

    @Test
    @Order(2)
    void actualDataStreamIlmLogQueriesAndLevelsWork() throws Exception {
        String token = login(),
                secret = UUID.randomUUID().toString(),
                marker = "record-" + UUID.randomUUID();
        org.slf4j.LoggerFactory.getLogger("com.gaiys.proto.test-log")
                .warn(marker + " password=" + secret);
        result(
                web.post()
                        .uri("/prototype-hub/projects")
                        .header("X-Access-Token", token)
                        .bodyValue(Map.of("name", "ES regression"))
                        .exchange());
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            try {
                                es.indices().refresh(q -> q.index("proto-integration-logs")).join();
                            } catch (Exception ignored) {
                            }
                            var page =
                                    result(
                                            web.post()
                                                    .uri("/logger/system/_query")
                                                    .header("X-Access-Token", token)
                                                    .bodyValue(
                                                            Map.of(
                                                                    "terms",
                                                                    java.util.List.of(
                                                                            Map.of(
                                                                                    "column",
                                                                                    "message",
                                                                                    "termType",
                                                                                    "like",
                                                                                    "value",
                                                                                    marker + "%"))))
                                                    .exchange());
                            assertEquals(1, page.path("total").asInt());
                            assertFalse(page.toString().contains(secret));
                            assertTrue(
                                    page.path("data")
                                            .get(0)
                                            .path("message")
                                            .asText()
                                            .contains("[REDACTED]"));
                        });
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            es.indices().refresh(q -> q.index("proto-integration-logs")).join();
                            var audit =
                                    logs.query(
                                                    "audit",
                                                    new com.gaiys.proto.logging.query.LogQuery())
                                            .block(Duration.ofSeconds(5));
                            assertNotNull(audit);
                            assertTrue(
                                    audit.data().stream()
                                            .anyMatch(
                                                    d ->
                                                            "project.create".equals(d.getAction())
                                                                    && "21232f297a57a5a743894a0e4a801fc3"
                                                                            .equals(
                                                                                    d
                                                                                            .getActorId())));
                        });
        assertEquals(
                "proto-integration-logs",
                es.indices()
                        .getDataStream(q -> q.name("proto-integration-logs"))
                        .join()
                        .dataStreams()
                        .getFirst()
                        .name());
        assertFalse(
                es.ilm()
                        .getLifecycle(q -> q.name("proto-integration-retention"))
                        .join()
                        .result()
                        .isEmpty());
        var access =
                result(
                        web.post()
                                .uri("/logger/access/_query")
                                .header("X-Access-Token", token)
                                .bodyValue(
                                        Map.of(
                                                "terms",
                                                java.util.List.of(
                                                        Map.of(
                                                                "column",
                                                                "url",
                                                                "value",
                                                                "/prototype-hub/projects"))))
                                .exchange());
        assertTrue(access.path("total").asInt() > 0);
        assertFalse(access.toString().contains(token));
        result(
                web.put()
                        .uri("/logger/level/com.gaiys.proto.test-level")
                        .header("X-Access-Token", token)
                        .bodyValue(Map.of("level", "DEBUG"))
                        .exchange());
        assertEquals(
                "DEBUG",
                result(
                                web.get()
                                        .uri("/logger/level/com.gaiys.proto.test-level")
                                        .header("X-Access-Token", token)
                                        .exchange())
                        .path("configuredLevel")
                        .asText());
        web.post()
                .uri("/logger/system/_query")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .consumeWith(body -> {});
        web.post()
                .uri("/logger/access/_query")
                .header("X-Access-Token", token)
                .bodyValue(Map.of("pageSize", 10000))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .consumeWith(body -> {});
    }

    @Test
    @Order(3)
    void historicalEsFieldsSupportAliasesNullConditionsAndAliasSorts() throws Exception {
        String token = login(), marker = "historical-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        String[] ids = {
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()
        };
        var bulk = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
        var historicalSources = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.length; i++) {
            Map<String, Object> doc = new java.util.LinkedHashMap<>();
            doc.put("id", ids[i]);
            doc.put("@timestamp", java.time.Instant.ofEpochMilli(now).toString());
            doc.put("createTime", now);
            doc.put("kind", "system");
            doc.put("message", marker);
            doc.put("server", marker);
            if (i < 2) {
                doc.put("actorId", i == 0 ? "actor-a" : "actor-b");
                doc.put("name", i == 0 ? "ClassA" : "ClassB");
            }
            // Historical source intentionally has no UI aliases or nested context object.
            historicalSources.add(doc);
            final String id = ids[i];
            bulk.operations(
                    op -> op.create(c -> c.index("proto-integration-logs").id(id).document(doc)));
        }
        assertFalse(es.bulk(bulk.build()).join().errors());
        es.indices().refresh(q -> q.index("proto-integration-logs")).join();
        var terms =
                java.util.List.<Map<String, Object>>of(
                        Map.of("column", "message", "value", marker),
                        Map.of(
                                "terms",
                                java.util.List.of(
                                        Map.of("column", "userId", "value", "actor-a"),
                                        Map.of("column", "className", "value", "ClassA"),
                                        Map.of("column", "context.server", "value", marker))));
        var page = querySystem(token, terms, java.util.List.of());
        assertEquals(1, page.path("total").asInt());
        JsonNode first = page.path("data").get(0);
        assertEquals("actor-a", first.path("userId").asText());
        assertEquals("ClassA", first.path("className").asText());
        assertEquals(marker, first.path("context").path("server").asText());
        for (String operator :
                java.util.List.of("isnull", "notnull", "not", "nlike", "nin", "nbtw")) {
            Map<String, Object> term = new java.util.LinkedHashMap<>();
            term.put("column", "className");
            term.put("termType", operator);
            if (operator.equals("not") || operator.equals("nlike")) term.put("value", "ClassA");
            if (operator.equals("nin")) term.put("value", java.util.List.of("ClassA"));
            if (operator.equals("nbtw")) term.put("value", java.util.List.of("ClassA", "ClassA"));
            page =
                    querySystem(
                            token,
                            java.util.List.of(Map.of("column", "message", "value", marker), term),
                            java.util.List.of());
            assertEquals(
                    operator.equals("isnull") || operator.equals("nbtw") ? 1 : 2,
                    page.path("total").asInt(),
                    operator);
        }
        page =
                querySystem(
                        token,
                        java.util.List.of(Map.of("column", "message", "value", marker)),
                        java.util.List.of(Map.of("name", "className", "order", "asc")));
        assertEquals(
                java.util.List.of(ids[2], ids[0], ids[1]),
                java.util.stream.StreamSupport.stream(page.path("data").spliterator(), false)
                        .map(row -> row.path("id").asText())
                        .toList());
        page =
                querySystem(
                        token,
                        java.util.List.of(Map.of("column", "message", "value", marker)),
                        java.util.List.of(
                                Map.of("name", "userId", "order", "desc"),
                                Map.of("name", "context.server", "order", "asc")));
        assertEquals(
                java.util.List.of(ids[1], ids[0], ids[2]),
                java.util.stream.StreamSupport.stream(page.path("data").spliterator(), false)
                        .map(row -> row.path("id").asText())
                        .toList());

        // An index written before metadata capture has no mapping for username/ip/target.
        String historicalIndex = "proto-historical-only-" + UUID.randomUUID();
        es.indices()
                .create(
                        c ->
                                c.index(historicalIndex)
                                        .withJson(
                                                new java.io.StringReader(
                                                        "{\"mappings\":{\"properties\":{\"kind\":{\"type\":\"keyword\"},\"name\":{\"type\":\"keyword\"},\"actorId\":{\"type\":\"keyword\"},\"server\":{\"type\":\"keyword\"}}}}")))
                .join();
        var historicalBulk = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
        historicalSources.forEach(
                doc ->
                        historicalBulk.operations(
                                op -> op.create(c -> c.index(historicalIndex).document(doc))));
        assertFalse(es.bulk(historicalBulk.build()).join().errors());
        es.indices().refresh(q -> q.index(historicalIndex)).join();
        var config = new com.gaiys.proto.logging.properties.LogStorageProperties();
        config.getElasticsearch().setDataStream(historicalIndex);
        config.getElasticsearch().setManageTemplate(false);
        var beans =
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        Map.of("es", es));
        var historicalStore =
                new LogStore(
                        config, beans.getBeanProvider(ElasticsearchAsyncClient.class), metrics);
        try {
            var query = new com.gaiys.proto.logging.query.LogQuery();
            var term = new com.gaiys.proto.logging.query.LogQuery.Term();
            term.setColumn("ip");
            term.setTermType("isnull");
            query.setTerms(java.util.List.of(term));
            query.setSorts(
                    java.util.List.of(
                            new com.gaiys.proto.logging.query.LogQuery.Sort(
                                    "context.username", "asc")));
            assertEquals(
                    3,
                    historicalStore.query("system", query).block(Duration.ofSeconds(10)).total());
        } finally {
            historicalStore.close();
        }
    }

    JsonNode querySystem(
            String token,
            java.util.List<Map<String, Object>> terms,
            java.util.List<Map<String, Object>> sorts) {
        return result(
                web.post()
                        .uri("/logger/system/_query")
                        .header("X-Access-Token", token)
                        .bodyValue(Map.of("terms", terms, "sorts", sorts))
                        .exchange());
    }

    @Test
    @Order(4)
    void accessDetailsCaptureRealPeerAndHandlerWithBoundedSafeQueryMetadata() throws Exception {
        String token = login(),
                secret = UUID.randomUUID().toString(),
                trace = "metadata-" + UUID.randomUUID();
        String next =
                java.net.URLEncoder.encode(
                        "https://example.test/callback?credential=" + secret,
                        java.nio.charset.StandardCharsets.UTF_8);
        result(
                web.post()
                        .uri(
                                "/prototype-hub/projects?password="
                                        + secret
                                        + "&client-token="
                                        + secret
                                        + "&next="
                                        + next)
                        .header("X-Access-Token", token)
                        .header("X-Request-ID", trace)
                        .header("X-Forwarded-For", "203.0.113.9")
                        .header("Cookie", "session=" + secret)
                        .header("X-Client-Secret", secret)
                        .header("User-Agent", "test token=" + secret)
                        .bodyValue(Map.of("name", "Request metadata fixture"))
                        .exchange());
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            es.indices().refresh(q -> q.index("proto-integration-logs")).join();
                            JsonNode page =
                                    result(
                                            web.post()
                                                    .uri("/logger/access/_query")
                                                    .header("X-Access-Token", token)
                                                    .bodyValue(
                                                            Map.of(
                                                                    "terms",
                                                                    java.util.List.of(
                                                                            Map.of(
                                                                                    "column",
                                                                                    "traceId",
                                                                                    "value",
                                                                                    trace))))
                                                    .exchange());
                            assertEquals(1, page.path("total").asInt());
                            JsonNode doc = page.path("data").get(0);
                            assertEquals("/prototype-hub/projects", doc.path("url").asText());
                            assertEquals(
                                    "POST /prototype-hub/projects", doc.path("action").asText());
                            assertFalse(doc.path("ip").asText().isBlank());
                            assertNotEquals("203.0.113.9", doc.path("ip").asText());
                            assertTrue(doc.path("target").asText().startsWith("com.gaiys.proto."));
                            assertFalse(doc.path("method").asText().isBlank());
                            assertEquals("admin", doc.path("context").path("username").asText());
                            assertFalse(doc.path("context").path("userName").asText().isBlank());
                            assertEquals(doc.path("actorId"), doc.path("userId"));
                            assertFalse(doc.path("httpHeaders").has("authorization"));
                            assertFalse(doc.path("httpHeaders").has("cookie"));
                            assertFalse(doc.path("httpHeaders").has("x-client-secret"));
                            assertEquals(
                                    "[REDACTED]",
                                    doc.path("parameters").path("password").get(0).asText());
                            assertEquals(
                                    "[REDACTED]",
                                    doc.path("parameters").path("client-token").get(0).asText());
                            assertFalse(doc.path("parameters").has("name"));
                            assertFalse(doc.toString().contains(secret));
                        });
    }

    @Test
    @Order(99)
    void esOutageDoesNotBlockBusinessAndQueryReportsUnavailable() throws Exception {
        String token = login();
        ES.stop();
        long start = System.nanoTime();
        result(
                web.post()
                        .uri("/prototype-hub/projects")
                        .header("X-Access-Token", token)
                        .bodyValue(Map.of("name", "ES unavailable"))
                        .exchange());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
        web.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
        assertFalse(logs.ping().block(Duration.ofSeconds(5)));
        web.post()
                .uri("/logger/system/_query")
                .header("X-Access-Token", token)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .consumeWith(body -> {});
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertTrue(metrics.counter("proto.logging.es.failures").count() > 0));
    }
}
