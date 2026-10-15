package com.gaiys.proto.system.web;

import com.gaiys.proto.system.service.BrandingFileStorageService;
import com.gaiys.proto.system.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Bean name replaces hsweb's default controller while retaining its file storage SPI. */
@Tag(name = "品牌文件")
@RestController("reactiveFileController")
@RequestMapping("/file")
@Authorize
@Resource(id = "file", name = "文件上传")
@RequiredArgsConstructor
public class BrandingFileController {
    private final BrandingFileStorageService storage;
    private final SystemConfigService configs;

    @Operation(summary = "上传品牌文件并返回访问地址")
    @PostMapping("/upload")
    @SaveAction
    public Mono<Map<String, String>> upload(@RequestPart("file") FilePart file) {
        return storage.saveFile(file)
                .flatMap(
                        url ->
                                configs.publicFileUrl(url)
                                        .map(access -> Map.of("url", url, "accessUrl", access)));
    }

    @Operation(summary = "上传静态品牌文件")
    @PostMapping("/static")
    @SaveAction
    public Mono<String> staticUpload(@RequestPart("file") FilePart file) {
        return storage.saveFile(file).flatMap(configs::publicFileUrl);
    }

    @Operation(summary = "读取公开品牌图像")
    @GetMapping("/static/{day}/{name}")
    @Authorize(ignore = true)
    public Mono<ResponseEntity<org.springframework.core.io.Resource>> read(
            @PathVariable String day, @PathVariable String name) {
        MediaType type =
                name.endsWith("png")
                        ? MediaType.IMAGE_PNG
                        : name.matches(".*\\.jpe?g")
                                ? MediaType.IMAGE_JPEG
                                : name.endsWith("gif")
                                        ? MediaType.IMAGE_GIF
                                        : MediaType.parseMediaType(
                                                name.endsWith("ico")
                                                        ? "image/x-icon"
                                                        : "image/webp");
        return storage.resource(day, name)
                .map(
                        resource ->
                                ResponseEntity.ok()
                                        .contentType(type)
                                        .header("X-Content-Type-Options", "nosniff")
                                        .header(
                                                "Content-Security-Policy",
                                                "default-src 'none'; sandbox")
                                        .cacheControl(
                                                CacheControl.maxAge(java.time.Duration.ofDays(30)))
                                        .body(resource));
    }
}
