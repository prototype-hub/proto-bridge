package com.gaiys.proto.prototypehub.storage.materials.text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 独立短命JVM只将显式一页静态绘为PNG；不运行PDF脚本、动作、表单或远程内容，不建立外部资源加载器。 */
public final class PdfPreviewWorker {
    private PdfPreviewWorker() {}

    public static void main(String[] args) {
        int status = 5;
        try {
            if (args.length != 4
                    || Files.size(Path.of(args[0])) > MaterialTextExtractor.MAX_INPUT_BYTES)
                throw new MaterialTextExtractor.LimitExceeded();
            int page = Integer.parseInt(args[3]);
            try (var document = Loader.loadPDF(Path.of(args[0]).toFile())) {
                if (document.isEncrypted()) {
                    status = 3;
                } else if (document.getNumberOfPages() > PdfPreviewProcess.MAX_PAGES) {
                    status = 4;
                } else if (page < 0 || page >= document.getNumberOfPages()) {
                    status = 6;
                } else {
                    var target = document.getPage(page);
                    var crop = target.getCropBox();
                    double width = crop.getWidth() * 96d / 72;
                    double height = crop.getHeight() * 96d / 72;
                    if (!Double.isFinite(width)
                            || !Double.isFinite(height)
                            || width <= 0
                            || height <= 0) throw new MaterialTextExtractor.LimitExceeded();
                    width = Math.max(1, Math.floor(width));
                    height = Math.max(1, Math.floor(height));
                    if (target.getRotation() == 90 || target.getRotation() == 270) {
                        double swap = width;
                        width = height;
                        height = swap;
                    }
                    if (width > PdfPreviewProcess.MAX_DIMENSION
                            || height > PdfPreviewProcess.MAX_DIMENSION
                            || width * height > PdfPreviewProcess.MAX_PIXELS)
                        throw new MaterialTextExtractor.LimitExceeded();
                    var image =
                            new PDFRenderer(document).renderImageWithDPI(page, 96, ImageType.RGB);
                    try {
                        if (image.getWidth() > PdfPreviewProcess.MAX_DIMENSION
                                || image.getHeight() > PdfPreviewProcess.MAX_DIMENSION
                                || (long) image.getWidth() * image.getHeight()
                                        > PdfPreviewProcess.MAX_PIXELS)
                            throw new MaterialTextExtractor.LimitExceeded();
                        Path output = Path.of(args[1]);
                        if (!ImageIO.write(image, "png", output.toFile()))
                            throw new IOException("PNG unavailable");
                        if (Files.size(output) > PdfPreviewProcess.MAX_PNG_BYTES)
                            throw new MaterialTextExtractor.LimitExceeded();
                        Files.writeString(
                                Path.of(args[2]),
                                document.getNumberOfPages()
                                        + ","
                                        + image.getWidth()
                                        + ","
                                        + image.getHeight());
                        status = 0;
                    } finally {
                        image.flush();
                    }
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
