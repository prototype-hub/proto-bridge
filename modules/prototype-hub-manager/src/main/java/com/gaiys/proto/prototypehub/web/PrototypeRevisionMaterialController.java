package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeRevisionMaterialService;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import com.gaiys.proto.prototypehub.web.response.RevisionMaterialResponse;
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

/** 成员资料入口，菜单动作与项目READ/EDIT相交，不接受访客或分享授权。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定修订资料与人工来源")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/materials")
public class PrototypeRevisionMaterialController {
    private final PrototypeRevisionMaterialService materials;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按项目READ数据库分页读取固定修订资料")
    public Mono<PagerResult<RevisionMaterialResponse>> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody RevisionMaterialRequests.Query input) {
        return materials.query(versionId, revisionId, input);
    }

    @PostMapping(consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "按项目EDIT上传固定修订私有图片或文档")
    public Mono<RevisionMaterialResponse> upload(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @RequestPart FilePart file,
            @RequestPart(required = false) String sourceTool,
            @RequestPart(required = false) String sourceName,
            @RequestPart(required = false) String sourceUrl,
            @RequestPart(required = false) String sourceNotes,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return materials.upload(
                versionId,
                revisionId,
                file,
                RevisionMaterialRequests.Source.of(sourceTool, sourceName, sourceUrl, sourceNotes),
                key);
    }

    @PostMapping("/prototype-source")
    @SaveAction
    @Operation(summary = "为尚未记录来源的旧修订登记人工声明")
    public Mono<RevisionMaterialResponse> createSource(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody RevisionMaterialRequests.CreateSource input) {
        return materials.createSource(versionId, revisionId, input);
    }

    @PutMapping("/{materialId}")
    @SaveAction
    @Operation(summary = "CAS维护人工来源声明，保留原始文件")
    public Mono<RevisionMaterialResponse> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String materialId,
            @Valid @RequestBody RevisionMaterialRequests.Update input) {
        return materials.update(versionId, revisionId, materialId, input);
    }

    @PostMapping("/{materialId}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除资料，原型类别仅删除来源声明")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String materialId,
            @Valid @RequestBody RevisionMaterialRequests.Delete input) {
        return materials.delete(versionId, revisionId, materialId, input);
    }

    @GetMapping("/{materialId}/content")
    @QueryAction
    @Operation(summary = "按当前项目READ下载固定修订资料原字节")
    public Mono<ResponseEntity<org.springframework.core.io.Resource>> read(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String materialId) {
        return materials.read(versionId, revisionId, materialId);
    }
}
