package com.gaiys.proto.system.web;

import com.gaiys.proto.system.service.UserSettingService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import com.gaiys.proto.system.web.request.UserSettingRequest;
import com.gaiys.proto.system.web.view.UserSettingView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Every authenticated user manages only their own query history and initialization marker. */
@Tag(name = "个人设置")
@RestController
@RequestMapping("/user/settings")
@Authorize
@RequiredArgsConstructor
public class UserSettingController {
    private final UserSettingService service;

    private Mono<String> owner() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .map(auth -> auth.getUser().getId());
    }

    @Operation(summary = "查询当前用户的设置列表")
    @GetMapping("/{type}")
    public Flux<UserSettingView> list(@PathVariable String type) {
        return owner().flatMapMany(user -> service.list(user, type)).map(UserSettingView::of);
    }

    @Operation(summary = "读取当前用户的设置")
    @GetMapping("/{type}/{key}")
    public Mono<UserSettingView> get(@PathVariable String type, @PathVariable String key) {
        return owner().flatMap(user -> service.get(user, type, key)).map(UserSettingView::of);
    }

    @Operation(summary = "创建当前用户的设置")
    @PostMapping("/{type}")
    public Mono<UserSettingView> create(
            @PathVariable String type, @RequestBody UserSettingRequest input) {
        return owner().flatMap(
                        user -> service.save(user, type, null, input.name(), input.content()))
                .map(UserSettingView::of);
    }

    @Operation(summary = "保存当前用户的设置")
    @PatchMapping("/{type}/{key}")
    public Mono<UserSettingView> save(
            @PathVariable String type,
            @PathVariable String key,
            @RequestBody UserSettingRequest input) {
        return owner().flatMap(user -> service.save(user, type, key, input.name(), input.content()))
                .map(UserSettingView::of);
    }

    @Operation(summary = "删除当前用户的设置")
    @DeleteMapping("/{type}/{key}")
    public Mono<Void> delete(@PathVariable String type, @PathVariable String key) {
        return owner().flatMap(user -> service.delete(user, type, key));
    }

    @Operation(summary = "保存当前用户的初始化标记")
    @PostMapping("/init")
    public Mono<UserSettingView> initialized(@RequestBody Map<String, Object> input) {
        if (!Boolean.TRUE.equals(input.get("init"))) return Mono.error(ManagementSupport.invalid());
        return owner().flatMap(
                        user -> service.save(user, "init", "init", "init", Map.of("init", true)))
                .map(UserSettingView::of);
    }
}
