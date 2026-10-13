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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
public class ImageVectorizer {
    private static final int MAX_TRACED_REGIONS = 64;
    private static final int RUN_BUFFER_SIZE = 65_536;
    private final PotraceConverter potrace;

    public ImageVectorizer() {
        this(new PotraceConverter());
    }

    ImageVectorizer(PotraceConverter potrace) {
        this.potrace = potrace;
    }

    public void vectorize(Path input, Path output, ConversionSettings settings) throws IOException {
        checkInterrupted();
        ConversionSettings snapshot = settings.copy();
        potrace.requireExecutable();
        BufferedImage source = readImage(input);
        BufferedImage working = snapshot.getColorMode() == ConversionSettings.ColorMode.UNLIMITED
                ? source : resizeForDetail(source, snapshot.getDetailLevel());
        int width = working.getWidth();
        int height = working.getHeight();
        int[] pixels = working.getRGB(0, 0, width, height, null, 0, width);
        checkInterrupted();
        if (snapshot.isRemoveBackground()) removeBackground(pixels, width, height);
        if (snapshot.getColorMode() == ConversionSettings.ColorMode.CUSTOM) {
            quantize(pixels, snapshot.getCustomColorCount());
        }

        Path temporaryDirectory = Files.createTempDirectory("potrace-layers-");
        try {
            List<TracedLayer> layers = new ArrayList<>();
            for (ColorRegion region : traceableRegions(pixels, width)) {
                checkInterrupted();
                Path mask = temporaryDirectory.resolve("mask.pgm");
                Path tracedSvg = temporaryDirectory.resolve("layer.svg");
                writeMask(mask, pixels, width, region);
                potrace.trace(mask, tracedSvg, snapshot.getDetailLevel());
                TracedLayer layer = readLayer(tracedSvg, region);
                if (!layer.paths().isEmpty()) layers.add(layer);
            }
            writeMergedSvg(output, layers, pixels, source.getWidth(), source.getHeight(),
                    width, height, snapshot);
        } finally {
            deleteRecursively(temporaryDirectory);
        }
    }

    public int detectColorLimit(Path input) throws IOException {
        BufferedImage image = readImage(input);
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            checkInterrupted();
            for (int x = 0; x < image.getWidth(); x++) {
                if ((x & 4095) == 0) checkInterrupted();
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) == 0) continue;
                colors.add(pixel & 0x00ffffff);
                if (colors.size() >= 5) return 5;
            }
        }
        return Math.max(1, colors.size());
    }

    private BufferedImage readImage(Path input) throws IOException {
        checkInterrupted();
        BufferedImage image = ImageIO.read(input.toFile());
        if (image == null) throw new IOException(input.getFileName() + " is corrupted or unsupported.");
        checkInterrupted();
        return image;
    }
    private void quantize(int[] pixels, int maximum) throws IOException {
        List<Integer> palette = exactColorsUpTo(pixels, maximum);
        if (palette == null) palette = kMeansPalette(pixels, maximum);
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            if ((pixels[i] >>> 24) != 0) {
                pixels[i] = (pixels[i] & 0xff000000) | palette.get(nearestColor(pixels[i], palette));
            }
        }
    }

    private List<Integer> exactColorsUpTo(int[] pixels, int maximum) throws IOException {
        List<Integer> colors = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            int pixel = pixels[i];
            if ((pixel >>> 24) == 0) continue;
            int rgb = pixel & 0x00ffffff;
            if (seen.add(rgb)) {
                colors.add(rgb);
                if (colors.size() > maximum) return null;
            }
        }
        if (colors.isEmpty()) colors.add(0);
        return colors;
    }

    private List<Integer> kMeansPalette(int[] pixels, int colorCount) throws IOException {
        List<Integer> palette = initialPalette(pixels, colorCount);
        for (int iteration = 0; iteration < 10; iteration++) {
            long[] red = new long[palette.size()];
            long[] green = new long[palette.size()];
            long[] blue = new long[palette.size()];
            long[] weights = new long[palette.size()];
            for (int index = 0; index < pixels.length; index++) {
                if ((index & 4095) == 0) checkInterrupted();
                int pixel = pixels[index];
                int alpha = pixel >>> 24;
                if (alpha == 0) continue;
                int nearest = nearestColor(pixel, palette);
                red[nearest] += ((pixel >> 16) & 0xff) * alpha;
                green[nearest] += ((pixel >> 8) & 0xff) * alpha;
                blue[nearest] += (pixel & 0xff) * alpha;
                weights[nearest] += alpha;
            }
            boolean changed = false;
            for (int i = 0; i < palette.size(); i++) {
                if (weights[i] > 0) {
                    int color = ((int) (red[i] / weights[i]) << 16)
                            | ((int) (green[i] / weights[i]) << 8)
                            | (int) (blue[i] / weights[i]);
                    changed |= color != palette.get(i);
                    palette.set(i, color);
                }
            }
            if (!changed) break;
        }
        return palette;
    }

    private List<Integer> initialPalette(int[] pixels, int colorCount) throws IOException {
        Map<Integer, ColorBucket> histogram = new HashMap<>();
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            int pixel = pixels[i];
            if ((pixel >>> 24) == 0) continue;
            int key = (((pixel >> 16) & 0xff) / 8 << 10)
                    | (((pixel >> 8) & 0xff) / 8 << 5) | ((pixel & 0xff) / 8);
            histogram.computeIfAbsent(key, ignored -> new ColorBucket()).add(pixel);
        }
        List<ColorBucket> buckets = new ArrayList<>(histogram.values());
        buckets.sort(Comparator.comparingInt(ColorBucket::count).reversed()
                .thenComparingInt(ColorBucket::average));
        List<Integer> palette = new ArrayList<>();
        if (!buckets.isEmpty()) palette.add(buckets.getFirst().average());
        while (palette.size() < colorCount && palette.size() < buckets.size()) {
            checkInterrupted();
            int candidate = 0;
            double bestScore = -1;
            for (ColorBucket bucket : buckets) {
                int color = bucket.average();
                int distance = colorDistance(color, palette.get(nearestColor(color, palette)));
                double score = distance * Math.sqrt(bucket.count());
                if (score > bestScore) {
                    candidate = color;
                    bestScore = score;
                }
            }
            if (bestScore <= 0) break;
            palette.add(candidate);
        }
        for (int i = 0; i < pixels.length && palette.size() < colorCount; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            int rgb = pixels[i] & 0x00ffffff;
            if ((pixels[i] >>> 24) != 0 && !palette.contains(rgb)) palette.add(rgb);
        }
        if (palette.isEmpty()) palette.add(0);
        return palette;
    }
    private List<ColorRegion> traceableRegions(int[] pixels, int width) throws IOException {
        Map<Integer, Integer> candidates = new HashMap<>();
        int visible = 0;
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            int argb = pixels[i];
            if ((argb >>> 24) == 0) continue;
            visible++;
            Integer count = candidates.get(argb);
            if (count != null) {
                candidates.put(argb, count + 1);
            } else if (candidates.size() < MAX_TRACED_REGIONS) {
                candidates.put(argb, 1);
            } else {
                var iterator = candidates.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getValue() == 1) iterator.remove();
                    else entry.setValue(entry.getValue() - 1);
                }
            }
        }
        Map<Integer, ColorRegion> regions = new HashMap<>();
        for (int color : candidates.keySet()) regions.put(color, new ColorRegion(color));
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            ColorRegion region = regions.get(pixels[i]);
            if (region != null) region.include(i % width, i / width);
        }
        int minimum = Math.max(16, (int) Math.ceil(visible / (double) MAX_TRACED_REGIONS));
        return regions.values().stream()
                .filter(region -> region.count >= minimum && region.count >= region.area() * 0.04)
                .sorted(Comparator.comparingInt((ColorRegion region) -> region.count).reversed()
                        .thenComparingInt(region -> region.color))
                .toList();
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
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }
    private void removeBackground(int[] pixels, int width, int height) throws IOException {
        Map<Integer, ColorBucket> borderColors = new HashMap<>();
        for (int x = 0; x < width; x++) {
            if ((x & 4095) == 0) checkInterrupted();
            countBorderColor(borderColors, pixels[x]);
            if (height > 1) countBorderColor(borderColors, pixels[(height - 1) * width + x]);
        }
        for (int y = 1; y < height - 1; y++) {
            if ((y & 4095) == 0) checkInterrupted();
            countBorderColor(borderColors, pixels[y * width]);
            if (width > 1) countBorderColor(borderColors, pixels[y * width + width - 1]);
        }
        if (borderColors.isEmpty()) return;
        int background = borderColors.values().stream()
                .max(Comparator.comparingInt(ColorBucket::count).thenComparingInt(ColorBucket::average))
                .orElseThrow().average();
        boolean[] visited = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int end = 0;
        for (int x = 0; x < width; x++) {
            if ((x & 4095) == 0) checkInterrupted();
            end = enqueueBackground(x, background, pixels, visited, queue, end);
            end = enqueueBackground((height - 1) * width + x, background, pixels, visited, queue, end);
        }
        for (int y = 1; y < height - 1; y++) {
            if ((y & 4095) == 0) checkInterrupted();
            end = enqueueBackground(y * width, background, pixels, visited, queue, end);
            end = enqueueBackground(y * width + width - 1, background, pixels, visited, queue, end);
        }
        for (int start = 0; start < end; start++) {
            if ((start & 4095) == 0) checkInterrupted();
            int index = queue[start];
            pixels[index] = 0;
            int x = index % width;
            if (x > 0) end = enqueueBackground(index - 1, background, pixels, visited, queue, end);
            if (x < width - 1) end = enqueueBackground(index + 1, background, pixels, visited, queue, end);
            if (index >= width) end = enqueueBackground(index - width, background, pixels, visited, queue, end);
            if (index + width < pixels.length) {
                end = enqueueBackground(index + width, background, pixels, visited, queue, end);
            }
        }
    }

    private int enqueueBackground(int index, int background, int[] pixels, boolean[] visited,
                                  int[] queue, int end) {
        if (!visited[index]) {
            visited[index] = true;
            if ((pixels[index] >>> 24) == 0 || colorDistance(pixels[index], background) < 48 * 48) {
                queue[end++] = index;
            }
        }
        return end;
    }

    private void countBorderColor(Map<Integer, ColorBucket> counts, int argb) {
        if ((argb >>> 24) == 0) return;
        int bucket = ((argb >> 20) & 0xf) << 20
                | ((argb >> 12) & 0xf) << 12 | ((argb >> 4) & 0xf) << 4;
        counts.computeIfAbsent(bucket, ignored -> new ColorBucket()).add(argb);
    }

    private int colorDistance(int first, int second) {
        int red = ((first >> 16) & 0xff) - ((second >> 16) & 0xff);
        int green = ((first >> 8) & 0xff) - ((second >> 8) & 0xff);
        int blue = (first & 0xff) - (second & 0xff);
        return red * red + green * green + blue * blue;
    }

    private void writeMask(Path output, int[] pixels, int width, ColorRegion region) throws IOException {
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
            stream.write(("P5\n" + region.width() + " " + region.height() + "\n255\n")
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] row = new byte[region.width()];
            for (int y = region.minY; y <= region.maxY; y++) {
                checkInterrupted();
                for (int x = 0; x < row.length; x++) {
                    if ((x & 4095) == 0) checkInterrupted();
                    row[x] = (byte) (pixels[y * width + region.minX + x] == region.color ? 0 : 255);
                }
                stream.write(row);
            }
        }
    }

    private TracedLayer readLayer(Path svg, ColorRegion region) throws IOException {
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
            if (!"svg".equals(root.getLocalName())) throw new IOException("Invalid Potrace SVG root.");
            NodeList pathNodes = document.getElementsByTagNameNS("*", "path");
            List<TracedPath> paths = new ArrayList<>();
            for (int i = 0; i < pathNodes.getLength(); i++) {
                checkInterrupted();
                Element path = (Element) pathNodes.item(i);
                String data = path.getAttribute("d");
                if (!data.isBlank()) paths.add(new TracedPath(data, combinedTransform(path, root)));
            }
            return new TracedLayer(region.color, region.minX, region.minY, paths);
        } catch (IOException exception) {
            throw exception;
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

    private void writeMergedSvg(Path output, List<TracedLayer> layers, int[] pixels,
                                int originalWidth, int originalHeight, int tracedWidth, int tracedHeight,
                                ConversionSettings settings) throws IOException {
        Path absoluteOutput = output.toAbsolutePath();
        Files.createDirectories(absoluteOutput.getParent());
        Path temporaryOutput = Files.createTempFile(absoluteOutput.getParent(), ".vector-", ".svg.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporaryOutput, StandardCharsets.UTF_8)) {
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + originalWidth
                        + "\" height=\"" + originalHeight + "\" viewBox=\"0 0 " + tracedWidth + " "
                        + tracedHeight + "\" preserveAspectRatio=\"xMidYMid meet\">\n");
                writer.write("  <metadata>Generated with Potrace; detail=" + settings.getDetailLevel()
                        + "; colors=" + settings.getColorMode() + "</metadata>\n");
                Set<Integer> tracedColors = new HashSet<>();
                for (TracedLayer layer : layers) {
                    checkInterrupted();
                    tracedColors.add(layer.color());
                    writer.write("  <g" + fillAttributes(layer.color()) + " stroke=\"none\" transform=\"translate("
                            + layer.x() + " " + layer.y() + ")\">\n");
                    for (TracedPath path : layer.paths()) {
                        checkInterrupted();
                        writer.write("    <path");
                        if (!path.transform().isBlank()) {
                            writer.write(" transform=\"" + escape(path.transform()) + "\"");
                        }
                        writer.write(" d=\"" + escape(path.data()) + "\"/>\n");
                    }
                    writer.write("  </g>\n");
                }
                writeVectorRuns(writer, pixels, tracedWidth, tracedColors);
                writer.write("</svg>\n");
            }
            checkInterrupted();
            try {
                Files.move(temporaryOutput, absoluteOutput,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryOutput, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryOutput);
        }
    }
    private void writeVectorRuns(Writer writer, int[] pixels, int width, Set<Integer> tracedColors)
            throws IOException {
        Map<Integer, StringBuilder> buffer = new LinkedHashMap<>();
        int characters = 0;
        for (int y = 0; y < pixels.length / width; y++) {
            checkInterrupted();
            for (int x = 0; x < width;) {
                if ((x & 4095) == 0) checkInterrupted();
                int color = pixels[y * width + x];
                int end = x + 1;
                while (end < width && pixels[y * width + end] == color) {
                    if ((end & 4095) == 0) checkInterrupted();
                    end++;
                }
                if ((color >>> 24) != 0 && !tracedColors.contains(color)) {
                    StringBuilder data = buffer.computeIfAbsent(color, ignored -> new StringBuilder());
                    int before = data.length();
                    data.append('M').append(x).append(' ').append(y).append('h').append(end - x)
                            .append("v1h-").append(end - x).append('z');
                    characters += data.length() - before;
                    if (characters >= RUN_BUFFER_SIZE) {
                        flushRuns(writer, buffer);
                        characters = 0;
                    }
                }
                x = end;
            }
        }
        flushRuns(writer, buffer);
    }

    private void flushRuns(Writer writer, Map<Integer, StringBuilder> buffer) throws IOException {
        checkInterrupted();
        for (var entry : buffer.entrySet()) {
            writer.write("  <path" + fillAttributes(entry.getKey()) + " stroke=\"none\" d=\"");
            writer.write(entry.getValue().toString());
            writer.write("\"/>\n");
        }
        buffer.clear();
    }

    private String fillAttributes(int argb) {
        String attributes = " fill=\"" + String.format(Locale.ROOT, "#%06x", argb & 0x00ffffff) + "\"";
        int alpha = argb >>> 24;
        return alpha == 255 ? attributes
                : attributes + " fill-opacity=\"" + Double.toString(alpha / 255.0) + "\"";
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Vectorization was interrupted.");
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record TracedPath(String data, String transform) { }
    private record TracedLayer(int color, int x, int y, List<TracedPath> paths) { }

    private static final class ColorRegion {
        final int color;
        int count;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX;
        int maxY;

        ColorRegion(int color) { this.color = color; }

        void include(int x, int y) {
            count++;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
        long area() { return (long) width() * height(); }
    }

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

        int count() { return count; }

        int average() {
            return ((int) (red / count) << 16) | ((int) (green / count) << 8) | (int) (blue / count);
        }
    }
}
