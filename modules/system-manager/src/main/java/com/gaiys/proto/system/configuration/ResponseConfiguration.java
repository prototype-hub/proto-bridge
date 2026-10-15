package com.gaiys.proto.system.configuration;

import com.gaiys.proto.system.web.response.ApiResponse;
import org.hswebframework.web.crud.entity.factory.MapperEntityFactory;
import org.hswebframework.web.crud.web.ResponseMessage;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResponseConfiguration {
    @Bean
    public InitializingBean responseMapping(MapperEntityFactory factory) {
        return () -> factory.addMapping(ResponseMessage.class, ApiResponse::new);
    }
}
