package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeAssetService;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles.Format;
import com.gaiys.proto.prototypehub.web.request.AssetExportRequest;
import com.gaiys.proto.prototypehub.web.request.AssetQueryRequest;
import com.gaiys.proto.prototypehub.web.response.AssetPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 内部固定修订原始资产入口；query动作与服务实时项目READ共同授权，不增加guest能力。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "原型原始资产")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/assets")
public class PrototypeAssetController {
    private final PrototypeAssetService assets;

    @GetMapping
    @QueryAction
    @Operation(summary = "分页查询固定修订原图片和SVG清单")
    public Mono<AssetPageResponse> list(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @ModelAttribute AssetQueryRequest input) {
        return assets.list(versionId, revisionId, input);
    }

    @PostMapping("/_file")
    @QueryAction
    @Operation(summary = "下载清单中的一个原始资产附件")
    public Mono<Void> file(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody AssetExportRequest input,
            ServerHttpResponse response) {
        return assets.export(versionId, revisionId, input, Format.FILE, response);
    }

    @PostMapping("/_zip")
    @QueryAction
    @Operation(summary = "打包选中或全部原始资产并保留相对路径")
    public Mono<Void> zip(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody AssetExportRequest input,
            ServerHttpResponse response) {
        return assets.export(versionId, revisionId, input, Format.ZIP, response);
    }

    @PostMapping("/_manifest")
    @QueryAction
    @Operation(summary = "下载所选原始资产的路径和哈希JSON")
    public Mono<Void> manifest(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody AssetExportRequest input,
            ServerHttpResponse response) {
        return assets.export(versionId, revisionId, input, Format.MANIFEST, response);
    }
}
