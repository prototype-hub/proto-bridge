package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests.ImageRef;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests.MaterialRef;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests.SourceRef;
import java.util.List;

/** 固定修订的可编辑会话投影；指纹绑定真实来源，正文不是持久化文档或审批结论。 */
public record DeliveryDocumentResponse(
        String projectId,
        String versionId,
        String revisionId,
        String locale,
        String title,
        String introduction,
        List<Page> pages) {
    /** 页面正文和已核对的来源；重排时必须按pageId保留正文。 */
    public record Page(
            String pageId,
            String name,
            String path,
            String body,
            String sourceFingerprint,
            List<SourceRef> descriptions,
            List<MaterialRef> materials,
            List<ImageRef> images,
            List<SourceRef> checks) {}
}
