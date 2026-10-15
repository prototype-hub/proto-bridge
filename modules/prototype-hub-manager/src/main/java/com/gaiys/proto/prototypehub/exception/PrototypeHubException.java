package com.gaiys.proto.prototypehub.exception;

import org.hswebframework.web.exception.BusinessException;

/**
 * PrototypeHub 业务异常，使用统一的国际化消息键、错误码和 HTTP 状态响应。
 *
 * <p>错误码使用 PROTOTYPE_HUB 前缀，消息键使用 error.prototype-hub；HTTP 状态及参数语义保持一致。
 */
public class PrototypeHubException extends BusinessException {
    public PrototypeHubException(String key, String code, int status, Object... args) {
        super(key, code, status, args);
    }

    public static PrototypeHubException badRequest(String key, String code, Object... args) {
        return new PrototypeHubException(key, code, 400, args);
    }

    public static PrototypeHubException forbidden(String key, String code, Object... args) {
        return new PrototypeHubException(key, code, 403, args);
    }

    public static PrototypeHubException notFound(String key, String code, Object... args) {
        return new PrototypeHubException(key, code, 404, args);
    }

    public static PrototypeHubException internal(String key, String code, Object... args) {
        return new PrototypeHubException(key, code, 500, args);
    }

    public static PrototypeHubException missing(String resource) {
        return notFound(
                "error.prototype-hub." + resource + "_not_found",
                "PROTOTYPE_HUB_" + resource.toUpperCase(java.util.Locale.ROOT) + "_NOT_FOUND");
    }

    public static PrototypeHubException invalidPath() {
        return forbidden(
                "error.prototype-hub.storage_path_invalid", "PROTOTYPE_HUB_STORAGE_PATH_INVALID");
    }

    public static PrototypeHubException projectHasVersions() {
        return new PrototypeHubException(
                "error.prototype-hub.project_has_versions",
                "PROTOTYPE_HUB_PROJECT_HAS_VERSIONS",
                409);
    }
}
