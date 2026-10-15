package com.gaiys.proto.system.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Capability directory: this backend has not installed JetLinks asset data permission providers.
 */
@Tag(name = "资产类型")
@RestController
@Authorize
public class AssetTypeController {
    @Operation(summary = "查询已安装的资产权限类型")
    @GetMapping("/asset/types")
    public Mono<List<Map<String, String>>> types() {
        return Mono.just(List.of());
    }
}
