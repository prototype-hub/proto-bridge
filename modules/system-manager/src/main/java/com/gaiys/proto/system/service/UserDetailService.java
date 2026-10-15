package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.UserDetailEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;

@Service
public class UserDetailService extends GenericReactiveCrudService<UserDetailEntity, String> {}
