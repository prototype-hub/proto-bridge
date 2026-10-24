package com.gaiys.proto.prototypehub.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaiys.proto.prototypehub.storage.parser.StaticCssReferences;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaticCssReferencesTest {
    @Test
    void extractsLiteralImportsAndUrlsWithCssEscapesAndSkipsCommentsAndOrdinaryStrings() {
        var references = new ArrayList<String>();
        StaticCssReferences.scan(
                """
                /* url(ignored.png) */ @IMPORT /*comment*/ 'nested.css';
                @import URL("more.css");
                .x{content:'url(ignored.png)';background:uRl( image.svg );
                mask:url('a\\20 b.svg');border:url(a\\)b.svg);font:url(\\4e2d \\6587 .woff)}
                .y{background:url("continued\\
                file.svg");content:"\\\\"}
                """,
                references::add);
        assertEquals(
                List.of(
                        "nested.css",
                        "more.css",
                        "image.svg",
                        "a b.svg",
                        "a)b.svg",
                        "中文.woff",
                        "continuedfile.svg"),
                references);
    }

    @Test
    void malformedOrDynamicCssDoesNotInventStaticReferencesOrRecurseIntoImports() {
        var references = new ArrayList<String>();
        StaticCssReferences.scan(
                "myurl(fake.svg);url(var(--dynamic));content:'broken\n;@import url(real.css);url(a b);url('bad' trailing);/*unterminated url(fake.svg)",
                references::add);
        assertEquals(List.of("real.css"), references);
    }

    @Test
    void replacementEscapesAndWhitespaceAreBoundedAndTrailingMalformedTokensTerminate() {
        var references = new ArrayList<String>();
        StaticCssReferences.scan(
                "url(\\0.svg);url('\\110000.svg');url('x\\\r\ny');URL   not-a-function;@import not-a-string;url('unterminated",
                references::add);
        assertEquals(List.of("�.svg", "�.svg", "xy"), references);
    }
}
