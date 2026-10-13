package se233.project1.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class BatchConverterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void onlyMultipleMediumCustomImagesQualifyForParallelWork() {
        ConversionSettings settings = new ConversionSettings();
        for (var detail : ConversionSettings.DetailLevel.values()) {
            for (var mode : ConversionSettings.ColorMode.values()) {
                settings.setDetailLevel(detail);
                settings.setColorMode(mode);
                assertFalse(BatchConverter.shouldRunInParallel(0, settings));
                assertFalse(BatchConverter.shouldRunInParallel(1, settings));
                assertEquals(detail == ConversionSettings.DetailLevel.MEDIUM
                                && mode == ConversionSettings.ColorMode.CUSTOM,
                        BatchConverter.shouldRunInParallel(3, settings));
            }
        }
    }

    @Test
    void parallelWorkDetectsBeforeConvertingAndReportsOrderedMonotonicProgress() throws Exception {
        RecordingVectorizer vectorizer = new RecordingVectorizer();
        BatchConverter batch = new BatchConverter(vectorizer);
        ConversionSettings settings = new ConversionSettings();
        settings.setCustomColorCount(4);
        List<ImageProject> images = projects("slow.png", "mono.png", "third.png", "fourth.png", "fifth.png");
        List<Integer> progress = new ArrayList<>();
        Thread caller = Thread.currentThread();
        List<BatchConverter.Result> results = batch.convert(images, settings,
                temporaryDirectory.resolve("output"), event -> {
                    assertSame(caller, Thread.currentThread(), "Progress callbacks must be serialized on the caller.");
                    assertTrue(event.parallel());
                    assertEquals(5, event.total());
                    assertNull(event.result().failure());
                    progress.add(event.completed());
                });
        assertTrue(vectorizer.peak.get() >= 2, "Eligible files should overlap.");
        assertTrue(vectorizer.peak.get() <= 4, "The worker pool should be bounded.");
        assertEquals(List.of(1, 2, 3, 4, 5), progress);
        assertEquals(images, results.stream().map(BatchConverter.Result::project).toList());
        assertEquals(1, results.get(1).settings().getCustomColorCount());
        assertEquals(4, results.get(2).settings().getCustomColorCount());
        assertEquals(4, settings.getCustomColorCount(), "Per-image limits must not change the batch request.");
        assertEquals(0, vectorizer.active.get());
    }

    @Test
    void allOtherSettingsActuallyConvertSequentially() throws Exception {
        for (var detail : ConversionSettings.DetailLevel.values()) {
            for (var mode : ConversionSettings.ColorMode.values()) {
                if (detail == ConversionSettings.DetailLevel.MEDIUM && mode == ConversionSettings.ColorMode.CUSTOM) continue;
                RecordingVectorizer vectorizer = new RecordingVectorizer();
                ConversionSettings settings = new ConversionSettings();
                settings.setDetailLevel(detail);
                settings.setColorMode(mode);
                new BatchConverter(vectorizer).convert(projects("first.png", "second.png"), settings,
                        temporaryDirectory.resolve(detail + "-" + mode), event -> assertFalse(event.parallel()));
                assertEquals(1, vectorizer.peak.get());
            }
        }
    }

    @Test
    void namesNeverOverwriteExistingFilesOrCollideWithinTheBatch() throws Exception {
        Path output = temporaryDirectory.resolve("names");
        Files.createDirectories(output);
        Files.writeString(output.resolve("a.svg"), "existing a");
        Files.writeString(output.resolve("a-2.svg"), "existing a-2");
        List<BatchConverter.Result> results = new BatchConverter(new RecordingVectorizer()).convert(
                projects("a.png", "a.jpg", "a-2.png", "A.png", "../odd.png"),
                new ConversionSettings(), output, null);
        assertTrue(results.stream().allMatch(result -> result.failure() == null));
        assertEquals(results.size(), results.stream().map(result -> result.output().getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT)).distinct().count());
        assertTrue(results.stream().allMatch(result -> result.output().getParent().equals(output)));
        assertEquals("existing a", Files.readString(output.resolve("a.svg")));
        assertEquals("existing a-2", Files.readString(output.resolve("a-2.svg")));
        assertEquals(7, childNames(output).size());
    }

    @Test
    void aDestinationCreatedDuringConversionIsNotOverwritten() throws Exception {
        Path output = temporaryDirectory.resolve("race");
        ImageVectorizer vectorizer = new RecordingVectorizer() {
            @Override public void vectorize(Path input, Path staged, ConversionSettings settings) throws IOException {
                Files.writeString(output.resolve("same.svg"), "created by another writer");
                Files.writeString(staged, "<svg/>");
            }
        };
        var results = new BatchConverter(vectorizer).convert(projects("same.png"),
                new ConversionSettings(), output, null);
        assertInstanceOf(FileAlreadyExistsException.class, results.getFirst().failure());
        assertEquals("created by another writer", Files.readString(output.resolve("same.svg")));
        assertEquals(List.of("same.svg"), childNames(output));
    }

    @Test
    void reportsIndividualErrorsAndContinuesTheOtherImages() throws Exception {
        ImageVectorizer vectorizer = new RecordingVectorizer() {
            @Override public int detectColorLimit(Path input) throws IOException {
                if (input.getFileName().toString().equals("broken.png")) throw new IOException("corrupt input");
                return super.detectColorLimit(input);
            }
        };
        var results = new BatchConverter(vectorizer).convert(projects("good.png", "broken.png", "last.png"),
                new ConversionSettings(), temporaryDirectory.resolve("errors"), null);
        assertNull(results.getFirst().failure());
        assertEquals("corrupt input", results.get(1).failure().getMessage());
        assertFalse(Files.exists(results.get(1).output()));
        assertNull(results.getLast().failure());
        assertEquals(2, childNames(temporaryDirectory.resolve("errors")).size());
    }

    @Test
    void cancellationInterruptsAndJoinsAllWorkersBeforeReturning() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        ImageVectorizer vectorizer = new RecordingVectorizer() {
            @Override public void vectorize(Path input, Path output, ConversionSettings settings) throws IOException {
                active.incrementAndGet();
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("cancelled");
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Path output = temporaryDirectory.resolve("cancel");
        Thread caller = new Thread(() -> {
            try {
                new BatchConverter(vectorizer).convert(projects("a.png", "b.png", "c.png"),
                        new ConversionSettings(), output, null);
                outcome.set(new AssertionError("Should not complete after interruption."));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        caller.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            caller.interrupt();
            caller.join(5000);
        }
        assertFalse(caller.isAlive(), "Cancellation must return promptly.");
        assertInstanceOf(InterruptedException.class, outcome.get());
        assertEquals(0, active.get(), "Every child must release files before the caller returns.");
        assertTrue(childNames(output).isEmpty());
    }

    @Test
    void settingsAreSnapshottedBeforeTheFirstDetection() throws Exception {
        ConversionSettings requested = new ConversionSettings();
        ImageVectorizer vectorizer = new RecordingVectorizer() {
            @Override public int detectColorLimit(Path input) throws IOException {
                requested.setCustomColorCount(5);
                requested.setRemoveBackground(true);
                return super.detectColorLimit(input);
            }
        };
        var result = new BatchConverter(vectorizer).convert(projects("image.png"), requested,
                temporaryDirectory.resolve("snapshot"), null).getFirst();
        assertEquals(2, result.settings().getCustomColorCount());
        assertFalse(result.settings().isRemoveBackground());
    }

    @Test
    void exportsRealPngAndJpegAsValidSvgWithACsvReportAndCleansStaging() throws Exception {
        Path png = raster("first.png", "png", false);
        Path jpg = raster("second.jpg", "jpg", true);
        Path output = temporaryDirectory.resolve("bundle.zip");
        var results = new BatchConverter(new ImageVectorizer()).exportZip(List.of(
                        new ImageProject(png, "first,\"quoted\".png"), new ImageProject(jpg, "second.jpg")),
                new ConversionSettings(), output, null);
        assertTrue(results.stream().allMatch(result -> result.failure() == null));
        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(3, zip.size());
            for (BatchConverter.Result result : results) {
                var entry = zip.getEntry(result.output().getFileName().toString());
                assertNotNull(entry);
                try (var stream = zip.getInputStream(entry)) {
                    assertEquals("svg", DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                            .parse(stream).getDocumentElement().getNodeName());
                }
            }
            String report = new String(zip.getInputStream(zip.getEntry("manifest.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(report.startsWith("source,svg,detail,color_mode,custom_colors,detected_color_limit,remove_background"));
            assertTrue(report.contains("\"first,\"\"quoted\"\".png\""));
            assertTrue(report.contains("MEDIUM,CUSTOM,1,1,false,"));
            assertTrue(report.contains("remove_background,output_bytes"));
        }
        assertTrue(results.stream().noneMatch(result -> Files.exists(result.output())), "ZIP staging should be removed.");
        assertTrue(childNames(temporaryDirectory).stream().noneMatch(name -> name.startsWith(".vector-export-")));
    }

    @Test
    void failedZipConversionPublishesNoArchiveAndCleansStaging() throws Exception {
        ImageVectorizer vectorizer = new RecordingVectorizer() {
            @Override public int detectColorLimit(Path input) throws IOException { throw new IOException("broken"); }
        };
        Path output = temporaryDirectory.resolve("failed.zip");
        var results = new BatchConverter(vectorizer).exportZip(projects("broken.png"),
                new ConversionSettings(), output, null);
        assertNotNull(results.getFirst().failure());
        assertFalse(Files.exists(output));
        assertTrue(childNames(temporaryDirectory).isEmpty());
    }

    @Test
    void zipWillNotReplaceAnExistingOrConcurrentlyCreatedDestination() throws Exception {
        Path existing = temporaryDirectory.resolve("existing.zip");
        Files.writeString(existing, "keep this");
        BatchConverter batch = new BatchConverter(new RecordingVectorizer());
        assertThrows(FileAlreadyExistsException.class, () -> batch.exportZip(projects("a.png"),
                new ConversionSettings(), existing, null));
        assertEquals("keep this", Files.readString(existing));

        Path raced = temporaryDirectory.resolve("raced.zip");
        assertThrows(FileAlreadyExistsException.class, () -> batch.exportZip(projects("a.png"),
                new ConversionSettings(), raced, event -> {
                    try { Files.writeString(raced, "other archive"); }
                    catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                }));
        assertEquals("other archive", Files.readString(raced));
        assertEquals(List.of("existing.zip", "raced.zip"), childNames(temporaryDirectory));
    }

    private List<ImageProject> projects(String... names) {
        List<ImageProject> result = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            Path source = temporaryDirectory.resolve(names[index].replace("../", ""));
            result.add(new ImageProject(source, names[index]));
        }
        return result;
    }

    private List<String> childNames(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private Path raster(String name, String format, boolean twoColors) throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            image.setRGB(x, y, twoColors && x >= 16 ? 0x003366ff : 0x00ff3333);
        }
        Path path = temporaryDirectory.resolve(name);
        assertTrue(ImageIO.write(image, format, path.toFile()));
        return path;
    }

    private static class RecordingVectorizer extends ImageVectorizer {
        final Set<Path> detected = ConcurrentHashMap.newKeySet();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        @Override public int detectColorLimit(Path input) throws IOException {
            detected.add(input);
            return input.getFileName().toString().contains("mono") ? 1 : 5;
        }

        @Override public void vectorize(Path input, Path output, ConversionSettings settings) throws IOException {
            assertTrue(detected.contains(input), "Every input must be detected before conversion.");
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(input.getFileName().toString().startsWith("slow") ? 100 : 30);
                Files.writeString(output, "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0L1 1\"/></svg>");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("cancelled");
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
