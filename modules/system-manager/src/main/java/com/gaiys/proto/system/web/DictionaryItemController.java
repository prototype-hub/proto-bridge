package com.gaiys.proto.system.web;

import org.hswebframework.web.dictionary.entity.DictionaryItemEntity;
import org.hswebframework.web.dictionary.webflux.WebfluxDictionaryItemController;

/** 复用官方字典接口，仅为标准分页查询增加管理端参数边界。 */
public class DictionaryItemController extends WebfluxDictionaryItemController
        implements ManagementQueryController<DictionaryItemEntity, String> {}
