package com.gaiys.proto.prototypehub.service.comparison;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.RevisionComparisonRequest;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.Counts;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.FileDifference;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.FileSide;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.PageDifference;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.PageSide;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.Status;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.Summary;
import com.gaiys.proto.prototypehub.web.response.RevisionResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;

/**
 * 在 FileWork 中对已授权的两份有界清单做纯计算；路径是匹配依据，不猜重命名或视觉变化。
 *
 * <p>每次请求独立计算，不访问磁盘、数据库或缓存；持久旧清单的完整性在解析边界校验。
 */
public final class PrototypeRevisionComparison {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private PrototypeRevisionComparison() {}

    public static RevisionComparisonResponse compare(
            PrototypeRevisionEntity left,
            PrototypeRevisionEntity right,
            RevisionComparisonRequest input) {
        Snapshot before = snapshot(left.getManifestJson());
        Snapshot after = snapshot(right.getManifestJson());
        var files = new ArrayList<FileDifference>();
        for (String path : paths(before.files(), after.files())) {
            var first = before.files().get(path);
            var second = after.files().get(path);
            files.add(new FileDifference(path, status(first, second), side(first), side(second)));
        }
        var pages = new ArrayList<PageDifference>();
        for (String path : paths(before.pages(), after.pages())) {
            var first = before.pages().get(path);
            var second = after.pages().get(path);
            Status status = status(before.files().get(path), after.files().get(path));
            // 页面从 sitemap 增删不要求对应文件也增删；名称变化独立于文件字节变化。
            if (first == null) status = Status.added;
            else if (second == null) status = Status.removed;
            else if (!Objects.equals(first.name(), second.name())) status = Status.changed;
            pages.add(new PageDifference(path, status, first, second));
        }
        Summary summary =
                new Summary(
                        counts(pages.stream().map(PageDifference::status)),
                        counts(files.stream().map(FileDifference::status)));
        if (!input.isIncludeUnchanged()) {
            pages.removeIf(row -> row.status() == Status.unchanged);
            files.removeIf(row -> row.status() == Status.unchanged);
        }
        return new RevisionComparisonResponse(
                left.getVersionId(),
                RevisionResponse.from(left),
                RevisionResponse.from(right),
                summary,
                page(pages, input.getPagesPageIndex(), input.getPagesPageSize()),
                page(files, input.getFilesPageIndex(), input.getFilesPageSize()));
    }

    private static Snapshot snapshot(String json) {
        PrototypeManifest manifest = PrototypeManifestBuilder.parse(json);
        if (manifest == null || manifest.formatVersion() != 1) throw invalid();
        Map<String, PrototypeManifest.FileEntry> files = new HashMap<>();
        for (var file : manifest.files()) {
            if (file == null
                    || !safePath(file.path())
                    || file.size() < 0
                    || file.sha256() == null
                    || !SHA256.matcher(file.sha256()).matches()
                    || files.putIfAbsent(file.path(), file) != null) throw invalid();
        }
        if (!files.containsKey(manifest.entryFile())) throw invalid();
        Map<String, PageSide> pages = new LinkedHashMap<>();
        collectPages(manifest.pages(), files, pages);
        return new Snapshot(files, pages);
    }

    private static void collectPages(
            List<PrototypePageInfo> nodes,
            Map<String, PrototypeManifest.FileEntry> files,
            Map<String, PageSide> pages) {
        for (var node : nodes) {
            if (node == null) throw invalid();
            if (!node.folder()) {
                if (!files.containsKey(node.path())
                        || node.name() == null
                        || (node.pageId() != null && !SHA256.matcher(node.pageId()).matches()))
                    throw invalid();
                // 同路径别名保留第一次出现的信息，真实页面即使有 children 也计入自身。
                pages.putIfAbsent(
                        node.path(), new PageSide(node.pageId(), node.name(), node.path()));
            }
            collectPages(node.children(), files, pages);
        }
    }

    /** 这里只检查规范路径文本；不得调用会探测符号链接的文件路径解析器。 */
    private static boolean safePath(String path) {
        if (path == null
                || path.isBlank()
                || path.length() > 1024
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.chars().anyMatch(c -> c < 32)) return false;
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static TreeSet<String> paths(Map<String, ?> left, Map<String, ?> right) {
        var paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        return paths;
    }

    private static Status status(
            PrototypeManifest.FileEntry left, PrototypeManifest.FileEntry right) {
        if (left == null) return Status.added;
        if (right == null) return Status.removed;
        return left.size() != right.size() || !left.sha256().equalsIgnoreCase(right.sha256())
                ? Status.changed
                : Status.unchanged;
    }

    private static FileSide side(PrototypeManifest.FileEntry file) {
        return file == null ? null : new FileSide(file.size(), file.sha256(), file.mediaType());
    }

    private static Counts counts(Stream<Status> statuses) {
        var counts =
                statuses.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return new Counts(
                counts.getOrDefault(Status.added, 0L),
                counts.getOrDefault(Status.removed, 0L),
                counts.getOrDefault(Status.changed, 0L),
                counts.getOrDefault(Status.unchanged, 0L));
    }

    private static <T> PagerResult<T> page(List<T> rows, int pageIndex, int pageSize) {
        // 页码允许超过实际末页；先用 long 计算偏移，避免大页码溢出后返回错误数据。
        int start = (int) Math.min((long) pageIndex * pageSize, rows.size());
        int end = Math.min(start + pageSize, rows.size());
        return PagerResult.of(
                rows.size(),
                List.copyOf(rows.subList(start, end)),
                QueryParamEntity.of().doPaging(pageIndex, pageSize));
    }

    private static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.manifest_invalid", "PROTOTYPE_HUB_MANIFEST_INVALID");
    }

    private record Snapshot(
            Map<String, PrototypeManifest.FileEntry> files, Map<String, PageSide> pages) {}
}
