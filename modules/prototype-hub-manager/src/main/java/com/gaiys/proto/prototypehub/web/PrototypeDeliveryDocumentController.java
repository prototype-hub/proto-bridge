package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.DeliveryDocumentRenderer;
import com.gaiys.proto.prototypehub.service.PrototypeDeliveryDocumentService;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests;
import com.gaiys.proto.prototypehub.web.response.DeliveryDocumentResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 内部query成员的固定修订说明书会话入口；生成/导出只读，不要求项目EDIT，不接收分享凭据。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/delivery-document")
public class PrototypeDeliveryDocumentController {
    private final PrototypeDeliveryDocumentService documents;

    @PostMapping("/_assemble")
    @QueryAction
    @Operation(summary = "从真实选定来源组装普通文本会话说明书")
    public Mono<DeliveryDocumentResponse> assemble(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody DeliveryDocumentRequests.Document input) {
        return documents.assemble(versionId, revisionId, input);
    }

    @PostMapping("/_export/{format}")
    @QueryAction
    @Operation(summary = "来源复核后导出Markdown图片ZIP或离线HTML")
    public Mono<ResponseEntity<byte[]>> export(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String format,
            @Valid @RequestBody DeliveryDocumentRequests.Document input) {
        return documents
                .export(versionId, revisionId, input, format)
                .map(
                        bytes -> {
                            boolean html = "html".equals(format);
                            return ResponseEntity.ok()
                                    .contentType(
                                            MediaType.parseMediaType(
                                                    html
                                                            ? "text/html;charset=UTF-8"
                                                            : "application/zip"))
                                    .contentLength(bytes.length)
                                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                    .header("X-Content-Type-Options", "nosniff")
                                    .header("Content-Security-Policy", DeliveryDocumentRenderer.CSP)
                                    .header(
                                            HttpHeaders.CONTENT_DISPOSITION,
                                            "attachment; filename=\"delivery-document."
                                                    + (html ? "html" : "zip")
                                                    + "\"")
                                    .body(bytes);
                        });
    }
}
