package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeShareService;
import com.gaiys.proto.prototypehub.web.request.ShareCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ShareVerifyRequest;
import com.gaiys.proto.prototypehub.web.response.ShareResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 提供原型版本分享链接的管理接口。
 *
 * <p>本入口使用已登录用户的资源操作权限；匿名分享访问由公开分享控制器承接。
 */
@RestController
@Tag(name = "原型分享管理")
@AllArgsConstructor
@Authorize
@Resource(id = "prototype-hub-share", name = "原型分享管理")
@RequestMapping("/prototype-hub")
public class PrototypeShareController {
    private final PrototypeShareService shares;
    private final PrototypeProjectAccessService access;

    @GetMapping("/versions/{versionId}/shares")
    @QueryAction
    @Operation(summary = "查询原型版本分享列表")
    public Flux<ShareResponse> list(@PathVariable String versionId) {
        return access.requireVersion(versionId, PrototypeProjectAccessService.Action.SHARE)
                .thenMany(shares.findByVersionId(versionId))
                .concatMap(share -> access.correlatedVersion(share).thenReturn(share))
                .map(ShareResponse::from);
    }

    @PostMapping("/versions/{versionId}/shares")
    @SaveAction
    @Operation(summary = "创建原型版本分享链接")
    public Mono<ShareResponse> create(
            @PathVariable String versionId, @Valid @RequestBody ShareCreateRequest input) {
        return Authentication.currentReactive()
                .flatMap(auth -> shares.createShare(versionId, input, auth.getUser().getId()))
                .map(ShareResponse::from);
    }

    /** 沿用分享查询权限与项目分享管理范围；校验已知密码，不读取或返回明文密码。 */
    @PostMapping("/shares/{id}/verify-password")
    @QueryAction
    @Operation(summary = "校验管理侧原型分享密码", description = "仅返回是否匹配，不授予访问密钥，支持过期或禁用分享")
    public Mono<Boolean> verifyPassword(
            @PathVariable String id, @Valid @RequestBody ShareVerifyRequest input) {
        return shares.verifyPassword(id, input.password());
    }

    @DeleteMapping("/shares/{id}")
    @DeleteAction
    @Operation(summary = "删除原型分享链接")
    public Mono<ShareResponse> delete(@PathVariable String id) {
        return shares.deleteShare(id).map(ShareResponse::from);
    }
}
