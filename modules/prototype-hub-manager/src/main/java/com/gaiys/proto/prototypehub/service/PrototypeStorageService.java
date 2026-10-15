package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.parser.AxureSitemapParser;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 管理原型文件上传目录、受限资源读取及页面目录解析。
 *
 * <p>文件系统操作通过 FileWork 隔离；SafePaths 维护存储根目录与符号链接边界， 版本事务和提交前后的目录回收由上传、删除及清理服务协调。
 */
@Service
@AllArgsConstructor
public class PrototypeStorageService {
    private final PrototypeHubStorageProperties properties;
    private final FileWork work;
    private final AxureSitemapParser pages;

    public Mono<UploadPaths> prepareUpload(String projectId, String storageId) {
        return work.call(
                () -> {
                    Path version = SafePaths.version(properties.getRoot(), projectId, storageId);
                    Files.createDirectories(version.getParent());
                    Files.createDirectory(version);
                    return new UploadPaths(
                            version, version.resolve("original.zip"), version.resolve("site"));
                });
    }

    public Mono<Void> receive(FilePart file, Path target) {
        return Mono.defer(
                () -> {
                    AtomicLong bytes = new AtomicLong();
                    // 超限缓冲区立即释放；取消时丢弃的缓冲区仍交给 DataBufferUtils 回收。
                    Flux<DataBuffer> content =
                            file.content()
                                    .<DataBuffer>handle(
                                            (buffer, sink) -> {
                                                if (bytes.addAndGet(buffer.readableByteCount())
                                                        > properties.getMaxUploadSizeBytes()) {
                                                    DataBufferUtils.release(buffer);
                                                    sink.error(
                                                            PrototypeHubException.badRequest(
                                                                    "error.prototype-hub.zip_too_large",
                                                                    "PROTOTYPE_HUB_ZIP_TOO_LARGE"));
                                                } else sink.next(buffer);
                                            })
                                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                    return DataBufferUtils.write(
                            content,
                            target,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                });
    }

    public String relativeSitePath(String project, String storage) {
        return "projects/" + SafePaths.id(project) + "/versions/" + SafePaths.id(storage) + "/site";
    }

    public Mono<Void> deleteVersion(String project, String storage) {
        return work.run(
                () ->
                        SecureZipExtractor.deleteRecursively(
                                SafePaths.version(properties.getRoot(), project, storage)));
    }

    public Mono<Void> deleteStoredVersion(String project, String storagePath, String version) {
        return work.run(
                () ->
                        SecureZipExtractor.deleteRecursively(
                                SafePaths.storedVersion(
                                        properties.getRoot(), project, storagePath)));
    }

    public Mono<Void> deleteVersion(PrototypeVersionEntity version) {
        return deleteStoredVersion(
                version.getProjectId(), version.getStoragePath(), version.getId());
    }

    public Mono<Void> deleteProject(String project) {
        return work.run(
                () ->
                        SecureZipExtractor.deleteRecursively(
                                SafePaths.project(properties.getRoot(), project)));
    }

    public Mono<StoredResource> resolveResource(PrototypeVersionEntity version, String requested) {
        return work.call(
                () -> {
                    Path site =
                            SafePaths.storedVersion(
                                            properties.getRoot(),
                                            version.getProjectId(),
                                            version.getStoragePath())
                                    .resolve("site");
                    String relative =
                            requested == null || requested.isBlank() || requested.equals("/")
                                    ? version.getEntryFile()
                                    : requested.replaceFirst("^/+", "");
                    Path file = SafePaths.within(site, relative);
                    if (!Files.isRegularFile(file)) throw PrototypeHubException.missing("resource");
                    return new StoredResource(
                            new FileSystemResource(file),
                            MediaTypeFactory.getMediaType(file.getFileName().toString())
                                    .orElse(MediaType.APPLICATION_OCTET_STREAM));
                });
    }

    public Mono<List<PrototypePageInfo>> listPages(PrototypeVersionEntity version) {
        return work.call(
                () ->
                        pages.parse(
                                SafePaths.storedVersion(
                                                properties.getRoot(),
                                                version.getProjectId(),
                                                version.getStoragePath())
                                        .resolve("site"),
                                properties.getMaxEntrySearchDepth()));
    }

    /** 单次上传独占的目录和文件路径，仅在上传资源生命周期内使用。 */
    public record UploadPaths(Path root, Path zip, Path site) {}

    /** 完成安全路径解析后的响应资源及其媒体类型。 */
    public record StoredResource(Resource resource, MediaType mediaType) {}
}
