package dev.abgleich.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Takes the screenshots of the README from a running Abgleich and joins them into an animated GIF. Uses only
 * synthetic example data (B41).
 *
 * <p>{@code ./gradlew :smoke-tests:tour -Psmoke.baseUrl=http://localhost}
 */
public final class Tour {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 820;
    private static final int GIF_WIDTH = 960;
    private static final int FRAME_MILLIS = 2600;

    private Tour() {
    }

    public static void main(String[] args) throws IOException {
        String baseUrl = args[0].replaceAll("/+$", "");
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        List<BufferedImage> frames = new ArrayList<>();
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch()) {
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(WIDTH, HEIGHT)
                    .setColorScheme(com.microsoft.playwright.options.ColorScheme.LIGHT));

            page.navigate(baseUrl + "/");
            frames.add(shot(page, out, "1-upload"));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load both")).click();
            page.getByText("Example loaded.").waitFor();
            page.locator("#result article").first().scrollIntoViewIfNeeded();
            frames.add(shot(page, out, "2-imported"));

            page.navigate(baseUrl + "/review");
            frames.add(shot(page, out, "3-review"));
            page.navigate(baseUrl + "/invoices");
            frames.add(shot(page, out, "4-invoices"));
            page.navigate(baseUrl + "/summary");
            frames.add(shot(page, out, "5-summary"));
        }
        writeGif(frames, out.resolve("abgleich-tour.gif"));
        System.out.println("Screenshots and GIF written to " + out);
    }

    private static BufferedImage shot(Page page, Path out, String name) throws IOException {
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setPath(out.resolve(name + ".png")));
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static void writeGif(List<BufferedImage> frames, Path file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(Files.newOutputStream(file))) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (BufferedImage frame : frames) {
                BufferedImage scaled = scale(frame);
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(scaled),
                        writer.getDefaultWriteParam());
                configureFrame(metadata);
                writer.writeToSequence(new IIOImage(scaled, null, metadata), (ImageWriteParam) null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage scale(BufferedImage frame) {
        int height = frame.getHeight() * GIF_WIDTH / frame.getWidth();
        BufferedImage scaled = new BufferedImage(GIF_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(frame, 0, 0, GIF_WIDTH, height, null);
        graphics.dispose();
        return scaled;
    }

    /** Frame delay and endless looping, in the metadata format the JDK GIF writer understands. */
    private static void configureFrame(IIOMetadata metadata) throws IOException {
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(FRAME_MILLIS / 10));
        control.setAttribute("transparentColorIndex", "0");
        IIOMetadataNode application = new IIOMetadataNode("ApplicationExtension");
        application.setAttribute("applicationID", "NETSCAPE");
        application.setAttribute("authenticationCode", "2.0");
        application.setUserObject(new byte[] {0x1, 0, 0});
        child(root, "ApplicationExtensions").appendChild(application);
        metadata.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
