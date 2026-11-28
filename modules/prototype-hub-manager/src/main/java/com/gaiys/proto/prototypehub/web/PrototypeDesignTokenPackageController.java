package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeDesignTokenPackageService;
import com.gaiys.proto.prototypehub.web.request.DesignTokenPackageRequests;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument;
import com.gaiys.proto.prototypehub.web.response.DesignTokenPackageImportResponse;
import com.gaiys.proto.prototypehub.web.response.DesignTokenPackageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 成员专用DTCG工作区；菜单动作与项目READ/EDIT相交，不接受分享或访客授权。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定修订DTCG规范包")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/design-token-packages")
public class PrototypeDesignTokenPackageController {
    private final PrototypeDesignTokenPackageService packages;

    @PostMapping(value = "/_validate", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "按EDIT校验本地DTCG原文件，不持久化")
    public Mono<DesignTokenDocument> validate(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @RequestPart FilePart file,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("private, no-store");
        return packages.validate(versionId, revisionId, file);
    }

    @PostMapping(value = "/_import", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "按EDIT导入DTCG原字节；语义无效包需要显式确认")
    public Mono<DesignTokenPackageImportResponse> importPackage(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @RequestPart FilePart file,
            @RequestPart(required = false) String sourceTool,
            @RequestPart(required = false) String sourceName,
            @RequestPart(required = false) String sourceUrl,
            @RequestPart(required = false) String sourceNotes,
            @RequestPart(required = false) String allowInvalid,
            @RequestHeader("Idempotency-Key") String key) {
        return packages.importPackage(
                versionId,
                revisionId,
                file,
                RevisionMaterialRequests.Source.of(sourceTool, sourceName, sourceUrl, sourceNotes),
                allowInvalid == null ? "false" : allowInvalid,
                key);
    }

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按READ数据库分页读取固定修订规范包")
    public Mono<PagerResult<DesignTokenPackageResponse>> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody DesignTokenPackageRequests.Query input,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("private, no-store");
        return packages.query(versionId, revisionId, input);
    }

    @GetMapping("/{packageId}")
    @QueryAction
    public Mono<DesignTokenPackageResponse> detail(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String packageId,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("private, no-store");
        return packages.detail(versionId, revisionId, packageId);
    }

    @PostMapping("/{packageId}/_document")
    @QueryAction
    @Operation(summary = "按READ解析完整有界文档；省略inputs取初始视图，显式输入严格解析")
    public Mono<DesignTokenDocument> document(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String packageId,
            @Valid @RequestBody(required = false) DesignTokenPackageRequests.Document input,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("private, no-store");
        return packages.document(
                versionId,
                revisionId,
                packageId,
                input == null ? new DesignTokenPackageRequests.Document() : input);
    }

    @GetMapping("/{packageId}/content")
    @QueryAction
    @Operation(summary = "按当前READ下载哈希验证的原始JSON字节")
    public Mono<ResponseEntity<org.springframework.core.io.Resource>> read(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String packageId) {
        return packages.read(versionId, revisionId, packageId);
    }

    @PutMapping("/{packageId}")
    @SaveAction
    @Operation(summary = "CAS维护规范包来源，不改变原始字节")
    public Mono<DesignTokenPackageResponse> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String packageId,
            @Valid @RequestBody RevisionMaterialRequests.Update input) {
        return packages.update(versionId, revisionId, packageId, input);
    }

    @PostMapping("/{packageId}/_delete")
    @SaveAction
    @Operation(summary = "CAS永久移除规范包并登记安全文件清理")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String packageId,
            @Valid @RequestBody RevisionMaterialRequests.Delete input) {
        return packages.delete(versionId, revisionId, packageId, input);
    }
}
