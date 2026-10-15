package com.gaiys.proto.system.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.defaults.webflux.WebFluxUserController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 沿用 hsweb 用户接口；/me 仅修改姓名，/detail 的完整公开资料由 UserProfileController 管理。 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/user")
@Authorize
@Resource(id = "user", name = "系统用户", group = "system")
public class SecureUserController extends WebFluxUserController {
    @Operation(summary = "修改当前用户的公开名称", description = "兼容姓名更新接口；头像、电话和邮箱请使用 PUT /user/detail")
    @Override
    @PutMapping("/me")
    @ResourceAction(id = "update-self-info", name = "修改当前用户信息")
    public Mono<Boolean> updateLoginUserInfo(@RequestBody UserEntity request) {
        if (request.getName() == null
                || request.getName().isBlank()
                || request.getName().length() > 128
                || request.getPassword() != null
                || request.getSalt() != null
                || request.getStatus() != null
                || request.getType() != null
                || request.getUsername() != null
                || request.getId() != null)
            return Mono.error(new IllegalArgumentException("validation.self_profile_invalid"));
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .flatMap(
                        auth ->
                                getService()
                                        .createUpdate()
                                        .set(UserEntity::getName, request.getName())
                                        .where(UserEntity::getId, auth.getUser().getId())
                                        .execute()
                                        .map(changed -> changed > 0));
    }
}
