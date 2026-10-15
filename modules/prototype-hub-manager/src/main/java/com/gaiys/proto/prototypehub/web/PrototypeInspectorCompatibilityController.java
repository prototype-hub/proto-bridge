package com.gaiys.proto.prototypehub.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为已持久化 HTML 中的旧 Inspector 地址提供匿名只读兼容入口。
 *
 * <p>读取与新地址相同的内置脚本，不修改已上传文件，也不开放旧业务接口或旧权限资源。
 */
@RestController
@Hidden
@Authorize(ignore = true)
public class PrototypeInspectorCompatibilityController {
    @GetMapping(value = "/protohub/public/inspector.js", produces = "application/javascript")
    public ResponseEntity<Resource> inspector() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("prototypehub/inspector.js"));
    }
}
