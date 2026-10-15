package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeShareService;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 通过版本预览密钥提供原型运行文件。
 *
 * <p>该入口允许匿名请求，由预览密钥校验授予文件访问，文件路径边界由存储服务维护。
 */
@RestController
@Tag(name = "原型预览访问")
@AllArgsConstructor
@RequestMapping("/prototype-hub/runtime")
public class PrototypeRuntimeController {
    private final PrototypeVersionService versions;
    private final PrototypeStorageService storage;

    @GetMapping("/{versionId}/{previewKey}/{*resourcePath}")
    @Authorize(ignore = true)
    @Operation(summary = "通过版本预览密钥读取原型运行文件")
    public Mono<ResponseEntity<Resource>> resource(
            @PathVariable String versionId,
            @PathVariable String previewKey,
            @PathVariable String resourcePath) {
        return versions.requireById(versionId)
                .filter(v -> PrototypeShareService.matches(v.getPreviewKey(), previewKey))
                .switchIfEmpty(
                        Mono.error(
                                () ->
                                        PrototypeHubException.forbidden(
                                                "error.prototype-hub.preview_access_denied",
                                                "PROTOTYPE_HUB_PREVIEW_ACCESS_DENIED")))
                .flatMap(v -> storage.resolveResource(v, resourcePath))
                .map(PrototypeRuntimeController::response);
    }

    static ResponseEntity<Resource> response(PrototypeStorageService.StoredResource stored) {
        // 上传的原型包含可执行 HTML/SVG；响应级沙箱同时保护内嵌预览和直接访问，保留脚本交互但隔离平台身份。
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "sandbox allow-scripts allow-forms")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .contentType(stored.mediaType())
                .body(stored.resource());
    }
}
