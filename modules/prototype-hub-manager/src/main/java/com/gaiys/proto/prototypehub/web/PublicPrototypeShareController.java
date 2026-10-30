package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeRevisionService;
import com.gaiys.proto.prototypehub.service.PrototypeShareService;
import com.gaiys.proto.prototypehub.service.PrototypeStatisticsService;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.ShareVerifyRequest;
import com.gaiys.proto.prototypehub.web.request.ShareVisitRequest;
import com.gaiys.proto.prototypehub.web.response.PublicShareResponse;
import com.gaiys.proto.prototypehub.web.security.ReviewRequestSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 提供匿名分享信息、密码校验、页面目录和运行文件接口。
 *
 * <p>分享有效期与访问密钥由分享服务校验，资源读取继续受存储服务的安全路径约束。
 */
@RestController
@Tag(name = "公开原型分享")
@AllArgsConstructor
@RequestMapping("/prototype-hub/public")
@Authorize(ignore = true)
public class PublicPrototypeShareController {
    private final PrototypeShareService shares;
    private final PrototypeRevisionService revisions;
    private final PrototypeStorageService storage;
    private final PrototypeStatisticsService statistics;
    private final ReviewRequestSecurity security;

    @GetMapping("/shares/{token}")
    @Operation(summary = "查询公开原型分享信息")
    public Mono<PublicShareResponse> info(@PathVariable String token) {
        return shares.getPublicInfo(token, null);
    }

    @PostMapping("/shares/{token}/verify")
    @Operation(summary = "校验原型分享访问密码")
    public Mono<PublicShareResponse> verify(
            @PathVariable String token, @Valid @RequestBody ShareVerifyRequest input) {
        return shares.getPublicInfo(token, input.password());
    }

    @GetMapping("/shares/{token}/{accessKey}/pages")
    @Operation(summary = "通过分享访问密钥查询页面目录")
    public Mono<List<PrototypePageInfo>> pages(
            @PathVariable String token, @PathVariable String accessKey) {
        return shares.requireAccess(token, accessKey)
                .flatMap(shares::requireRevision)
                .flatMap(revisions::pages);
    }

    @GetMapping("/shares/{token}/{accessKey}/runtime/{*resourcePath}")
    @Operation(summary = "通过分享访问密钥读取运行文件")
    public Mono<ResponseEntity<Resource>> resource(
            @PathVariable String token,
            @PathVariable String accessKey,
            @PathVariable String resourcePath) {
        return shares.requireAccess(token, accessKey)
                .flatMap(shares::requireRevision)
                .flatMap(v -> storage.resolveResource(v, resourcePath))
                .map(PrototypeRuntimeController::response);
    }

    @GetMapping("/shares/{token}/{accessKey}/revisions/{revisionId}/pages")
    @Operation(summary = "通过快照分享凭据查询页面目录")
    public Mono<List<PrototypePageInfo>> revisionPages(
            @PathVariable String token,
            @PathVariable String accessKey,
            @PathVariable String revisionId) {
        return shares.requireRevisionAccess(token, accessKey, revisionId).flatMap(revisions::pages);
    }

    @GetMapping("/shares/{token}/{accessKey}/revisions/{revisionId}/runtime/{*resourcePath}")
    @Operation(summary = "通过快照分享凭据读取运行文件")
    public Mono<ResponseEntity<Resource>> revisionResource(
            @PathVariable String token,
            @PathVariable String accessKey,
            @PathVariable String revisionId,
            @PathVariable String resourcePath) {
        return shares.requireRevisionAccess(token, accessKey, revisionId)
                .flatMap(revision -> storage.resolveResource(revision, resourcePath))
                .map(PrototypeRuntimeController::response);
    }

    /** 与访客身份无关；运行凭据、限时事件票据和精确Origin共同限制公开写入。 */
    @PostMapping("/shares/{token}/{accessKey}/revisions/{revisionId}/visits")
    @Operation(summary = "确认一次成功的外层分享访问", description = "资源请求和切页不计数；重复提交同一票据不重复计数")
    public Mono<Boolean> visit(
            @PathVariable String token,
            @PathVariable String accessKey,
            @PathVariable String revisionId,
            @Valid @RequestBody ShareVisitRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return statistics.record(token, accessKey, revisionId, input.ticket()).thenReturn(true);
    }

    @GetMapping(value = "/inspector.js", produces = "application/javascript")
    @Operation(summary = "读取原型元素检查脚本")
    public ResponseEntity<Resource> inspector() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("prototypehub/inspector.js"));
    }
}
