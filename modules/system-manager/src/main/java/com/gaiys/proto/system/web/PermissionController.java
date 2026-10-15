package com.gaiys.proto.system.web;

import org.hswebframework.web.system.authorization.api.entity.PermissionEntity;
import org.hswebframework.web.system.authorization.defaults.webflux.WebFluxPermissionController;

/** 复用官方权限接口，仅为标准分页查询增加管理端参数边界。 */
public class PermissionController extends WebFluxPermissionController
        implements ManagementQueryController<PermissionEntity, String> {}
