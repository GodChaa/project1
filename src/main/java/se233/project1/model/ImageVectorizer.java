package se233.project1.model;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds color masks, traces each mask with Potrace, and merges the curves into a color SVG. */
public class ImageVectorizer {
    private static final int TRANSPARENT = 0;
    private final PotraceConverter potrace;

    public ImageVectorizer() {
        this(new PotraceConverter());
    }

    ImageVectorizer(PotraceConverter potrace) {
        this.potrace = potrace;
    }

    public void vectorize(Path input, Path output, ConversionSettings settings) throws IOException {
        potrace.requireExecutable();
        BufferedImage source = ImageIO.read(input.toFile());
        if (source == null) {
            throw new IOException(input.getFileName() + " is corrupted or unsupported.");
        }

        BufferedImage working = resizeForDetail(source, settings.getDetailLevel());
        int width = working.getWidth();
        int height = working.getHeight();
        int[] pixels = working.getRGB(0, 0, width, height, null, 0, width);
        if (settings.isRemoveBackground()) {
            removeBackground(pixels, width, height);
        }

        Palette palette = buildPalette(pixels, settings);
        Path temporaryDirectory = Files.createTempDirectory("potrace-layers-");
        try {
            List<TracedLayer> layers = new ArrayList<>();
            List<Integer> layerOrder = layerOrder(palette.counts());
            for (int layerIndex : layerOrder) {
                if (palette.counts()[layerIndex] == 0) continue;
                Path mask = temporaryDirectory.resolve("mask-" + layerIndex + ".pgm");
                Path tracedSvg = temporaryDirectory.resolve("layer-" + layerIndex + ".svg");
                writeMask(mask, palette.assignments(), layerIndex, width, height);
                potrace.trace(mask, tracedSvg, settings.getDetailLevel());
                TracedLayer layer = readLayer(tracedSvg, palette.colors().get(layerIndex));
                if (!layer.paths().isEmpty()) layers.add(layer);
            }
            writeMergedSvg(output, layers, source.getWidth(), source.getHeight(), width, height, settings);
        } finally {
            deleteRecursively(temporaryDirectory);
        }
    }

    public int detectColorLimit(Path input) throws IOException {
        BufferedImage image = ImageIO.read(input.toFile());
        if (image == null) {
            throw new IOException(input.getFileName() + " is corrupted or unsupported.");
        }
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                if (((pixel >>> 24) & 0xff) < 32) continue;
                colors.add(pixel & 0x00ffffff);
                if (colors.size() >= 5) return 5;
            }
        }
        return Math.max(1, colors.size());
    }

    private Palette buildPalette(int[] pixels, ConversionSettings settings) {
        int requestedColors = settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM
                ? settings.getCustomColorCount()
                : switch (settings.getDetailLevel()) {
                    case LOW -> 8;
                    case MEDIUM -> 16;
                    case HIGH -> 24;
                };

        List<Integer> exactColors = exactColorsUpTo(pixels, requestedColors);
        List<Integer> colors = exactColors != null
                ? exactColors
                : kMeansPalette(pixels, requestedColors);
        int[] assignments = new int[pixels.length];
        int[] counts = new int[colors.size()];
        for (int i = 0; i < pixels.length; i++) {
            if (((pixels[i] >>> 24) & 0xff) < 32) {
                assignments[i] = -1;
            } else {
                int nearest = nearestColor(pixels[i], colors);
                assignments[i] = nearest;
                counts[nearest]++;
            }
        }
        return new Palette(colors, assignments, counts);
    }

    private List<Integer> exactColorsUpTo(int[] pixels, int maximum) {
        List<Integer> colors = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int pixel : pixels) {
            if (((pixel >>> 24) & 0xff) < 32) continue;
            int rgb = pixel & 0x00ffffff;
            if (seen.add(rgb)) {
                colors.add(rgb);
                if (colors.size() > maximum) return null;
            }
        }
        if (colors.isEmpty()) colors.add(0);
        return colors;
    }

    private List<Integer> kMeansPalette(int[] pixels, int colorCount) {
        List<Integer> palette = initialPalette(pixels, colorCount);
        for (int iteration = 0; iteration < 10; iteration++) {
            long[] red = new long[palette.size()];
            long[] green = new long[palette.size()];
            long[] blue = new long[palette.size()];
            int[] count = new int[palette.size()];
            for (int pixel : pixels) {
                if (((pixel >>> 24) & 0xff) < 32) continue;
                int nearest = nearestColor(pixel, palette);
                red[nearest] += (pixel >> 16) & 0xff;
                green[nearest] += (pixel >> 8) & 0xff;
                blue[nearest] += pixel & 0xff;
                count[nearest]++;
            }
            for (int i = 0; i < palette.size(); i++) {
                if (count[i] > 0) {
                    palette.set(i, ((int) (red[i] / count[i]) << 16)
                            | ((int) (green[i] / count[i]) << 8)
                            | (int) (blue[i] / count[i]));
                }
            }
        }
        return palette;
    }

    private List<Integer> initialPalette(int[] pixels, int colorCount) {
        Map<Integer, ColorBucket> histogram = new HashMap<>();
        for (int pixel : pixels) {
            if (((pixel >>> 24) & 0xff) < 32) continue;
            int key = (((pixel >> 16) & 0xff) / 16 << 8)
                    | (((pixel >> 8) & 0xff) / 16 << 4)
                    | ((pixel & 0xff) / 16);
            histogram.computeIfAbsent(key, ignored -> new ColorBucket()).add(pixel);
        }
        List<Integer> palette = histogram.values().stream()
                .sorted(Comparator.comparingInt(ColorBucket::count).reversed())
                .limit(colorCount)
                .map(ColorBucket::average)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (palette.isEmpty()) palette.add(0);
        return palette;
    }

    private List<Integer> layerOrder(int[] counts) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer index) -> counts[index]).reversed());
        return order;
    }

    private int nearestColor(int color, List<Integer> palette) {
        int nearest = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.size(); i++) {
            int distance = colorDistance(color, palette.get(i));
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private BufferedImage resizeForDetail(BufferedImage source, ConversionSettings.DetailLevel detail) {
        int maximumDimension = switch (detail) {
            case LOW -> 240;
            case MEDIUM -> 480;
            case HIGH -> 900;
        };
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maximumDimension) return source;
        double scale = maximumDimension / (double) longest;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }

    private void removeBackground(int[] pixels, int width, int height) {
        Map<Integer, Integer> borderColors = new HashMap<>();
        for (int x = 0; x < width; x++) {
            countBorderColor(borderColors, pixels[x]);
            countBorderColor(borderColors, pixels[(height - 1) * width + x]);
        }
        for (int y = 1; y < height - 1; y++) {
            countBorderColor(borderColors, pixels[y * width]);
            countBorderColor(borderColors, pixels[y * width + width - 1]);
        }
        int background = borderColors.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0x00ffffff);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = (pixels[i] >>> 24) & 0xff;
            if (alpha < 32 || colorDistance(pixels[i], background) < 48 * 48) {
                pixels[i] = TRANSPARENT;
            }
        }
    }

    private void countBorderColor(Map<Integer, Integer> counts, int argb) {
        if (((argb >>> 24) & 0xff) < 32) return;
        int bucket = ((argb >> 20) & 0xf) << 20
                | ((argb >> 12) & 0xf) << 12
                | ((argb >> 4) & 0xf) << 4;
        counts.merge(bucket, 1, Integer::sum);
    }

    private int colorDistance(int first, int second) {
        int red = ((first >> 16) & 0xff) - ((second >> 16) & 0xff);
        int green = ((first >> 8) & 0xff) - ((second >> 8) & 0xff);
        int blue = (first & 0xff) - (second & 0xff);
        return red * red + green * green + blue * blue;
    }

    private void writeMask(Path output, int[] assignments, int layer, int width, int height)
            throws IOException {
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
            stream.write(("P5\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (int assignment : assignments) {
                stream.write(assignment == layer ? 0 : 255);
            }
        }
    }

    private TracedLayer readLayer(Path svg, int color) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(svg.toFile());
            Element root = document.getDocumentElement();
            String viewBox = root.getAttribute("viewBox");
            NodeList pathNodes = document.getElementsByTagNameNS("*", "path");
            List<TracedPath> paths = new ArrayList<>();
            for (int i = 0; i < pathNodes.getLength(); i++) {
                Element path = (Element) pathNodes.item(i);
                String data = path.getAttribute("d");
                if (!data.isBlank()) paths.add(new TracedPath(data, combinedTransform(path, root)));
            }
            return new TracedLayer(viewBox, color, paths);
        } catch (Exception exception) {
            throw new IOException("Could not read the SVG generated by Potrace.", exception);
        }
    }

    private String combinedTransform(Element path, Element root) {
        List<String> transforms = new ArrayList<>();
        Node current = path;
        while (current instanceof Element element) {
            String transform = element.getAttribute("transform");
            if (!transform.isBlank()) transforms.add(0, transform);
            if (element == root) break;
            current = element.getParentNode();
        }
        return String.join(" ", transforms);
    }

    private void writeMergedSvg(Path output, List<TracedLayer> layers,
                                int originalWidth, int originalHeight,
                                int tracedWidth, int tracedHeight,
                                ConversionSettings settings) throws IOException {
        Path absoluteOutput = output.toAbsolutePath();
        Files.createDirectories(absoluteOutput.getParent());
        Path temporaryOutput = absoluteOutput.resolveSibling(
                absoluteOutput.getFileName() + "." + UUID.randomUUID() + ".tmp");
        String viewBox = layers.isEmpty() || layers.getFirst().viewBox().isBlank()
                ? "0 0 " + tracedWidth + " " + tracedHeight
                : layers.getFirst().viewBox();

        try (Writer writer = Files.newBufferedWriter(temporaryOutput, StandardCharsets.UTF_8)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + originalWidth
                    + "\" height=\"" + originalHeight + "\" viewBox=\"" + escape(viewBox)
                    + "\" preserveAspectRatio=\"xMidYMid meet\">\n");
            writer.write("  <metadata>Generated with Potrace; detail=" + settings.getDetailLevel()
                    + "; colors=" + settings.getColorMode() + "</metadata>\n");
            for (TracedLayer layer : layers) {
                writer.write("  <g fill=\"" + String.format(Locale.ROOT, "#%06x", layer.color())
                        + "\" stroke=\"none\">\n");
                for (TracedPath path : layer.paths()) {
                    writer.write("    <path");
                    if (!path.transform().isBlank()) {
                        writer.write(" transform=\"" + escape(path.transform()) + "\"");
                    }
                    writer.write(" d=\"" + escape(path.data()) + "\"/>\n");
                }
                writer.write("  </g>\n");
            }
            writer.write("</svg>\n");
        }
        try {
            Files.move(temporaryOutput, absoluteOutput,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryOutput, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary tracing files can be removed by the OS later.
                }
            });
        } catch (IOException ignored) {
            // Temporary tracing files can be removed by the OS later.
        }
    }

    private record Palette(List<Integer> colors, int[] assignments, int[] counts) { }
    private record TracedPath(String data, String transform) { }
    private record TracedLayer(String viewBox, int color, List<TracedPath> paths) { }

    private static final class ColorBucket {
        private long red;
        private long green;
        private long blue;
        private int count;

        void add(int color) {
            red += (color >> 16) & 0xff;
            green += (color >> 8) & 0xff;
            blue += color & 0xff;
            count++;
        }

        int count() {
            return count;
        }

        int average() {
            return ((int) (red / count) << 16)
                    | ((int) (green / count) << 8)
                    | (int) (blue / count);
        }
    }
}
