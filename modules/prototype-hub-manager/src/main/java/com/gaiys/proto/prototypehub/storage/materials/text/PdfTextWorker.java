package com.gaiys.proto.prototypehub.storage.materials.text;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** 独立子JVM入口，只抽文字层；不渲染页面、不执行JavaScript、不获取外部资源。 */
public final class PdfTextWorker {
    private PdfTextWorker() {}

    public static void main(String[] args) {
        int status = 5;
        try {
            if (args.length != 2
                    || Files.size(Path.of(args[0])) > MaterialTextExtractor.MAX_INPUT_BYTES)
                throw new MaterialTextExtractor.LimitExceeded();
            try (PDDocument document = Loader.loadPDF(Path.of(args[0]).toFile())) {
                if (document.isEncrypted()
                        || !document.getCurrentAccessPermission().canExtractContent()) {
                    status = 3;
                } else {
                    if (document.getNumberOfPages() > MaterialTextExtractor.MAX_PDF_PAGES)
                        throw new MaterialTextExtractor.LimitExceeded();
                    var text = new MaterialTextExtractor.Text();
                    Writer writer =
                            new Writer() {
                                @Override
                                public void write(char[] chars, int offset, int length)
                                        throws IOException {
                                    text.append(new String(chars, offset, length));
                                }

                                @Override
                                public void flush() {}

                                @Override
                                public void close() {}
                            };
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSuppressDuplicateOverlappingText(false);
                    stripper.writeText(document, writer);
                    Files.writeString(Path.of(args[1]), text.value(), StandardCharsets.UTF_8);
                    status = 0;
                }
            }
        } catch (InvalidPasswordException e) {
            status = 3;
        } catch (MaterialTextExtractor.LimitExceeded | OutOfMemoryError e) {
            status = 4;
        } catch (IOException | RuntimeException e) {
            status = 5;
        }
        System.exit(status);
    }
}
