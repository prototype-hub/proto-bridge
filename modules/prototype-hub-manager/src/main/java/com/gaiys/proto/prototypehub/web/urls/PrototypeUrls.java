package com.gaiys.proto.prototypehub.web.urls;

import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/**
 * 构造原型响应中的预览地址。
 *
 * <p>调用方提供已授权的运行文件基础地址与已保存的入口路径；这里只编码路径中的文件名字符， 保留目录斜杠，不改变存储文件名、访问凭据或运行文件接口地址。
 */
public final class PrototypeUrls {
    private PrototypeUrls() {}

    /** 将入口路径编码一次，使前端的一次 URI 解码能还原原始文件名。 */
    public static String preview(String runtimeBaseUrl, String entryFile) {
        // Spring 按原始分号提取矩阵参数；文件名中的分号必须编码，否则路由会丢失后缀或目录名。
        String encodedEntry =
                UriUtils.encodePath(entryFile, StandardCharsets.UTF_8).replace(";", "%3B");
        return runtimeBaseUrl + "/" + encodedEntry;
    }
}
