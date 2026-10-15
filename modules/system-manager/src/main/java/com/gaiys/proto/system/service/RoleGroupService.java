package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.RoleGroupEntity;
import java.util.List;
import org.hswebframework.web.crud.service.GenericReactiveTreeSupportCrudService;
import org.hswebframework.web.id.IDGenerator;
import org.springframework.stereotype.Service;

@Service
public class RoleGroupService
        extends GenericReactiveTreeSupportCrudService<RoleGroupEntity, String> {
    @Override
    public IDGenerator<String> getIDGenerator() {
        return IDGenerator.MD5;
    }

    @Override
    public void setChildren(RoleGroupEntity entity, List<RoleGroupEntity> children) {
        entity.setChildren(children);
    }
}
