package com.gaiys.proto.prototypehub.web.urls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.web.response.VersionResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.pattern.PathPatternParser;

class PrototypeUrlsTest {
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "index.html|index.html",
                "pages/index%.html|pages/index%25.html",
                "pages/index%20page.html|pages/index%2520page.html",
                "目录/a b?#.html|%E7%9B%AE%E5%BD%95/a%20b%3F%23.html",
                "index;v.html|index%3Bv.html",
                "dir;v/index.html|dir%3Bv/index.html"
            })
    void versionResponseEncodesThePreviewOnceAndPreservesTheStoredEntry(
            String entry, String encodedEntry) {
        var version = new PrototypeVersionEntity();
        version.setId("version-id");
        version.setProjectId("project-id");
        version.setPreviewKey("preview-key");
        version.setEntryFile(entry);
        var response = VersionResponse.from(version);
        String base = "/api/prototype-hub/runtime/version-id/preview-key";
        assertEquals(entry, response.entryFile());
        assertEquals(base, response.runtimeBaseUrl());
        assertEquals(base + "/" + encodedEntry, response.previewUrl());
        assertEquals(
                entry,
                UriUtils.decode(
                        response.previewUrl().substring(base.length() + 1),
                        StandardCharsets.UTF_8));
        var pattern =
                PathPatternParser.defaultInstance.parse(
                        "/prototype-hub/runtime/{versionId}/{previewKey}/{*resourcePath}");
        // 对照真实控制器路径规则，模拟已有/api过滤器后的路由，而不只验证字符串编码。
        var match =
                pattern.matchAndExtract(
                        PathContainer.parsePath(response.previewUrl().substring(4)));
        assertNotNull(match);
        assertEquals("/" + entry, match.getUriVariables().get("resourcePath"));
    }
}
