package se233.project1.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageVectorizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void convertsPngAndJpgToSvg() throws Exception {
        ImageVectorizer vectorizer = new ImageVectorizer();
        ConversionSettings settings = new ConversionSettings();
        Path png = createImage("sample.png", "png", BufferedImage.TYPE_INT_ARGB);
        Path jpg = createImage("sample.jpg", "jpg", BufferedImage.TYPE_INT_RGB);

        for (Path input : new Path[]{png, jpg}) {
            Path output = temporaryDirectory.resolve(input.getFileName() + ".svg");
            vectorizer.vectorize(input, output, settings);
            String svg = Files.readString(output);
            assertTrue(svg.startsWith("<?xml"));
            assertTrue(svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""));
            assertTrue(svg.contains("<path"));
            assertTrue(svg.endsWith("</svg>\n"));
            assertTrue(DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                    .parse(output.toFile()).getDocumentElement().getNodeName().equals("svg"));
        }
    }

    @Test
    void detectsThePaletteAndHonorsTheCustomColorCount() throws Exception {
        ImageVectorizer vectorizer = new ImageVectorizer();
        Path png = createImage("colors.png", "png", BufferedImage.TYPE_INT_ARGB);
        assertTrue(vectorizer.detectColorLimit(png) == 3);

        ConversionSettings settings = new ConversionSettings();
        settings.setCustomColorCount(2);
        Path output = temporaryDirectory.resolve("two-colors.svg");
        vectorizer.vectorize(png, output, settings);
        String svg = Files.readString(output);
        assertTrue(svg.split("<path ", -1).length - 1 <= 2);
    }

    @Test
    void detailAndBackgroundSettingsChangeTheOutput() throws Exception {
        ImageVectorizer vectorizer = new ImageVectorizer();
        Path png = createImage("settings.png", "png", BufferedImage.TYPE_INT_ARGB);

        ConversionSettings low = new ConversionSettings();
        low.setDetailLevel(ConversionSettings.DetailLevel.LOW);
        low.setColorMode(ConversionSettings.ColorMode.UNLIMITED);
        Path lowOutput = temporaryDirectory.resolve("low.svg");
        vectorizer.vectorize(png, lowOutput, low);

        ConversionSettings highTransparent = new ConversionSettings();
        highTransparent.setDetailLevel(ConversionSettings.DetailLevel.HIGH);
        highTransparent.setColorMode(ConversionSettings.ColorMode.UNLIMITED);
        highTransparent.setRemoveBackground(true);
        Path highOutput = temporaryDirectory.resolve("high.svg");
        vectorizer.vectorize(png, highOutput, highTransparent);

        String lowSvg = Files.readString(lowOutput);
        String highSvg = Files.readString(highOutput);
        assertTrue(lowSvg.contains("viewBox=\"0 0 240.000000 120.000000\""));
        assertTrue(highSvg.contains("viewBox=\"0 0 300.000000 150.000000\""));
        assertTrue(highSvg.contains("Generated with Potrace"));
        assertTrue(highSvg.contains(" c"), "Potrace should output smooth cubic curves.");
        assertTrue(lowSvg.contains("fill=\"#fff"));
        assertFalse(highSvg.contains("fill=\"#fff"));
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
