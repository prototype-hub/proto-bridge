package com.gaiys.proto.prototypehub.storage.scanner;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.injector.HtmlInspectorInjector;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.parser.StaticCssReferences;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Code;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Finding;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Limits;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Severity;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Summary;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.UncheckedReferences;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 在 FileWork 内只读检查已授权的固定修订；清单决定可见文件集合，路径解析保持在存储根内。
 *
 * <p>只检查 HTML/CSS 字面引用，不发网络请求、不执行脚本、不解析片段目标。所有预算是单次请求局部状态，
 * 无常驻报告、权限缓存、后台任务或递归导入；部分检查显式标记，不能被解释为整包通过。
 */
@Component
@RequiredArgsConstructor
public class PrototypePackageQualityScanner {
    public static final Limits LIMITS =
            new Limits(2 * 1024 * 1024, 32L * 1024 * 1024, 100_000, 1000);
    private static final String ORIGIN = "prototype-hub.invalid";
    private static final String ROOT_ORIGIN = "root-absolute.invalid";
    private static final String EXTERNAL_ORIGIN = "external.invalid";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern CSS_CHARSET =
            Pattern.compile("^@charset\\s+\"([^\"]{1,80})\"\\s*;");
    private static final Comparator<Finding> FINDING_ORDER =
            Comparator.comparing(Finding::sourcePath)
                    .thenComparing(finding -> finding.code().name())
                    .thenComparing(Finding::targetPath, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(Finding::reference, Comparator.nullsFirst(String::compareTo));
    private final PrototypeHubStorageProperties properties;

    /** 调用方已校验活动版本和精确修订归属；数据库读取必须在进入本方法前完成。 */
    public RevisionQualityResponse scan(PrototypeRevisionEntity revision) {
        PrototypeManifest manifest = manifest(revision.getManifestJson());
        Path site =
                SafePaths.storedVersion(
                                properties.getRoot(),
                                revision.getProjectId(),
                                revision.getStoragePath())
                        .resolve("site");
        return new Scan(revision, manifest, site).run();
    }

    private PrototypeManifest manifest(String json) {
        PrototypeManifest manifest = PrototypeManifestBuilder.parse(json);
        if (manifest == null
                || manifest.formatVersion() != 1
                || manifest.files().size() > properties.getMaxZipFiles()
                || !canonical(manifest.entryFile())) throw invalidManifest();
        Set<String> paths = new HashSet<>();
        long bytes = 0;
        for (var file : manifest.files()) {
            if (file == null
                    || !canonical(file.path())
                    || file.size() < 0
                    || file.sha256() == null
                    || !SHA256.matcher(file.sha256()).matches()
                    || !paths.add(file.path())) throw invalidManifest();
            if (file.size() > properties.getMaxExtractedSizeBytes() - bytes)
                throw invalidManifest();
            bytes += file.size();
        }
        return manifest;
    }

    private static boolean canonical(String path) {
        if (path == null
                || path.isBlank()
                || path.length() > 1024
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.chars().anyMatch(value -> value < 32)) return false;
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static PrototypeHubException invalidManifest() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.manifest_invalid", "PROTOTYPE_HUB_MANIFEST_INVALID");
    }

    private static URI sourceUri(String path) {
        try {
            return new URI("https", null, ORIGIN, -1, "/" + path, null, null);
        } catch (URISyntaxException invalid) {
            throw invalidManifest();
        }
    }

    private static boolean css(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".css");
    }

    private static String bounded(String value) {
        return value == null || value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private final class Scan {
        private final PrototypeRevisionEntity revision;
        private final PrototypeManifest manifest;
        private final Path site;
        private final Map<String, Path> existing = new LinkedHashMap<>();
        private final List<Finding> findings = new ArrayList<>();
        private final Set<FindingKey> seen = new HashSet<>();
        private int documents;
        private long bytes;
        private int references;
        private int errors;
        private int warnings;
        private int omitted;
        private int skipped;
        private int external;
        private int rootAbsolute;
        private int fragment;
        private int unsupportedScheme;
        private boolean complete = true;
        private boolean referenceLimit;

        private Scan(PrototypeRevisionEntity revision, PrototypeManifest manifest, Path site) {
            this.revision = revision;
            this.manifest = manifest;
            this.site = site;
        }

        private RevisionQualityResponse run() {
            List<PrototypeManifest.FileEntry> files =
                    manifest.files().stream()
                            .sorted(Comparator.comparing(PrototypeManifest.FileEntry::path))
                            .toList();
            for (var file : files) {
                Path path = SafePaths.within(site, file.path());
                if (file.path()
                        .codePoints()
                        .anyMatch(
                                c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
                    add(Code.chinese_path, Severity.warning, file.path(), null, null);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    existing.put(file.path(), path);
                else {
                    complete = false;
                    add(Code.file_missing, Severity.error, file.path(), file.path(), null);
                    if (document(file.path())) skipped++;
                }
            }
            if (!existing.containsKey(manifest.entryFile())) {
                complete = false;
                add(
                        Code.entry_missing,
                        Severity.error,
                        manifest.entryFile(),
                        manifest.entryFile(),
                        null);
            }
            for (var file : existing.entrySet()) {
                if (!document(file.getKey())) continue;
                if (referenceLimit) {
                    skipped++;
                    continue;
                }
                inspect(file.getKey(), file.getValue());
            }
            findings.sort(FINDING_ORDER);
            return new RevisionQualityResponse(
                    revision.getVersionId(),
                    revision.getId(),
                    revision.getManifestSha256(),
                    manifest.entryFile(),
                    new Summary(
                            files.size(),
                            documents,
                            bytes,
                            references,
                            errors,
                            warnings,
                            seen.size(),
                            omitted,
                            skipped,
                            new UncheckedReferences(
                                    external, rootAbsolute, fragment, unsupportedScheme)),
                    LIMITS,
                    complete,
                    List.copyOf(findings));
        }

        private boolean document(String path) {
            return HtmlInspectorInjector.isHtml(Path.of(path)) || css(path);
        }

        private void inspect(String source, Path path) {
            try {
                long remaining = LIMITS.maxTotalTextBytes() - bytes;
                long size = Files.size(path);
                if (size > LIMITS.maxTextBytes() || size > remaining) {
                    skip(Code.document_limit, source);
                    return;
                }
                byte[] content;
                // 不仅依赖先验 size：变化中的文件也只能读取预算加一个哨兵字节。
                int allowed = (int) Math.min(LIMITS.maxTextBytes(), remaining);
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    content = input.readNBytes(allowed + 1);
                }
                if (content.length > allowed) {
                    skip(Code.document_limit, source);
                    return;
                }
                bytes += content.length;
                if (css(source)) {
                    StaticCssReferences.scan(
                            decodeCss(content),
                            ref -> reference(source, sourceUri(source), ref, false));
                } else html(source, content);
                documents++;
                if (referenceLimit) skipped++;
            } catch (IOException | IllegalArgumentException error) {
                // 报告只返回包内来源，不泄漏根目录、原始 I/O 异常或无界内容。
                skip(Code.text_unreadable, source);
            }
        }

        private void html(String source, byte[] content) throws IOException {
            // jsoup 只解析内存字节；null charset 使用 BOM/meta 声明，默认 UTF-8，不加载任何 URL。
            Document document =
                    Jsoup.parse(
                            new ByteArrayInputStream(content),
                            null,
                            sourceUri(source).toASCIIString());
            URI base = sourceUri(source);
            Element baseElement = document.selectFirst("base[href]");
            if (baseElement != null) {
                try {
                    String href = baseElement.attr("href").strip();
                    URI declared = uri(href);
                    if (declared.isAbsolute() || href.startsWith("//"))
                        base = URI.create("https://" + EXTERNAL_ORIGIN + "/");
                    else if (href.startsWith("/"))
                        base = URI.create("https://" + ROOT_ORIGIN + "/");
                    else base = base.resolve(declared);
                } catch (IllegalArgumentException | URISyntaxException invalid) {
                    add(
                            Code.invalid_reference,
                            Severity.error,
                            source,
                            null,
                            baseElement.attr("href"));
                }
            }
            URI resolvedBase = base;
            for (Element element : document.getAllElements()) {
                if (referenceLimit) break;
                String tag = element.normalName();
                if (tag.equals("script")
                        && (element.hasAttr("data-prototype-hub-inspector")
                                || element.hasAttr("data-protohub-inspector"))) continue;
                if ((tag.equals("a") || tag.equals("area")) && element.hasAttr("href"))
                    reference(source, base, element.attr("href"), true);
                if (tag.equals("link") && element.hasAttr("href"))
                    reference(source, base, element.attr("href"), false);
                if (Set.of(
                                        "script", "img", "iframe", "frame", "source", "audio",
                                        "video", "embed", "input", "track")
                                .contains(tag)
                        && element.hasAttr("src"))
                    reference(source, base, element.attr("src"), false);
                if (tag.equals("video") && element.hasAttr("poster"))
                    reference(source, base, element.attr("poster"), false);
                if (tag.equals("object") && element.hasAttr("data"))
                    reference(source, base, element.attr("data"), false);
                if ((tag.equals("img") || tag.equals("source")) && element.hasAttr("srcset"))
                    srcset(source, base, element.attr("srcset"));
                if (element.hasAttr("style"))
                    StaticCssReferences.scan(
                            element.attr("style"),
                            ref -> reference(source, resolvedBase, ref, false));
                if (tag.equals("style"))
                    StaticCssReferences.scan(
                            element.data(), ref -> reference(source, resolvedBase, ref, false));
            }
        }

        private void srcset(String source, URI base, String value) {
            int position = 0;
            while (position < value.length() && !referenceLimit) {
                while (position < value.length()
                        && (Character.isWhitespace(value.charAt(position))
                                || value.charAt(position) == ',')) position++;
                int start = position;
                // HTML srcset 的 URL 是空白分隔；data URL 内的逗号属于 URL，尾部逗号才分隔候选。
                while (position < value.length() && !Character.isWhitespace(value.charAt(position)))
                    position++;
                String url = value.substring(start, position);
                if (url.endsWith(",")) {
                    int end = url.length();
                    while (end > 0 && url.charAt(end - 1) == ',') end--;
                    reference(source, base, url.substring(0, end), false);
                    continue;
                }
                if (!url.isEmpty()) reference(source, base, url, false);
                // 描述符可含括号；逗号只有在括号外才结束候选，不把 data 的片段当本地文件。
                int depth = 0;
                while (position < value.length()) {
                    char next = value.charAt(position++);
                    if (next == '(') depth++;
                    else if (next == ')' && depth > 0) depth--;
                    else if (next == ',' && depth == 0) break;
                }
            }
        }

        private void reference(String source, URI base, String raw, boolean navigation) {
            if (referenceLimit || raw.isBlank()) return;
            if (references == LIMITS.maxReferences()) {
                referenceLimit = true;
                complete = false;
                add(Code.reference_limit, Severity.warning, source, null, null);
                return;
            }
            references++;
            String reference = raw.strip();
            if (reference.indexOf('#') >= 0) fragment++;
            // 范围外的 data/脚本等并非文件 URI，不能拿 RFC 文件语法误报合法内联内容。
            var scheme = SCHEME.matcher(reference);
            if (scheme.find()) {
                if (Set.of("https", "http").contains(scheme.group(1).toLowerCase(Locale.ROOT)))
                    external++;
                else unsupportedScheme++;
                return;
            }
            if (reference.startsWith("//")) {
                external++;
                return;
            }
            if (reference.startsWith("/")) {
                rootAbsolute++;
                return;
            }
            try {
                if (reference.length() > 1024
                        || reference.indexOf('\\') >= 0
                        || reference.chars().anyMatch(c -> c < 32))
                    throw new IllegalArgumentException();
                URI requested = uri(reference);
                if (requested.getRawPath() == null || requested.getRawPath().isEmpty()) return;
                URI resolved = base.resolve(requested);
                if (ROOT_ORIGIN.equals(resolved.getHost())) {
                    rootAbsolute++;
                    return;
                }
                if (!ORIGIN.equals(resolved.getHost()) || !"https".equals(resolved.getScheme())) {
                    external++;
                    return;
                }
                String decoded = decodePath(resolved.getRawPath());
                String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
                Path target = Path.of(relative).normalize();
                if (target.isAbsolute()
                        || target.startsWith("..")
                        || relative.indexOf('\\') >= 0
                        || relative.indexOf(':') >= 0
                        || relative.chars().anyMatch(c -> c < 32))
                    throw new IllegalArgumentException();
                String path = target.toString().replace('\\', '/');
                if (!existing.containsKey(path))
                    add(
                            navigation ? Code.broken_internal_link : Code.missing_resource,
                            Severity.error,
                            source,
                            path,
                            reference);
            } catch (IllegalArgumentException
                    | URISyntaxException
                    | CharacterCodingException invalid) {
                add(Code.invalid_reference, Severity.error, source, null, reference);
            }
        }

        private void skip(Code reason, String source) {
            skipped++;
            complete = false;
            add(
                    reason,
                    reason == Code.text_unreadable ? Severity.error : Severity.warning,
                    source,
                    null,
                    null);
        }

        private void add(
                Code code, Severity severity, String source, String target, String reference) {
            if (!seen.add(new FindingKey(code, source, target))) return;
            if (severity == Severity.error) errors++;
            else warnings++;
            if (findings.size() < LIMITS.maxFindings()) {
                findings.add(
                        new Finding(code, severity, source, bounded(target), bounded(reference)));
            } else {
                omitted++;
                complete = false;
            }
        }
    }

    private static URI uri(String value) throws URISyntaxException {
        return new URI(value.replace(" ", "%20"));
    }

    /** 百分号只解码一次，保留文件名中的加号；畸形 UTF-8 不猜测成另一个磁盘路径。 */
    private static String decodePath(String path) throws CharacterCodingException {
        StringBuilder result = new StringBuilder();
        int position = 0;
        while (position < path.length()) {
            if (path.charAt(position) != '%') result.append(path.charAt(position++));
            else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                while (position < path.length() && path.charAt(position) == '%') {
                    if (position + 2 >= path.length()) throw new IllegalArgumentException();
                    int high = Character.digit(path.charAt(position + 1), 16);
                    int low = Character.digit(path.charAt(position + 2), 16);
                    if (high < 0 || low < 0) throw new IllegalArgumentException();
                    bytes.write(high * 16 + low);
                    position += 3;
                }
                result.append(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(bytes.toByteArray())));
            }
        }
        return result.toString();
    }

    private static String decodeCss(byte[] content) throws CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (content.length >= 3
                && content[0] == (byte) 0xef
                && content[1] == (byte) 0xbb
                && content[2] == (byte) 0xbf) offset = 3;
        else if (content.length >= 2 && content[0] == (byte) 0xff && content[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (content.length >= 2 && content[0] == (byte) 0xfe && content[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else {
            String prefix =
                    new String(
                            content, 0, Math.min(120, content.length), StandardCharsets.ISO_8859_1);
            var declaration = CSS_CHARSET.matcher(prefix);
            if (declaration.find()) charset = Charset.forName(declaration.group(1));
        }
        return charset.newDecoder()
                .decode(ByteBuffer.wrap(content, offset, content.length - offset))
                .toString();
    }

    private record FindingKey(Code code, String source, String target) {}
}
