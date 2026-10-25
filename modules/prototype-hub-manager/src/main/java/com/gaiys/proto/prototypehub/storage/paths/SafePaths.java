package com.gaiys.proto.prototypehub.storage.paths;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SafePaths {
    private SafePaths() {}

    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw PrototypeHubException.invalidPath();
        return value;
    }

    public static Path within(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        if (relative == null
                || relative.isBlank()
                || relative.indexOf('\\') >= 0
                || relative.indexOf('\0') >= 0
                || Path.of(relative).isAbsolute()) throw PrototypeHubException.invalidPath();
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base)) throw PrototypeHubException.invalidPath();
        Path current = base;
        if (Files.isSymbolicLink(current)) throw PrototypeHubException.invalidPath();
        for (Path segment : base.relativize(result)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw PrototypeHubException.invalidPath();
        }
        return result;
    }

    public static Path project(Path root, String projectId) {
        return within(root, "projects/" + id(projectId));
    }

    public static Path version(Path root, String projectId, String storageId) {
        return within(root, "projects/" + id(projectId) + "/versions/" + id(storageId));
    }

    public static Path storedVersion(Path root, String projectId, String sitePath) {
        Path versions = project(root, projectId).resolve("versions");
        Path site = within(root, sitePath);
        Path version = site.getParent();
        if (!"site".equals(site.getFileName().toString())
                || version == null
                || !versions.equals(version.getParent())) throw PrototypeHubException.invalidPath();
        id(version.getFileName().toString());
        return version;
    }

    /** 图片独占目录与不可变原型快照分离，文件名固定且不接受上传名称。 */
    public static Path reviewImage(Path root, String projectId, String imagePath) {
        canonicalReviewImagePath(projectId, imagePath);
        return within(root, imagePath);
    }

    public static String canonicalReviewImagePath(String projectId, String imagePath) {
        String prefix = "projects/" + id(projectId) + "/review-images/";
        if (imagePath == null || !imagePath.startsWith(prefix) || !imagePath.endsWith("/image"))
            throw PrototypeHubException.invalidPath();
        String storage = imagePath.substring(prefix.length(), imagePath.length() - 6);
        String canonical = prefix + id(storage) + "/image";
        if (!canonical.equals(imagePath)) throw PrototypeHubException.invalidPath();
        return canonical;
    }

    /** 队列只保存规范快照路径；历史别名先按词法归一，实际删除仍须经过 within 的符号链接检查。 */
    public static String canonicalSitePath(String projectId, String sitePath) {
        id(projectId);
        if (sitePath == null
                || sitePath.isBlank()
                || sitePath.indexOf('\\') >= 0
                || sitePath.indexOf('\0') >= 0) throw PrototypeHubException.invalidPath();
        Path site = Path.of(sitePath).normalize();
        if (site.isAbsolute() || site.getNameCount() != 5)
            throw PrototypeHubException.invalidPath();
        String storage = id(site.getName(3).toString());
        String canonical = "projects/" + projectId + "/versions/" + storage + "/site";
        if (!site.equals(Path.of(canonical))) throw PrototypeHubException.invalidPath();
        return canonical;
    }
}
