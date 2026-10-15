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
}
