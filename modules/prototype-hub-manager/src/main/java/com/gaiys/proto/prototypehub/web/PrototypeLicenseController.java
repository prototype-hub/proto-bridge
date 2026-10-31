package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeLicenseService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 安装级凭证管理，独立动作权限与实时有效ADMIN取交集，普通项目权限不能读取客户声明。 */
@RestController
@Authorize
@Resource(id = "prototype-hub-license", name = "安装与凭证管理")
@RequestMapping("/prototype-hub/license")
@Tag(name = "安装与凭证管理")
@RequiredArgsConstructor
public class PrototypeLicenseController {
    private final PrototypeLicenseService licenses;
    private final PrototypeProjectAccessService access;

    @GetMapping
    @QueryAction
    @Operation(summary = "读取安装请求、凭证验证状态与实际准入")
    public Mono<PrototypeLicenseStatus> current() {
        return access.requireAdministrator().then(licenses.current());
    }

    @PostMapping("/_import")
    @SaveAction
    @Operation(summary = "导入本地已签名凭证", description = "全量替代，重复幂等；只验签声明，不启用商业模块或执行扩容")
    public Mono<PrototypeLicenseService.ImportResult> importDocument(
            @Valid @RequestBody ImportRequest request) {
        return access.requireAdministrator().then(licenses.importDocument(request.document()));
    }

    /** 封套只在导入请求和内部持久层使用，不放到查询响应。 */
    @Schema(description = "待验签凭证")
    public record ImportRequest(
            @NotBlank @Size(max = 65536) @Schema(description = "UTF8最多64KiB的完整JSON封套")
                    String document) {}
}
