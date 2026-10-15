package com.gaiys.proto.system.web;

import org.hswebframework.web.dictionary.entity.DictionaryEntity;
import org.hswebframework.web.dictionary.webflux.WebfluxDictionaryController;

/** 复用官方字典接口，仅为标准分页查询增加管理端参数边界。 */
public class DictionaryController extends WebfluxDictionaryController
        implements ManagementQueryController<DictionaryEntity, String> {}
