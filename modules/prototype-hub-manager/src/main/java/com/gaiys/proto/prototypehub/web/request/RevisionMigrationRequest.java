package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "旧逻辑版本显式迁移请求；默认仅检查，不写数据库")
public record RevisionMigrationRequest(@Schema(description = "是否在校验后提交迁移") boolean apply) {}
