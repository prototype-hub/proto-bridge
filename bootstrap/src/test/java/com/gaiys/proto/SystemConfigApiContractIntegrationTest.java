package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.system.service.SystemConfigStoreService;
import com.gaiys.proto.system.service.ValidatedDictionaryItemService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hswebframework.web.dictionary.service.DefaultDictionaryItemService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 配置入口与真实字典调用的契约回归；只使用隔离数据库、临时文件目录和真实HTTP。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class SystemConfigApiContractIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String PASSWORD = UUID.randomUUID().toString();
    static final Path FILES = temporaryStorage();
    static final String COLORS = "/system/config/calendar-tag-color";

    static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("system-config-api-contract-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        registry.add("spring.r2dbc.username", DATABASE::getUsername);
        registry.add("spring.r2dbc.password", DATABASE::getPassword);
        registry.add("proto.admin.password", () -> PASSWORD);
        registry.add("prototype-hub.storage.root", () -> FILES.toString());
    }

    @Autowired WebTestClient client;
    @Autowired DefaultDictionaryItemService dictionaryItems;
    @Autowired SystemConfigStoreService configStore;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        admin =
                call(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", "admin", "password", PASSWORD),
                                200)
                        .path("result")
                        .path("token")
                        .asText();
        assertFalse(admin.isBlank());
    }

    @AfterAll
    static void clearStorage() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        JsonNode result =
                request.exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(result);
        return result;
    }

    JsonNode config(String scope) {
        return call(HttpMethod.GET, "/system/config/" + scope, admin, null, 200).path("result");
    }

    void badJson(String path, String json) {
        JsonNode result = call(HttpMethod.POST, path, admin, json, 400);
        // JSON null bodies may be rejected by WebFlux before reaching the domain guard.
        assertEquals(400, result.path("status").asInt());
        assertFalse(result.path("success").asBoolean());
    }

    @Test
    void rejectsNullJsonAndEntireInvalidBatchesWithoutPartialUpdates() {
        call(HttpMethod.POST, "/system/config/front", admin, Map.of("title", "Original"), 200);
        call(HttpMethod.POST, "/system/config/amap", admin, Map.of("key", "Original"), 200);
        JsonNode front = config("front");
        JsonNode amap = config("amap");
        for (String json :
                List.of(
                        "null",
                        "[null]",
                        "[{\"scope\":null,\"properties\":{}}]",
                        "[{\"scope\":\"front\",\"properties\":null}]",
                        "[{\"scope\":\"front\",\"properties\":{\"title\":\"Changed\"}},null]",
                        "[{\"scope\":\"amap\",\"properties\":{\"key\":\"Changed\"}},"
                                + "{\"scope\":\"paths\",\"properties\":{\"base-path\":\"http://192.168.0.280:5173/api\"}}]")) {
            badJson("/system/config/scope/_save", json);
            assertEquals(front, config("front"));
            assertEquals(amap, config("amap"));
        }
        for (String json : List.of("null", "[null]", "[\"front\",null]"))
            badJson("/system/config/scopes", json);
        call(HttpMethod.POST, "/system/config/scopes", admin, List.of("front", "unknown"), 404);
        call(HttpMethod.GET, "/system/config/unknown", admin, null, 404);
        badJson("/system/config/front", "null");
        call(HttpMethod.POST, "/system/config/scope/_save", admin, List.of(), 200);
        assertEquals(front, config("front"));
    }

    @Test
    void calendarTagColorsRoundTripAsTheExistingTagIdToStringMap() {
        String custom = UUID.randomUUID().toString();
        Map<String, Object> colors =
                Map.of(
                        "holiday",
                        "#a1b4cc",
                        "weekend",
                        "#95de64",
                        "workday",
                        "#69b1ff",
                        custom,
                        "#1677ff",
                        "legacy-tag",
                        "blue");
        call(HttpMethod.POST, COLORS, admin, colors, 200);
        JsonNode result = config("calendar-tag-color");
        assertEquals(colors.size(), result.size());
        colors.forEach((key, value) -> assertEquals(value, result.path(key).asText()));
        assertEquals(
                result, call(HttpMethod.GET, "/api" + COLORS, admin, null, 200).path("result"));
        JsonNode batch =
                call(
                                HttpMethod.POST,
                                "/system/config/scopes",
                                admin,
                                List.of("calendar-tag-color", "front", "paths"),
                                200)
                        .path("result");
        assertTrue(batch.isArray());
        assertEquals(3, batch.size());
        assertEquals("calendar-tag-color", batch.get(0).path("scope").asText());
        assertEquals(result, batch.get(0).path("properties"));
        call(
                HttpMethod.POST,
                "/system/config/scope/_save",
                admin,
                List.of(Map.of("scope", "calendar-tag-color", "properties", Map.of(custom, "red"))),
                200);
        assertEquals(1, config("calendar-tag-color").size());
        assertEquals("red", config("calendar-tag-color").path(custom).asText());
        call(HttpMethod.POST, COLORS, admin, Map.of(), 200);
        assertEquals(0, config("calendar-tag-color").size());
        call(HttpMethod.GET, COLORS, null, null, 401);
        call(HttpMethod.POST, COLORS, null, Map.of(), 401);
    }

    @Test
    void calendarColorsRetainExistingBudgetsAndRejectNonStringValues() {
        call(HttpMethod.POST, COLORS, admin, Map.of("holiday", "#1677ff"), 200);
        JsonNode before = config("calendar-tag-color");
        for (String json :
                List.of(
                        "{\"holiday\":null}",
                        "{\"holiday\":123}",
                        "{\"holiday\":true}",
                        "{\"holiday\":{\"color\":\"#1677ff\"}}",
                        "{\"holiday\":[\"#1677ff\"]}")) {
            badJson(COLORS, json);
            assertEquals(before, config("calendar-tag-color"));
        }
        Map<String, Object> maximum = new LinkedHashMap<>();
        for (int index = 0; index < 30; index++) maximum.put("tag-" + index, "#1677ff");
        call(HttpMethod.POST, COLORS, admin, maximum, 200);
        JsonNode maximumBefore = config("calendar-tag-color");
        maximum.put("extra", "#1677ff");
        call(HttpMethod.POST, COLORS, admin, maximum, 400);
        assertEquals(maximumBefore, config("calendar-tag-color"));
        call(HttpMethod.POST, COLORS, admin, Map.of("holiday", "a".repeat(16385)), 400);
        assertEquals(maximumBefore, config("calendar-tag-color"));
        call(
                HttpMethod.POST,
                "/system/config/scopes",
                admin,
                List.of("front", "amap", "paths", "calendar-tag-color"),
                400);
        call(
                HttpMethod.POST,
                "/system/config/scope/_save",
                admin,
                List.of("front", "amap", "paths", "calendar-tag-color").stream()
                        .map(scope -> Map.of("scope", scope, "properties", Map.of()))
                        .toList(),
                400);
        assertEquals(maximumBefore, config("calendar-tag-color"));
    }

    @Test
    void existingThreeScopeDtoPublicWhitelistAndBasePathRulesRemainCompatible() {
        Map<String, Object> front =
                Map.of(
                        "title", "Configured",
                        "logo", "",
                        "ico", "",
                        "background", "",
                        "headerTheme", "dark",
                        "showRecordNumber", true,
                        "recordNumber", "",
                        "policeRecordNumber", "",
                        "copyright", "");
        call(
                HttpMethod.POST,
                "/system/config/scope/_save",
                admin,
                List.of(
                        Map.of("scope", "front", "properties", front),
                        Map.of("scope", "amap", "properties", Map.of()),
                        Map.of("scope", "paths", "properties", Map.of("base-path", "/api"))),
                200);
        JsonNode publicFront =
                call(HttpMethod.GET, "/system/config/front", null, null, 200).path("result");
        Set<String> names = new HashSet<>();
        publicFront.fieldNames().forEachRemaining(names::add);
        assertEquals(front.keySet(), names);
        front.forEach(
                (key, value) -> {
                    if (value instanceof Boolean flag)
                        assertEquals(flag, publicFront.path(key).asBoolean());
                    else assertEquals(value, publicFront.path(key).asText());
                });
        assertEquals(0, config("amap").size());
        assertEquals("/api", config("paths").path("base-path").asText());
        for (String invalid :
                List.of(
                        "http://192.168.0.280:5173/api",
                        "//example.com/api",
                        "/api?query=1",
                        "/api#fragment",
                        "https://user@example.com/api",
                        "",
                        " ")) {
            call(HttpMethod.POST, "/system/config/paths", admin, Map.of("base-path", invalid), 400);
            assertEquals("/api", config("paths").path("base-path").asText());
        }
        for (String valid : List.of("https://example.com/api/", "/api")) {
            call(HttpMethod.POST, "/system/config/paths", admin, Map.of("base-path", valid), 200);
            assertEquals(valid, config("paths").path("base-path").asText());
        }
        call(HttpMethod.POST, "/system/config/front", admin, Map.of("password", "private"), 400);
        call(HttpMethod.POST, "/system/config/front", admin, Map.of("headerTheme", "unknown"), 400);
        call(
                HttpMethod.POST,
                "/system/config/front",
                admin,
                Map.of("showRecordNumber", "true"),
                400);
        assertEquals(
                publicFront,
                call(HttpMethod.GET, "/api/system/config/front", null, null, 200).path("result"));
    }

    @Test
    void officialDictionaryItemCreationRequiresItsPersistedDictionaryId() {
        // 原官方fillOrdinal对null dictId产生500；扩展服务在序号计算前返回参数400。
        Map<HttpMethod, Integer> statuses = new LinkedHashMap<>();
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PATCH)) {
            var result =
                    client.method(method)
                            .uri("/dictionary-item")
                            .header("X-Access-Token", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(
                                    Map.of(
                                            "id",
                                            UUID.randomUUID().toString(),
                                            "name",
                                            "Missing dictionary"))
                            .exchange()
                            .expectBody(JsonNode.class)
                            .returnResult();
            statuses.put(method, result.getStatus().value());
            JsonNode response = result.getResponseBody();
            System.out.printf(
                    "Dictionary-item missing dictId probe: %s status=%d code=%s%n",
                    method,
                    result.getStatus().value(),
                    response == null ? "no JSON body" : response.path("code").asText());
        }
        assertAll(
                () -> assertEquals(400, statuses.get(HttpMethod.POST)),
                () -> assertEquals(400, statuses.get(HttpMethod.PATCH)));
    }

    @Test
    void literalNullUsesSpringRequiredBodyRulesAcrossTypedAndNativeJsonEntrypoints() {
        for (Map.Entry<HttpMethod, String> entry :
                List.of(
                        Map.entry(HttpMethod.POST, "/user/detail/_create"),
                        Map.entry(HttpMethod.POST, "/role"),
                        Map.entry(HttpMethod.PATCH, "/role"),
                        Map.entry(HttpMethod.PATCH, "/dictionary-item"),
                        Map.entry(HttpMethod.POST, "/dictionary-item/_batch"))) {
            call(entry.getKey(), entry.getValue(), admin, "null", 400);
        }
        JsonNode withoutBody = call(HttpMethod.POST, "/menu/_all/tree", admin, null, 200);
        JsonNode withNull = call(HttpMethod.POST, "/menu/_all/tree", admin, "null", 200);
        assertEquals(withoutBody.path("result"), withNull.path("result"));
        call(HttpMethod.POST, "/role/_query", admin, Map.of(), 200);
        call(HttpMethod.POST, "/system/config/front", admin, Map.of(), 200);
        // /scopes和/front都合并默认值；原始Map只从隔离测试库的存储服务读取。
        var persisted = configStore.findById("front").block(Duration.ofSeconds(10));
        assertNotNull(persisted);
        assertEquals(0, persisted.getProperties().size());
        // 公开视图合并9项默认展示字段；空Map并不删除默认配置。
        assertEquals(9, config("front").size());
        call(HttpMethod.POST, "/system/config/front", admin, null, 400);
    }

    String dictionary() {
        String id = UUID.randomUUID().toString();
        call(
                HttpMethod.PATCH,
                "/dictionary",
                admin,
                Map.of("id", id, "name", "Contract dictionary"),
                200);
        return id;
    }

    JsonNode dictionaryItem(String id) {
        return call(HttpMethod.GET, "/dictionary-item/" + id, admin, null, 200).path("result");
    }

    @Test
    void legitimateOfficialDictionaryWritesRetainGeneratedIdsOrdinalsAndOptionalItemFields() {
        assertInstanceOf(ValidatedDictionaryItemService.class, dictionaryItems);
        String dictId = dictionary();
        JsonNode created =
                call(HttpMethod.POST, "/dictionary-item", admin, Map.of("dictId", dictId), 200)
                        .path("result");
        String id = created.path("id").asText();
        assertFalse(id.isBlank());
        JsonNode stored = dictionaryItem(id);
        assertEquals(dictId, stored.path("dictId").asText());
        assertTrue(stored.path("ordinal").isNumber());
        assertTrue(stored.path("name").isMissingNode() || stored.path("name").isNull());
        assertTrue(stored.path("value").isMissingNode() || stored.path("value").isNull());
        assertTrue(stored.path("text").isMissingNode() || stored.path("text").isNull());
        call(
                HttpMethod.PATCH,
                "/dictionary-item",
                admin,
                Map.of("id", id, "dictId", dictId, "text", "Saved"),
                200);
        assertEquals("Saved", dictionaryItem(id).path("text").asText());
        call(HttpMethod.PUT, "/dictionary-item/" + id, admin, Map.of("text", "Updated"), 200);
        stored = dictionaryItem(id);
        assertEquals("Updated", stored.path("text").asText());
        assertEquals(dictId, stored.path("dictId").asText());

        String second = UUID.randomUUID().toString();
        String third = UUID.randomUUID().toString();
        call(
                HttpMethod.POST,
                "/dictionary-item/_batch",
                admin,
                List.of(
                        Map.of("id", second, "dictId", dictId),
                        Map.of("id", third, "dictId", dictId)),
                200);
        assertTrue(dictionaryItem(second).path("ordinal").isNumber());
        assertTrue(dictionaryItem(third).path("ordinal").isNumber());
        assertTrue(
                dictionaryItem(third).path("ordinal").asInt()
                        > dictionaryItem(second).path("ordinal").asInt());
    }

    @Test
    void invalidDictionaryBatchesDoNotPersistEarlierValidItems() {
        String dictId = dictionary();
        for (Map.Entry<HttpMethod, String> entry :
                List.of(
                        Map.entry(HttpMethod.PATCH, "/dictionary-item"),
                        Map.entry(HttpMethod.POST, "/dictionary-item/_batch"))) {
            for (Map<String, Object> invalid :
                    List.of(
                            Map.<String, Object>of("id", UUID.randomUUID().toString()),
                            Map.<String, Object>of(
                                    "id",
                                    UUID.randomUUID().toString(),
                                    "dictId",
                                    "a".repeat(65)))) {
                String first = UUID.randomUUID().toString();
                call(
                        entry.getKey(),
                        entry.getValue(),
                        admin,
                        List.of(Map.of("id", first, "dictId", dictId), invalid),
                        400);
                JsonNode count =
                        call(
                                HttpMethod.POST,
                                "/dictionary-item/_count",
                                admin,
                                Map.of(
                                        "terms",
                                        List.of(
                                                Map.of(
                                                        "column",
                                                        "id",
                                                        "termType",
                                                        "eq",
                                                        "value",
                                                        first))),
                                200);
                assertEquals(0, count.path("result").asInt());
            }
        }
    }
}
