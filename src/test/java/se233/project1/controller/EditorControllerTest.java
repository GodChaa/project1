package se233.project1.controller;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se233.project1.FxTestSupport;
import se233.project1.model.ConversionSettings;
import se233.project1.model.ImageVectorizer;
import se233.project1.model.InputImporter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static se233.project1.FxTestSupport.onFx;

class EditorControllerTest {
    @TempDir Path temporaryDirectory;

    @BeforeAll static void startFx() throws Exception { FxTestSupport.start(); }

    @Test void defaultsNavigationAndPerImageLimitsSurviveAnotherImport() throws Exception {
        Path mono = image("mono.png");
        Path colorful = image("colorful.png");
        ConversionSettings settings = new ConversionSettings();
        FakeVectorizer vectorizer = new FakeVectorizer();
        RecordingView view = new RecordingView();
        EditorController controller = onFx(() -> {
            EditorController result = new EditorController(settings, vectorizer, new InputImporter());
            result.attachView(view);
            result.loadInputs(List.of(mono.toFile(), colorful.toFile()));
            return result;
        });
        try {
            view.idle.get(10, TimeUnit.SECONDS);
            onFx(() -> {
                assertEquals(2, settings.getCustomColorCount());
                assertEquals(1, view.limit);
                assertEquals(1, view.vectorSettings.getCustomColorCount());
                assertTrue(view.canNext);
                controller.next();
                assertEquals("colorful.png", view.name);
                assertEquals(5, view.limit);
                assertEquals(2, view.vectorSettings.getCustomColorCount());
                assertTrue(view.canBack);
                assertFalse(view.canNext);
                controller.previous();
                assertEquals("mono.png", view.name);
                assertTrue(view.statuses.stream().anyMatch(s -> s.contains("in parallel")));
                assertEquals(0, view.errors.size());
                return null;
            });
            var export = onFx(() -> controller.exportToDirectory(temporaryDirectory.resolve("export")));
            var results = export.get(10, TimeUnit.SECONDS);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(r -> r.failure() == null && Files.isRegularFile(r.output())));
            onFx(() -> {
                settings.setDetailLevel(ConversionSettings.DetailLevel.HIGH);
                settings.setColorMode(ConversionSettings.ColorMode.UNLIMITED);
                settings.setRemoveBackground(true);
                settings.setCustomColorCount(5);
                view.idle = new CompletableFuture<>();
                controller.loadInputs(List.of(colorful.toFile()));
                assertEquals(new ConversionSettings().signature(), settings.signature());
                return null;
            });
            view.idle.get(10, TimeUnit.SECONDS);
            onFx(() -> {
                assertEquals(2, view.vectorSettings.getCustomColorCount());
                assertFalse(view.canNext);
                assertEquals(0, view.errors.size());
                return null;
            });
            for (Path source : List.of(mono, colorful)) {
                assertTrue(vectorizer.events.indexOf("detect:" + source) < vectorizer.events.indexOf("trace:" + source));
            }
        } finally { onFx(() -> { controller.close(); return null; }); }
    }

    @Test void settingChangesCancelOldPreviewAndClosingCancelsExportFuture() throws Exception {
        Path source = image("photo.png");
        ConversionSettings settings = new ConversionSettings();
        FakeVectorizer vectorizer = new FakeVectorizer();
        RecordingView view = new RecordingView();
        EditorController controller = onFx(() -> {
            EditorController result = new EditorController(settings, vectorizer, new InputImporter());
            result.attachView(view);
            result.loadInputs(List.of(source.toFile()));
            return result;
        });
        try {
            view.idle.get(10, TimeUnit.SECONDS);
            onFx(() -> {
                view.idle = new CompletableFuture<>();
                vectorizer.blockHigh = true;
                settings.setDetailLevel(ConversionSettings.DetailLevel.HIGH);
                controller.settingsChanged();
                return null;
            });
            assertTrue(vectorizer.highStarted.await(10, TimeUnit.SECONDS));
            onFx(() -> {
                settings.setDetailLevel(ConversionSettings.DetailLevel.LOW);
                controller.settingsChanged();
                return null;
            });
            view.idle.get(10, TimeUnit.SECONDS);
            assertTrue(vectorizer.interrupted.await(10, TimeUnit.SECONDS));
            onFx(() -> {
                assertEquals(ConversionSettings.DetailLevel.LOW, view.vectorSettings.getDetailLevel());
                assertEquals(0, view.errors.size());
                assertFalse(view.rendered.contains(ConversionSettings.DetailLevel.HIGH));
                var export = controller.exportToDirectory(temporaryDirectory.resolve("cancelled"));
                controller.close();
                assertTrue(export.isCancelled());
                return null;
            });
        } finally { onFx(() -> { controller.close(); return null; }); }
    }

    @Test void corruptImportReportsErrorAndCanBeRetried() throws Exception {
        Path bad = temporaryDirectory.resolve("broken.png");
        Files.writeString(bad, "not an image");
        Path good = image("good.png");
        RecordingView view = new RecordingView();
        EditorController controller = onFx(() -> {
            EditorController result = new EditorController(new ConversionSettings(), new FakeVectorizer(), new InputImporter());
            result.attachView(view);
            result.loadInputs(List.of(bad.toFile()));
            return result;
        });
        try {
            view.idle.get(10, TimeUnit.SECONDS);
            onFx(() -> {
                assertTrue(view.errors.stream().anyMatch(s -> s.contains("broken.png")));
                view.idle = new CompletableFuture<>();
                controller.loadInputs(List.of(good.toFile()));
                return null;
            });
            view.idle.get(10, TimeUnit.SECONDS);
            onFx(() -> { assertEquals("good.png", view.name); assertNotNull(view.vectorSettings); return null; });
        } finally { onFx(() -> { controller.close(); return null; }); }
    }

    @Test void failedInitialConversionEndsProgressAndShowsFailurePlaceholder() throws Exception {
        Path source = image("sample.png");
        RecordingView view = new RecordingView();
        ImageVectorizer failing = new FakeVectorizer() {
            @Override public void vectorize(Path input, Path output, ConversionSettings settings) throws IOException {
                throw new IOException("Potrace was not found. Install Potrace or set POTRACE_PATH.");
            }
        };
        EditorController controller = onFx(() -> {
            EditorController result = new EditorController(new ConversionSettings(), failing, new InputImporter());
            result.attachView(view);
            result.loadInputs(List.of(source.toFile()));
            return result;
        });
        try {
            view.idle.get(10, TimeUnit.SECONDS);
            onFx(() -> {
                assertEquals("Preview failed", view.placeholder);
                assertTrue(view.errors.stream().anyMatch(s -> s.contains("Potrace") && s.contains("sample.png")));
                assertNull(view.vectorSettings);
                return null;
            });
        } finally { onFx(() -> { controller.close(); return null; }); }
    }

    private Path image(String filename) throws IOException {
        Path path = temporaryDirectory.resolve(filename);
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path;
    }

    private static class FakeVectorizer extends ImageVectorizer {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch highStarted = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        volatile boolean blockHigh;

        @Override public int detectColorLimit(Path input) {
            assertFalse(Platform.isFxApplicationThread());
            events.add("detect:" + input);
            return input.getFileName().toString().startsWith("mono") ? 1 : 5;
        }

        @Override public void vectorize(Path input, Path output, ConversionSettings settings) throws IOException {
            assertFalse(Platform.isFxApplicationThread());
            events.add("trace:" + input);
            if (blockHigh && settings.getDetailLevel() == ConversionSettings.DetailLevel.HIGH) {
                highStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Cancelled");
                }
            }
            Files.writeString(output, "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 12 8\"/>");
        }
    }

    private static class RecordingView implements EditorController.View {
        volatile CompletableFuture<Void> idle = new CompletableFuture<>();
        String name;
        String placeholder;
        int limit;
        boolean canNext;
        boolean canBack;
        ConversionSettings vectorSettings;
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<ConversionSettings.DetailLevel> rendered = new CopyOnWriteArrayList<>();

        private void fx() { assertTrue(Platform.isFxApplicationThread()); }
        public void showOriginal(Path path, String name, int position, int total) { fx(); this.name = name; }
        public void showVector(Path path, ConversionSettings settings) { fx(); vectorSettings = settings.copy(); rendered.add(settings.getDetailLevel()); }
        public void clearVector(String message) { fx(); placeholder = message; }
        public void updateNavigation(boolean back, boolean next) { fx(); canBack = back; canNext = next; }
        public void updateColorLimit(int maximum) { fx(); limit = maximum; }
        public void showSettings(ConversionSettings settings) { fx(); }
        public void setEditingEnabled(boolean enabled) { fx(); }
        public void setWorking(boolean working, double progress, String message) {
            fx(); statuses.add(message);
            if (!working) idle.complete(null);
        }
        public void showError(String title, String message) { fx(); errors.add(message); }
        public void showInfo(String title, String message) { fx(); }
        public void applyZoom(double zoom) { fx(); }
    }
}
