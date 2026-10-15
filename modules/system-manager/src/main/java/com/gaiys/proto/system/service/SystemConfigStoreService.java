package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.SystemConfigEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;

@Service
public class SystemConfigStoreService
        extends GenericReactiveCrudService<SystemConfigEntity, String> {}
