package se233.project1.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImageVectorizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void convertsPngAndJpgToValidPureVectorSvg() throws Exception {
        ImageVectorizer vectorizer = new ImageVectorizer();
        ConversionSettings settings = new ConversionSettings();
        Path png = createImage("sample.png", "png", BufferedImage.TYPE_INT_ARGB);
        Path jpg = createImage("sample.jpg", "jpg", BufferedImage.TYPE_INT_RGB);

        for (Path input : new Path[]{png, jpg}) {
            Path output = temporaryDirectory.resolve(input.getFileName() + ".svg");
            vectorizer.vectorize(input, output, settings);
            Document svg = parse(output);
            assertEquals("svg", svg.getDocumentElement().getLocalName());
            assertEquals("300", svg.getDocumentElement().getAttribute("width"));
            assertEquals("150", svg.getDocumentElement().getAttribute("height"));
            assertTrue(svg.getElementsByTagNameNS("*", "path").getLength() > 0);
            assertEquals(0, svg.getElementsByTagNameNS("*", "image").getLength());
        }
    }

    @Test
    void detectsThePaletteAndHonorsEachCustomColorCount() throws Exception {
        ImageVectorizer vectorizer = new ImageVectorizer();
        BufferedImage image = new BufferedImage(250, 50, BufferedImage.TYPE_INT_ARGB);
        int[] colors = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff, 0xff000000};
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, colors[x / 50]);
        }
        Path png = write(image, "five-colors.png");
        assertEquals(5, vectorizer.detectColorLimit(png));
        assertEquals(3, vectorizer.detectColorLimit(createImage("three.png", "png", BufferedImage.TYPE_INT_ARGB)));
        for (int count = 1; count <= 5; count++) {
            ConversionSettings settings = new ConversionSettings();
            settings.setCustomColorCount(count);
            Path output = temporaryDirectory.resolve("custom-" + count + ".svg");
            vectorizer.vectorize(png, output, settings);
            assertEquals(count, palette(parse(output)).size(), "Custom must honor the selected RGB count.");
        }
    }

    @Test
    void unlimitedPreservesEveryOriginalArgbColorWithoutResamplingOrOneProcessPerColor() throws Exception {
        BufferedImage image = new BufferedImage(1024, 2, BufferedImage.TYPE_INT_ARGB);
        Set<Integer> original = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = ((x % 255 + 1) << 24) | (y * image.getWidth() + x);
                image.setRGB(x, y, color);
                original.add(color);
            }
        }
        Path input = write(image, "many-colors.png");
        AtomicInteger calls = new AtomicInteger();
        ImageVectorizer vectorizer = new ImageVectorizer(new PotraceConverter() {
            @Override public String requireExecutable() { return "test-potrace"; }
            @Override public void trace(Path bitmap, Path output, ConversionSettings.DetailLevel detail) {
                calls.incrementAndGet();
                fail("Tiny unique photo colors should use vector runs, without launching thousands of processes.");
            }
        });
        for (ConversionSettings.DetailLevel detail : ConversionSettings.DetailLevel.values()) {
            ConversionSettings settings = unlimited(detail);
            Path output = temporaryDirectory.resolve("unlimited-" + detail + ".svg");
            vectorizer.vectorize(input, output, settings);
            Document svg = parse(output);
            assertEquals("0 0 1024 2", svg.getDocumentElement().getAttribute("viewBox"));
            assertEquals(original, palette(svg), "Unlimited must keep all 2,048 original ARGB colors.");
            assertEquals(0, svg.getElementsByTagNameNS("*", "image").getLength());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void unlimitedTracesMoreThanTwentyFourSubstantialColorsAndKeepsTheirAlpha() throws Exception {
        BufferedImage image = new BufferedImage(1040, 24, BufferedImage.TYPE_INT_ARGB);
        Set<Integer> original = new HashSet<>();
        for (int stripe = 0; stripe < 40; stripe++) {
            int color = ((128 + stripe * 3) << 24) | ((stripe * 6) << 16) | (255 - stripe * 5);
            original.add(color);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = stripe * 26; x < (stripe + 1) * 26; x++) image.setRGB(x, y, color);
            }
        }
        Path input = write(image, "forty-colors.png");
        Path output = temporaryDirectory.resolve("forty-colors.svg");
        new ImageVectorizer().vectorize(input, output, unlimited(ConversionSettings.DetailLevel.LOW));
        Document svg = parse(output);
        assertEquals(original, palette(svg));
        assertEquals("0 0 1040 24", svg.getDocumentElement().getAttribute("viewBox"));
        assertEquals(40, svg.getElementsByTagNameNS("*", "g").getLength());
    }

    @Test
    void eachDetailLevelChangesTheTracedGeometryAndProducesSmoothCurves() throws Exception {
        ImageVectorizer vectorizer = new ImageVectorizer();
        Path png = createImage("detail.png", "png", BufferedImage.TYPE_INT_ARGB);
        Set<String> geometries = new HashSet<>();
        for (ConversionSettings.DetailLevel detail : ConversionSettings.DetailLevel.values()) {
            Path output = temporaryDirectory.resolve("detail-" + detail + ".svg");
            vectorizer.vectorize(png, output, unlimited(detail));
            Document svg = parse(output);
            StringBuilder geometry = new StringBuilder();
            var paths = svg.getElementsByTagNameNS("*", "path");
            for (int i = 0; i < paths.getLength(); i++) {
                geometry.append(((Element) paths.item(i)).getAttribute("d"));
            }
            assertTrue(geometry.toString().matches("(?s).*[cC].*"), "Potrace should output cubic curves.");
            geometries.add(geometry.toString());
        }
        assertEquals(3, geometries.size(), "Detail should alter geometry, not only SVG metadata.");
    }

    @Test
    void backgroundRemovalKeepsEnclosedDetailsWithTheSameColor() throws Exception {
        BufferedImage image = new BufferedImage(120, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 120, 100);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 20, 80, 60);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(40, 35, 40, 30);
        graphics.dispose();
        Path input = write(image, "background.png");
        ConversionSettings settings = unlimited(ConversionSettings.DetailLevel.HIGH);
        settings.setRemoveBackground(true);
        Path output = temporaryDirectory.resolve("without-background.svg");
        new ImageVectorizer().vectorize(input, output, settings);
        Document svg = parse(output);
        assertEquals(Set.of(0xffffffff, 0xff0000ff), palette(svg));
        var groups = svg.getElementsByTagNameNS("*", "g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element group = (Element) groups.item(i);
            if ("#ffffff".equals(group.getAttribute("fill"))) {
                assertEquals("translate(40 35)", group.getAttribute("transform"),
                        "The surviving white region must be the enclosed detail, not the border.");
            }
        }
    }

    @Test
    void alphaIsPreservedAndFullyTransparentPixelsAreOmitted() throws Exception {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x014080c0);
        image.setRGB(1, 0, 0x804080c0);
        image.setRGB(2, 0, 0x00ff0000);
        Path input = write(image, "alpha.png");
        for (ConversionSettings.ColorMode mode : ConversionSettings.ColorMode.values()) {
            ConversionSettings settings = new ConversionSettings();
            settings.setColorMode(mode);
            Path output = temporaryDirectory.resolve("alpha-" + mode + ".svg");
            new ImageVectorizer().vectorize(input, output, settings);
            assertEquals(Set.of(0x014080c0, 0x804080c0), palette(parse(output)));
        }
        assertEquals(1, new ImageVectorizer().detectColorLimit(input));
    }

    @Test
    void anEntirelyTransparentImageProducesAValidEmptySvg() throws Exception {
        Path input = write(new BufferedImage(10, 20, BufferedImage.TYPE_INT_ARGB), "transparent.png");
        Path output = temporaryDirectory.resolve("transparent.svg");
        new ImageVectorizer().vectorize(input, output, new ConversionSettings());
        Document svg = parse(output);
        assertTrue(palette(svg).isEmpty());
        assertEquals(0, svg.getElementsByTagNameNS("*", "path").getLength());
        assertEquals("0 0 10 20", svg.getDocumentElement().getAttribute("viewBox"));
    }

    @Test
    void corruptInputAndMissingPotraceFailWithoutLeavingAnOutput() throws Exception {
        Path input = temporaryDirectory.resolve("broken.png");
        Files.writeString(input, "not an image");
        Path output = temporaryDirectory.resolve("failure.svg");
        assertThrows(IOException.class, () -> new ImageVectorizer().vectorize(input, output, new ConversionSettings()));
        assertFalse(Files.exists(output));
        ImageVectorizer unavailable = new ImageVectorizer(new PotraceConverter(
                List.of(temporaryDirectory.resolve("does-not-exist").toString()),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
        IOException missing = assertThrows(IOException.class,
                () -> unavailable.vectorize(input, output, new ConversionSettings()));
        assertTrue(missing.getMessage().contains("Potrace was not found"));
        assertFalse(Files.exists(output));
    }

    @Test
    void cancellationDuringTracingPreservesExistingOutputAndCleansTemporaryOutput() throws Exception {
        Path input = createImage("cancel.png", "png", BufferedImage.TYPE_INT_ARGB);
        Path output = temporaryDirectory.resolve("existing.svg");
        Files.writeString(output, "existing output");
        ImageVectorizer vectorizer = new ImageVectorizer(new PotraceConverter() {
            @Override public String requireExecutable() { return "test-potrace"; }
            @Override public void trace(Path bitmap, Path svg, ConversionSettings.DetailLevel detail) throws IOException {
                Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0h1v1z\"/></svg>");
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThrows(IOException.class, () -> vectorizer.vectorize(input, output, new ConversionSettings()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("existing output", Files.readString(output));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".vector-")));
        }
    }

    private ConversionSettings unlimited(ConversionSettings.DetailLevel detail) {
        ConversionSettings settings = new ConversionSettings();
        settings.setColorMode(ConversionSettings.ColorMode.UNLIMITED);
        settings.setDetailLevel(detail);
        return settings;
    }

    private Set<Integer> palette(Document svg) {
        Set<Integer> result = new HashSet<>();
        var elements = svg.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String fill = element.getAttribute("fill");
            if (!fill.startsWith("#")) continue;
            String opacity = element.getAttribute("fill-opacity");
            int alpha = opacity.isEmpty() ? 255 : (int) Math.round(Double.parseDouble(opacity) * 255);
            result.add((alpha << 24) | Integer.parseInt(fill.substring(1), 16));
        }
        return result;
    }

    private Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private Path write(BufferedImage image, String name) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
        return path;
    }

    private Path createImage(String fileName, String format, int type) throws Exception {
        BufferedImage image = new BufferedImage(300, 150, type);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 300, 150);
        graphics.setColor(Color.RED);
        graphics.fillOval(60, 20, 100, 100);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(180, 35, 80, 80);
        graphics.dispose();
        Path path = temporaryDirectory.resolve(fileName);
        assertTrue(ImageIO.write(image, format, path.toFile()));
        return path;
    }
}
