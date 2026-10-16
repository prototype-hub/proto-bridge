package com.gaiys.proto.system.web;

import com.gaiys.proto.system.service.BrandingFileStorageService;
import com.gaiys.proto.system.service.SystemConfigService;
import com.gaiys.proto.system.service.SystemManagementService;
import com.gaiys.proto.system.web.request.UserProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 当前用户资料及头像上传接口，由已登录用户调用，用户标识始终来自认证上下文。
 *
 * <p>公开资料由系统管理服务保存；头像复用受限图像存储，不授予品牌文件或用户管理权限。
 */
@Tag(name = "当前用户")
@RestController
@RequestMapping("/user")
@Authorize
@RequiredArgsConstructor
public class UserProfileController {
    private final SystemManagementService management;
    private final DefaultReactiveUserService users;
    private final BrandingFileStorageService files;
    private final SystemConfigService configs;

    /** 用户类型的展示信息，保留公开 Java 类型契约。 */
    @Schema(name = "UserProfileType", description = "用户类型展示信息")
    public record Type(
            @Schema(description = "用户类型标识") String id,
            @Schema(description = "用户类型显示文本") String text) {
        /** 保留原构造器与 id 字段，同时提供标准枚举值。 */
        @Schema(description = "用户类型枚举值，与 id 一致")
        public String getValue() {
            return id;
        }
    }

    /** 用户基本资料的公开 Java 视图；当前接口使用兼容字段映射返回详情。 */
    @Schema(name = "UserProfile", description = "用户基本资料")
    public record Profile(
            @Schema(description = "用户标识") String id,
            @Schema(description = "用户显示名称") String name,
            @Schema(description = "登录用户名") String username,
            @Schema(description = "用户类型") Type type,
            @Schema(description = "用户角色标识列表") List<String> roles) {}

    @Operation(summary = "查询当前用户详情")
    @GetMapping("/detail")
    public Mono<Map<String, Object>> current() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .flatMap(
                        auth ->
                                management
                                        .user(auth.getUser().getId())
                                        .map(
                                                row -> {
                                                    row.put(
                                                            "roles",
                                                            auth.getDimensions("role").stream()
                                                                    .map(d -> d.getId())
                                                                    .toList());
                                                    return row;
                                                }));
    }

    @Operation(summary = "更新当前用户的公开资料", description = "仅可更新姓名、头像、电话和邮箱，用户标识由认证上下文确定")
    @PutMapping("/detail")
    public Mono<Boolean> update(@Valid @RequestBody UserProfileRequest input) {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .flatMap(auth -> management.saveSelfProfile(auth.getUser().getId(), input));
    }

    @Operation(summary = "上传当前用户头像", description = "仅返回图像地址，提交个人资料后才保存为当前用户头像")
    @PostMapping("/me/avatar")
    public Mono<Map<String, String>> uploadAvatar(@RequestPart("file") FilePart file) {
        // 本人自服务只受登录保护；品牌上传仍需 file.save，上传本身不改写任何用户资料。
        return files.saveFile(file)
                .flatMap(
                        url ->
                                configs.publicFileUrl(url)
                                        .map(access -> Map.of("url", url, "accessUrl", access)));
    }

    @Operation(summary = "校验当前用户的旧密码")
    @PostMapping("/me/password/_validate")
    public Mono<Map<String, Boolean>> validateCurrentPassword(@RequestBody String password) {
        // 校验复用官方服务及当前认证身份，不接收目标用户，也不返回账号的散列或盐。
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .flatMap(
                        auth ->
                                users.findByUsernameAndPassword(
                                                auth.getUser().getUsername(), password)
                                        .filter(user -> auth.getUser().getId().equals(user.getId()))
                                        .hasElement())
                .map(passed -> Map.of("passed", passed));
    }

    @Operation(summary = "校验新密码格式", description = "所有已登录用户可校验密码格式，沿用服务端12至128字符策略")
    @PostMapping("/password/_validate")
    public Mono<Map<String, Object>> validatePassword(@RequestBody String password) {
        return Mono.just(management.validatePassword(password));
    }

    @Operation(summary = "查询系统已完成的初始化步骤")
    // This backend's administrator/schema initialization is complete before readiness; no wizard is
    // required.
    @GetMapping("/settings/init")
    public Mono<List<String>> initialized() {
        return Mono.just(List.of("administrator", "permissions", "menus"));
    }
}
