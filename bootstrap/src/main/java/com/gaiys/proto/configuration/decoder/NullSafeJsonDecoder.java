package com.gaiys.proto.configuration.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.hswebframework.web.api.crud.entity.EntityFactory;
import org.hswebframework.web.i18n.LocaleUtils;
import org.hswebframework.web.starter.jackson.CustomJackson2JsonDecoder;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;

/** 复用官方实体和视图解码，只将 JSON null 转为空结果，由 Spring 判断必需或可选请求体。 */
public class NullSafeJsonDecoder extends CustomJackson2JsonDecoder {
    public NullSafeJsonDecoder(EntityFactory factory, ObjectMapper mapper) {
        super(factory, mapper);
    }

    @Override
    public Mono<Object> decodeToMono(
            Publisher<DataBuffer> input,
            ResolvableType elementType,
            MimeType mimeType,
            Map<String, Object> hints) {
        // 官方5.0.1用map接收readValue的null会抛NPE；保留其locale、reader和buffer释放逻辑。
        return DataBufferUtils.join(input)
                .as(LocaleUtils::transform)
                .flatMap(
                        buffer ->
                                Mono.justOrEmpty(
                                        super.decode(buffer, elementType, mimeType, hints)));
    }
}
