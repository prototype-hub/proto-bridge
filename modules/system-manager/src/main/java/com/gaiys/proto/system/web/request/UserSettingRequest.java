package com.gaiys.proto.system.web.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** 个人设置写入内容。所属用户和设置范围由控制器解析，不接受请求指定其他用户。 */
@Schema(description = "个人设置写入请求")
public record UserSettingRequest(
        @Schema(description = "设置显示名称") String name,
        @Schema(description = "设置内容，保留字符串、对象等 JSON 值类型") Object content) {}
