package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.web.request.PrototypeSearchRequest.Type;
import io.swagger.v3.oas.annotations.media.Schema;

/** 只含当前已授权固定来源的安全投影；不包含存储路径、访客身份或访问凭据。 */
@Schema(description = "基础搜索命中，类型无关字段为空")
public record PrototypeSearchResponse(
        @Schema(description = "命中类型") Type type,
        @Schema(description = "文案、资料或消息标识；消息并非线程标识") String sourceId,
        @Schema(description = "所属项目") String projectId,
        @Schema(description = "当前项目名称") String projectName,
        @Schema(description = "所属逻辑版本") String versionId,
        @Schema(description = "当前逻辑版本名称") String versionName,
        @Schema(description = "不可变修订") String revisionId,
        @Schema(description = "实际修订创建Unix毫秒，不伪造序号") long revisionCreatedAt,
        @Schema(description = "固定页面标识；资料为空") String pageId,
        @Schema(description = "固定页面规范相对路径；资料为空") String pagePath,
        @Schema(description = "评审线程标识；其他类型为空") String threadId,
        @Schema(description = "最多160个Unicode码点的纯文本标题") String title,
        @Schema(description = "最多240个Unicode码点的命中字段纯文本片段") String snippet,
        @Schema(description = "实际命中字段名，供界面翻译") String matchField,
        @Schema(description = "消息public或internal；其他类型为空") String visibility,
        @Schema(description = "资料prototype、image或document；其他类型为空") String materialType,
        @Schema(description = "文案/资料更新时间，或消息创建Unix毫秒") long updatedAt) {}
