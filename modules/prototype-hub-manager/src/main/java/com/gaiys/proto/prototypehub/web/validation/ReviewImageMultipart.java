package com.gaiys.proto.prototypehub.web.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeReviewImageService;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.codec.Hints;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 仅图片回复使用的官方multipart reader；在读取时限额，不继承ZIP上传的500MB落盘上限。 */
@Component
public class ReviewImageMultipart {
    private final DefaultPartHttpMessageReader reader = new DefaultPartHttpMessageReader();
    private final ObjectMapper mapper;
    private final Validator validator;

    public ReviewImageMultipart(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
        reader.setMaxInMemorySize(64 * 1024);
        reader.setMaxDiskUsagePerPart(PrototypeReviewImageService.MAX_BYTES);
        reader.setMaxParts(2);
    }

    /** HTTP安全检查先由controller完成；每次请求独占已读part列表，终态均释放框架临时文件。 */
    public <T, R> Mono<R> read(
            ServerWebExchange exchange,
            Class<T> type,
            Class<?>[] groups,
            BiFunction<T, FilePart, Mono<R>> operation) {
        return Mono.usingWhen(
                        Mono.fromSupplier(ArrayList<Part>::new),
                        parts ->
                                reader.read(
                                                ResolvableType.forClass(Part.class),
                                                exchange.getRequest(),
                                                Map.of(Hints.SUPPRESS_LOGGING_HINT, true))
                                        .doOnNext(parts::add)
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                decode(
                                                                        parts, type, groups,
                                                                        operation))),
                        this::delete,
                        (parts, error) -> delete(parts),
                        this::delete)
                .onErrorMap(DataBufferLimitException.class, ignored -> invalid())
                .onErrorMap(DecodingException.class, ignored -> invalid());
    }

    private <T, R> Mono<R> decode(
            List<Part> parts,
            Class<T> type,
            Class<?>[] groups,
            BiFunction<T, FilePart, Mono<R>> operation) {
        if (parts.size() != 2) return Mono.error(invalid());
        Part request = null;
        FilePart image = null;
        for (Part part : parts) {
            if ("request".equals(part.name()) && request == null) request = part;
            else if ("image".equals(part.name()) && image == null && part instanceof FilePart file)
                image = file;
            else return Mono.error(invalid());
        }
        if (request == null
                || image == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(
                        request.headers().getContentType() == null
                                ? MediaType.APPLICATION_OCTET_STREAM
                                : request.headers().getContentType())) return Mono.error(invalid());
        FilePart selected = image;
        return DataBufferUtils.join(request.content(), 64 * 1024)
                .switchIfEmpty(Mono.error(ReviewImageMultipart::invalid))
                .map(
                        buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                T input = mapper.readValue(bytes, type);
                                if (input == null || !validator.validate(input, groups).isEmpty())
                                    throw invalid();
                                return input;
                            } catch (IOException error) {
                                throw invalid();
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .flatMap(input -> operation.apply(input, selected));
    }

    private Mono<Void> delete(List<Part> parts) {
        return Flux.fromIterable(parts).concatMap(Part::delete).then();
    }

    private static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.review_image_parts_invalid",
                "PROTOTYPE_HUB_REVIEW_IMAGE_PARTS_INVALID");
    }
}
