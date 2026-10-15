package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.RoleMenuGrantEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;

@Service
public class RoleMenuGrantService extends GenericReactiveCrudService<RoleMenuGrantEntity, String> {}
