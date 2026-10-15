package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.PositionEntity;
import java.util.List;
import org.hswebframework.web.crud.service.GenericReactiveTreeSupportCrudService;
import org.hswebframework.web.id.IDGenerator;
import org.springframework.stereotype.Service;

@Service
public class PositionService extends GenericReactiveTreeSupportCrudService<PositionEntity, String> {
    @Override
    public IDGenerator<String> getIDGenerator() {
        return IDGenerator.MD5;
    }

    @Override
    public void setChildren(PositionEntity entity, List<PositionEntity> children) {
        entity.setChildren(children);
    }
}
