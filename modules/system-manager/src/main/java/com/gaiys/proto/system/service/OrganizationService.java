package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.OrganizationEntity;
import java.util.List;
import org.hswebframework.web.crud.service.GenericReactiveTreeSupportCrudService;
import org.hswebframework.web.id.IDGenerator;
import org.springframework.stereotype.Service;

@Service
public class OrganizationService
        extends GenericReactiveTreeSupportCrudService<OrganizationEntity, String> {
    @Override
    public IDGenerator<String> getIDGenerator() {
        return IDGenerator.MD5;
    }

    @Override
    public void setChildren(OrganizationEntity entity, List<OrganizationEntity> children) {
        entity.setChildren(children);
    }
}
