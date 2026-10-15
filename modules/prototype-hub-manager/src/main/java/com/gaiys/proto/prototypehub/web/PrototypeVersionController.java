package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeDeletionService;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.response.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 提供原型版本查询、ZIP 上传及替换接口。
 *
 * <p>操作权限由 hsweb 注解承接，文件生命周期与事务协调由上传和删除服务负责。
 */
@RestController
@Tag(name = "原型版本管理")
@AllArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@RequestMapping("/prototype-hub")
public class PrototypeVersionController {
    private final PrototypeVersionService versions;
    private final PrototypeUploadService upload;
    private final PrototypeDeletionService deletion;
    private final PrototypeStorageService storage;

    @GetMapping("/versions")
    @QueryAction
    @Operation(summary = "查询原型版本列表")
    public Flux<VersionResponse> list(@RequestParam(required = false) String projectId) {
        return versions.findAll(projectId)
                .sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()))
                .map(VersionResponse::from);
    }

    @GetMapping("/versions/{id}")
    @QueryAction
    @Operation(summary = "查询原型版本详情")
    public Mono<VersionResponse> detail(@PathVariable String id) {
        return versions.requireById(id).map(VersionResponse::from);
    }

    @GetMapping("/versions/{id}/pages")
    @QueryAction
    @Operation(summary = "查询原型版本页面目录")
    public Mono<List<PrototypePageInfo>> pages(@PathVariable String id) {
        return versions.requireById(id).flatMap(storage::listPages);
    }

    @PostMapping(value = "/projects/{projectId}/versions", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "上传原型版本 ZIP 文件")
    public Mono<VersionResponse> upload(
            @PathVariable String projectId,
            @RequestPart String versionName,
            @RequestPart(required = false) String description,
            @RequestPart FilePart file) {
        return Authentication.currentReactive()
                .flatMap(
                        auth ->
                                upload.upload(
                                        projectId,
                                        versionName,
                                        description,
                                        file,
                                        auth.getUser().getId()))
                .map(VersionResponse::from);
    }

    @PutMapping(value = "/versions/{id}", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "替换原型版本 ZIP 文件")
    public Mono<VersionResponse> update(
            @PathVariable String id,
            @RequestPart String versionName,
            @RequestPart(required = false) String description,
            @RequestPart FilePart file) {
        return upload.update(id, versionName, description, file).map(VersionResponse::from);
    }

    @DeleteMapping("/versions/{id}")
    @DeleteAction
    @Operation(summary = "删除原型版本及分享")
    public Mono<VersionResponse> delete(@PathVariable String id) {
        return deletion.deleteVersion(id).map(VersionResponse::from);
    }
}
