package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeDeletionService;
import com.gaiys.proto.prototypehub.service.PrototypePreviewSessionService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeRevisionMigrationService;
import com.gaiys.proto.prototypehub.service.PrototypeRevisionService;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.PrototypeListQueryRequest;
import com.gaiys.proto.prototypehub.web.request.RevisionMigrationRequest;
import com.gaiys.proto.prototypehub.web.response.PreviewSessionResponse;
import com.gaiys.proto.prototypehub.web.response.RevisionMigrationResponse;
import com.gaiys.proto.prototypehub.web.response.RevisionResponse;
import com.gaiys.proto.prototypehub.web.response.VersionResponse;
import com.gaiys.proto.prototypehub.web.response.VersionUsageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final PrototypeRevisionService revisions;
    private final PrototypeRevisionMigrationService migration;
    private final PrototypeProjectAccessService access;
    private final PrototypePreviewSessionService previews;

    @GetMapping("/versions")
    @QueryAction
    @Operation(summary = "查询原型版本列表")
    public Flux<VersionResponse> list(@RequestParam(required = false) String projectId) {
        return access.readableVersions(projectId)
                .sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()))
                .concatMap(versions::response);
    }

    /** 与版本详情共用项目范围和版本查询动作，修订数量只对当前页批量装配。 */
    @PostMapping("/versions/_query")
    @QueryAction
    @Operation(summary = "按授权范围分页查询原型版本", description = "可选项目范围，支持名称字面包含和稳定排序；不返回永久预览凭证")
    public Mono<PagerResult<VersionResponse>> query(
            @RequestParam(required = false) String projectId,
            @Valid @RequestBody PrototypeListQueryRequest input) {
        return versions.responses(access.readableVersions(projectId, input));
    }

    /** 版本容量沿用版本查询权限；项目存在性由服务校验，不放宽为项目查询权限。 */
    @GetMapping("/versions/_usage")
    @QueryAction
    @Operation(summary = "查询项目版本容量")
    public Mono<VersionUsageResponse> usage(@RequestParam String projectId) {
        return access.requireProject(projectId, Action.READ).then(versions.usage(projectId));
    }

    @GetMapping("/versions/{id}")
    @QueryAction
    @Operation(summary = "查询原型版本详情")
    public Mono<VersionResponse> detail(@PathVariable String id) {
        return access.requireVersion(id, Action.READ).flatMap(versions::response);
    }

    @GetMapping("/versions/{id}/pages")
    @QueryAction
    @Operation(summary = "查询原型版本页面目录")
    public Mono<List<PrototypePageInfo>> pages(@PathVariable String id) {
        return access.requireVersion(id, Action.READ)
                .flatMap(revisions::current)
                .flatMap(revisions::pages);
    }

    @PostMapping("/versions/{id}/preview-session")
    @QueryAction
    @Operation(summary = "获取五分钟内部预览会话", description = "固定签发时快照与项目授权代次，成员撤权或账号停用后失效，逻辑版本替换不改变会话内容")
    public Mono<PreviewSessionResponse> previewSession(@PathVariable String id) {
        return previews.create(id);
    }

    @GetMapping("/versions/{id}/current-revision")
    @QueryAction
    @Operation(summary = "解析当前快照唯一身份，后续页面及会话请求使用返回的快照标识")
    public Mono<RevisionResponse> currentRevision(@PathVariable String id) {
        return access.requireVersion(id, Action.READ)
                .flatMap(revisions::current)
                .map(RevisionResponse::from);
    }

    @GetMapping("/versions/{id}/revisions")
    @QueryAction
    @Operation(summary = "按发布时间和标识倒序查询不可变历史，最多一百条")
    public Flux<RevisionResponse> history(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit) {
        return access.requireVersion(id, Action.READ)
                .flatMapMany(version -> revisions.history(version, after, limit))
                .map(RevisionResponse::from);
    }

    @GetMapping("/versions/{id}/revisions/{rid}")
    @QueryAction
    @Operation(summary = "查询固定快照元数据")
    public Mono<RevisionResponse> revision(@PathVariable String id, @PathVariable String rid) {
        return access.requireVersion(id, Action.READ)
                .flatMap(version -> revisions.require(version, rid))
                .map(RevisionResponse::from);
    }

    @GetMapping("/versions/{id}/revisions/{rid}/pages")
    @QueryAction
    @Operation(summary = "读取发布时保存的固定快照页面清单")
    public Mono<List<PrototypePageInfo>> revisionPages(
            @PathVariable String id, @PathVariable String rid) {
        return access.requireVersion(id, Action.READ)
                .flatMap(version -> revisions.require(version, rid))
                .flatMap(revisions::pages);
    }

    @PostMapping("/versions/{id}/revisions/{rid}/preview-session")
    @QueryAction
    @Operation(summary = "获取固定历史快照的五分钟内部预览会话")
    public Mono<PreviewSessionResponse> revisionPreview(
            @PathVariable String id, @PathVariable String rid) {
        return previews.create(id, rid);
    }

    @PostMapping("/versions/{id}/revision-migration")
    @SaveAction
    @Operation(summary = "管理员显式检查或迁移单版本旧内容及旧分享和创建回执")
    public Mono<RevisionMigrationResponse> migrate(
            @PathVariable String id, @RequestBody RevisionMigrationRequest input) {
        return migration.migrate(id, input.apply());
    }

    @PostMapping(value = "/projects/{projectId}/versions", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "上传原型版本 ZIP 文件")
    public Mono<VersionResponse> upload(
            @PathVariable String projectId,
            @RequestPart String versionName,
            @RequestPart(required = false) String description,
            @RequestPart FilePart file,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return Authentication.currentReactive()
                .flatMap(
                        auth ->
                                upload.upload(
                                        projectId,
                                        versionName,
                                        description,
                                        file,
                                        auth.getUser().getId(),
                                        key))
                .flatMap(versions::response);
    }

    @PutMapping(value = "/versions/{id}", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "替换原型版本 ZIP 文件")
    public Mono<VersionResponse> update(
            @PathVariable String id,
            @RequestPart String versionName,
            @RequestPart(required = false) String description,
            @RequestPart FilePart file,
            @RequestPart(required = false) String expectedRevisionId) {
        return upload.update(id, versionName, description, file, expectedRevisionId)
                .flatMap(versions::response);
    }

    @DeleteMapping("/versions/{id}")
    @DeleteAction
    @Operation(summary = "删除原型版本及分享")
    public Mono<VersionResponse> delete(@PathVariable String id) {
        return deletion.deleteVersion(id).flatMap(versions::response);
    }
}
