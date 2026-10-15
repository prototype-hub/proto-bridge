package com.gaiys.proto.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.configuration.decoder.NullSafeJsonDecoder;
import org.hswebframework.web.api.crud.entity.EntityFactory;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** 在官方 codec 注册后补充 null 安全解码；编码器、multipart 与其他 reader 保持原配置。 */
@Configuration
public class NullSafeJsonCodecConfiguration {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public CodecCustomizer nullSafeJsonDecoder(EntityFactory factory, ObjectMapper mapper) {
        return codecs ->
                codecs.defaultCodecs()
                        .jackson2JsonDecoder(new NullSafeJsonDecoder(factory, mapper));
    }
}
