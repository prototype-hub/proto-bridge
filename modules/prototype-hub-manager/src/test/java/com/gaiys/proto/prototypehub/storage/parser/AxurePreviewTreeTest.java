package com.gaiys.proto.prototypehub.storage.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.injector.HtmlInspectorInjector;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AxurePreviewTreeTest {
    @TempDir Path root;

    @Test
    void originalAxureBuilderPreservesChineseLabelsPageChildrenAndSourceOrder() throws Exception {
        Path site = root.resolve("existing-site");
        Path prototype = site.resolve("prototype");
        write(prototype, "z-overview.html", "overview");
        write(prototype, "overview-child.html", "child");
        write(prototype, "a-detail.html", "detail");
        write(prototype, "standalone.html", "standalone");
        write(prototype, "data/document.js", originalDocument());

        List<PrototypePageInfo> pages = new AxureSitemapParser().parse(site, 6);

        assertEquals(List.of("业务目录", "独立页面"), names(pages));
        PrototypePageInfo folder = pages.getFirst();
        assertTrue(folder.folder());
        assertNull(folder.path());
        assertEquals(List.of("总览", "详情"), names(folder.children()));
        PrototypePageInfo overview = folder.children().getFirst();
        assertFalse(overview.folder());
        assertEquals("prototype/z-overview.html", overview.path());
        assertEquals("总览子页面", overview.children().getFirst().name());
        assertEquals("prototype/overview-child.html", overview.children().getFirst().path());
        assertEquals("prototype/a-detail.html", folder.children().get(1).path());
    }

    @Test
    void quoteAliasesAndEscapedQuotesSurviveZipExtractionWithoutFallingBackToHtmlTitles()
            throws Exception {
        Path zipFile = root.resolve("quoted-pages.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip(zip, "site/index.html", "<title>Incorrect fallback title</title>");
            zip(zip, "site/child.html", "<title>Incorrect child title</title>");
            zip(
                    zip,
                    "site/data/document.js",
                    """
                    var a='rootNodes',b='pageName',c='url',d='children',e="客户's总览",f='他说"详情"',g="报价\\\"说明";
                    var sitemap=_(a,[_(b,e,c,'index.html',d,[_(b,f,c,'child.html'),_(b,g,c,'child.html')])]);
                    """);
        }
        var properties = new PrototypeHubStorageProperties();
        Path extracted = root.resolve("extracted");
        new SecureZipExtractor(properties, new HtmlInspectorInjector(properties))
                .extract(zipFile, extracted);

        List<PrototypePageInfo> pages = new AxureSitemapParser().parse(extracted, 6);

        assertEquals(
                List.of("site/index.html"), pages.stream().map(PrototypePageInfo::path).toList());
        assertEquals("客户's总览", pages.getFirst().name());
        assertEquals(List.of("他说\"详情\"", "报价\"说明"), names(pages.getFirst().children()));
    }

    @Test
    void missingUnsafeAndNonHtmlParentsKeepOnlyIndependentlySafeDescendantsAndNamedEmptyFolders()
            throws Exception {
        write(root, "child.html", "<title>Child</title>");
        write(root, "other.html", "<title>Other</title>");
        write(root, "image.svg", "<svg/>");
        write(
                root,
                "data/document.js",
                """
                var rootNodes=[
                  {type:'Folder',pageName:'空目录',children:[]},
                  {pageName:'缺失父页',url:'missing.html',children:[{pageName:'有效子页',url:'child.html'}]},
                  {pageName:'越界父页',url:'../../outside.html',children:[{pageName:'安全后代',url:'other.html'}]},
                  {pageName:'非HTML父页',url:'image.svg',children:[{pageName:'页面后代',url:'child.html'}]},
                  {type:'Folder',pageName:'',children:[{pageName:'无名目录页面',url:'other.html'}]},
                  {pageName:'',url:'child.html',children:[{pageName:'无名父页后代',url:'other.html'}]},
                  {pageName:'越界叶子',url:'../../outside.html'}
                ];
                """);

        List<PrototypePageInfo> pages = new AxureSitemapParser().parse(root, 6);

        assertEquals(List.of("空目录", "有效子页", "安全后代", "页面后代", "无名目录页面", "无名父页后代"), names(pages));
        assertTrue(pages.getFirst().folder());
        assertTrue(pages.getFirst().children().isEmpty());
        assertNull(pages.getFirst().path());
        assertEquals(
                List.of("child.html", "other.html", "child.html", "other.html", "other.html"),
                pages.subList(1, pages.size()).stream().map(PrototypePageInfo::path).toList());
    }

    private static List<String> names(List<PrototypePageInfo> pages) {
        return pages.stream().map(PrototypePageInfo::name).toList();
    }

    private static void write(Path site, String path, String content) throws Exception {
        Path target = site.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static void zip(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String originalDocument() {
        return """
                $axure.loadDocument((function() {
                  var _=function() { var r={},a=arguments; for(var i=0;i<a.length;i+=2) r[a[i]]=a[i+1]; return r; };
                  var _creator=function() { return _(a,_(b,[
                    _(c,d,e,f,g,h,i,d,j,[_(c,k,e,l,g,m,i,n,j,[_(c,"4",e,"总览子页面",g,m,i,"overview-child.html")]),_(c,o,e,p,g,m,i,q)]),
                    _(c,r,e,s,g,m,i,t)
                  ])); };
                  var a="sitemap",b="rootNodes",c="id",d="",e="pageName",f="业务目录",g="type",h="Folder",i="url",j="children",k="1",l="总览",m="Wireframe",n="z-overview.html",o="2",p="详情",q="a-detail.html",r="3",s="独立页面",t="standalone.html";
                  return _creator();
                })());
                """;
    }
}
