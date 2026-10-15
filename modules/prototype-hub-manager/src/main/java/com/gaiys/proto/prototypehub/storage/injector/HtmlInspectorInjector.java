package com.gaiys.proto.prototypehub.storage.injector;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class HtmlInspectorInjector {
    private final PrototypeHubStorageProperties properties;

    public void inject(Path file) throws IOException {
        if (Files.size(file) > properties.getMaxHtmlSizeBytes())
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.html_too_large", "PROTOTYPE_HUB_HTML_TOO_LARGE");
        String html = Files.readString(file, StandardCharsets.UTF_8);
        // 已上传或重新导出的旧页面继续使用只读兼容入口，避免给同一页面重复注入脚本。
        if (html.contains("data-prototype-hub-inspector")
                || html.contains("data-protohub-inspector")) return;
        String path = properties.getInspectorPath();
        if (!path.matches("/[A-Za-z0-9/_.,-]+"))
            throw new IllegalStateException("Invalid inspector URL");
        String tag = "<script data-prototype-hub-inspector src=\"" + path + "\"></script>";
        Matcher body = Pattern.compile("(?i)</body\\s*>").matcher(html);
        Files.writeString(
                file,
                body.find()
                        ? body.replaceFirst(Matcher.quoteReplacement(tag + body.group()))
                        : html + tag,
                StandardCharsets.UTF_8);
    }

    public static boolean isHtml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm");
    }
}
