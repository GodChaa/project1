package se233.project1.controller;

import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import se233.project1.model.BatchConverter;
import se233.project1.model.ConversionSettings;
import se233.project1.model.ImageProject;
import se233.project1.model.ImageVectorizer;
import se233.project1.model.InputImporter;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Owns one cancellable operation at a time; all UI changes happen on the FX thread. */
public class EditorController implements AutoCloseable {
    private static final double ZOOM_STEP = 0.15;

    public interface View {
        void showOriginal(Path path, String name, int position, int total);
        void showVector(Path svgPath, ConversionSettings settings);
        void clearVector(String message);
        void updateNavigation(boolean canGoBack, boolean canGoNext);
        void updateColorLimit(int maximum);
        void showSettings(ConversionSettings settings);
        void setEditingEnabled(boolean enabled);
        void setWorking(boolean working, double progress, String message);
        void showError(String title, String message);
        void showInfo(String title, String message);
        void applyZoom(double zoom);
    }

    private enum Operation { IDLE, IMPORT, PREPROCESS, PREVIEW, EXPORT }

    private final ConversionSettings settings;
    private final InputImporter importer;
    private final BatchConverter batchConverter;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vectorizer-coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final List<Path> temporaryPreviews = new ArrayList<>();
    private List<ImageProject> projects = List.of();
    private int currentIndex;
    private double zoom = 1.0;
    private volatile boolean closed;
    private View view;
    private Path previewDirectory;
    private Future<?> activeTask;
    private Operation operation = Operation.IDLE;

    public EditorController(ConversionSettings settings) {
        this(settings, new ImageVectorizer(), new InputImporter());
    }

    EditorController(ConversionSettings settings, ImageVectorizer vectorizer, InputImporter importer) {
        this.settings = settings;
        this.importer = importer;
        this.batchConverter = new BatchConverter(vectorizer);
    }

    public void attachView(View view) {
        this.view = view;
    }

    public void loadInputs(List<File> files) {
        if (closed || view == null) return;
        List<File> inputs = List.copyOf(files);
        settings.reset();
        projects = List.of();
        currentIndex = 0;
        view.showSettings(settings.copy());
        view.updateColorLimit(5);
        view.updateNavigation(false, false);
        long request = begin(Operation.IMPORT, "Importing images...");
        view.setEditingEnabled(false);
        ConversionSettings defaults = settings.copy();
        activeTask = coordinator.submit(() -> {
            try {
                List<ImageProject> imported = importer.importFiles(inputs);
                checkInterrupted();
                Path directory = Files.createTempDirectory("vectorizer-preview-");
                temporaryPreviews.add(directory);
                post(request, () -> {
                    projects = imported;
                    currentIndex = 0;
                    previewDirectory = directory;
                    operation = Operation.PREPROCESS;
                    view.setEditingEnabled(true);
                    selectCurrentProject();
                    view.setWorking(true, 0, progressText("Preprocessing", 0, imported.size(),
                            BatchConverter.shouldRunInParallel(imported.size(), defaults)));
                });
                List<BatchConverter.Result> results = batchConverter.convert(imported, defaults, directory,
                        progress -> post(request, () -> {
                            cacheResult(progress.result());
                            view.setWorking(true, progress.completed() / (double) progress.total(),
                                    progressText("Preprocessing", progress.completed(), progress.total(), progress.parallel()));
                        }));
                post(request, () -> {
                    finish("Previews ready");
                    reportFailures("Some images could not be preprocessed", results);
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                post(request, () -> {
                    finish("Import failed");
                    view.clearVector("Could not prepare images");
                    view.showError("Could not prepare images", rootMessage(exception));
                });
            }
        });
    }

    public void previous() {
        if (canNavigate() && currentIndex > 0) {
            currentIndex--;
            selectCurrentProject();
        }
    }

    public void next() {
        if (canNavigate() && currentIndex < projects.size() - 1) {
            currentIndex++;
            selectCurrentProject();
        }
    }

    public void settingsChanged() {
        if (!canNavigate() || projects.isEmpty()) return;
        generateCurrentPreview();
    }

    public void zoomIn() {
        zoom = Math.min(3.0, zoom + ZOOM_STEP);
        if (view != null) view.applyZoom(zoom);
    }

    public void zoomOut() {
        zoom = Math.max(0.25, zoom - ZOOM_STEP);
        if (view != null) view.applyZoom(zoom);
    }

    public void zoomToFit() {
        zoom = 1.0;
        if (view != null) view.applyZoom(zoom);
    }

    public void exportAll(Window ownerWindow) {
        if (!hasImagesToExport()) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select output folder for SVG files");
        File directory = chooser.showDialog(ownerWindow);
        if (directory != null) {
            exportToDirectory(directory.toPath()).thenAccept(results -> {
                if (results.stream().allMatch(result -> result.failure() == null)) openFolder(directory.toPath());
            });
        }
    }

    public void exportZip(Window ownerWindow) {
        if (!hasImagesToExport()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export SVG bundle with conversion report");
        chooser.setInitialFileName("vectorized-images.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG bundle (*.zip)", "*.zip"));
        File archive = chooser.showSaveDialog(ownerWindow);
        if (archive != null) {
            Path destination = archive.toPath();
            if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                destination = destination.resolveSibling(destination.getFileName() + ".zip");
            }
            Path selected = destination;
            exportToZip(selected).thenAccept(results -> {
                if (results.stream().allMatch(result -> result.failure() == null)) openFolder(selected.toAbsolutePath().getParent());
            });
        }
    }

    /** Also used by integration tests to exercise exactly the same export as the UI. */
    public CompletableFuture<List<BatchConverter.Result>> exportToDirectory(Path directory) {
        return startExport(directory, false);
    }

    public CompletableFuture<List<BatchConverter.Result>> exportToZip(Path archive) {
        return startExport(archive, true);
    }

    private CompletableFuture<List<BatchConverter.Result>> startExport(Path destination, boolean zip) {
        if (closed || projects.isEmpty() || operation == Operation.EXPORT || operation == Operation.IMPORT) {
            return CompletableFuture.failedFuture(new IllegalStateException("No images are ready for export."));
        }
        List<ImageProject> selection = List.copyOf(projects);
        ConversionSettings snapshot = settings.copy();
        long request = begin(Operation.EXPORT, zip ? "Preparing SVG bundle..." : "Exporting SVG files...");
        view.setEditingEnabled(false);
        CompletableFuture<List<BatchConverter.Result>> completed = new CompletableFuture<>();
        activeTask = coordinator.submit(() -> {
            try {
                java.util.function.Consumer<BatchConverter.Progress> progress = value -> post(request, () ->
                        view.setWorking(true, value.completed() / (double) value.total(),
                                progressText(zip ? "Preparing bundle" : "Exporting", value.completed(),
                                        value.total(), value.parallel())));
                List<BatchConverter.Result> results = zip
                        ? batchConverter.exportZip(selection, snapshot, destination, progress)
                        : batchConverter.convert(selection, snapshot, destination, progress);
                post(request, () -> {
                    finish("Export complete");
                    if (!reportFailures("Export incomplete", results)) {
                        view.showInfo("Export complete", zip
                                ? "SVG bundle and conversion report saved to:\n" + destination.toAbsolutePath()
                                : results.size() + " SVG file(s) saved to:\n" + destination.toAbsolutePath());
                    }
                    completed.complete(results);
                });
                if (closed || request != generation.get()) completed.cancel(false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completed.cancel(false);
            } catch (Exception exception) {
                post(request, () -> {
                    finish("Export failed");
                    view.showError("Export failed", rootMessage(exception));
                    completed.completeExceptionally(exception);
                });
                if (closed) completed.completeExceptionally(exception);
            }
        });
        return completed;
    }

    private boolean hasImagesToExport() {
        if (projects.isEmpty()) {
            view.showError("Nothing to export", "Import at least one image first.");
            return false;
        }
        return !closed && operation != Operation.EXPORT && operation != Operation.IMPORT;
    }

    private void selectCurrentProject() {
        if (projects.isEmpty()) return;
        ImageProject project = projects.get(currentIndex);
        zoomToFit();
        view.showOriginal(project.getSourcePath(), project.getDisplayName(), currentIndex + 1, projects.size());
        view.updateNavigation(currentIndex > 0, currentIndex < projects.size() - 1);
        view.showSettings(settings.copy());
        view.updateColorLimit(project.getColorLimit());
        ConversionSettings effective = effectiveSettings(project);
        Path cached = project.getPreview(effective.signature());
        if (cached != null) {
            view.showVector(cached, effective);
            if (operation == Operation.PREVIEW) {
                invalidateActiveTask();
                finish("Preview ready");
            }
        } else {
            view.clearVector("Generating preview...");
            if (operation != Operation.PREPROCESS) generateCurrentPreview();
        }
    }

    private void generateCurrentPreview() {
        ImageProject project = projects.get(currentIndex);
        ConversionSettings snapshot = settings.copy();
        ConversionSettings effective = effectiveSettings(project);
        Path cached = project.getPreview(effective.signature());
        long request = begin(Operation.PREVIEW, "Updating preview...");
        if (cached != null) {
            view.showVector(cached, effective);
            finish("Preview ready");
            return;
        }
        view.clearVector("Updating preview...");
        Path directory = previewDirectory;
        activeTask = coordinator.submit(() -> {
            try {
                List<BatchConverter.Result> results = batchConverter.convert(List.of(project), snapshot, directory,
                        ignored -> { });
                post(request, () -> {
                    results.forEach(this::cacheResult);
                    finish("Preview ready");
                    if (reportFailures("Could not update preview", results)) view.clearVector("Preview failed");
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                post(request, () -> {
                    finish("Preview failed");
                    view.clearVector("Preview failed");
                    view.showError("Could not update preview", rootMessage(exception));
                });
            }
        });
    }

    private ConversionSettings effectiveSettings(ImageProject project) {
        ConversionSettings result = settings.copy();
        result.setCustomColorCount(Math.min(result.getCustomColorCount(), project.getColorLimit()));
        return result;
    }

    private void cacheResult(BatchConverter.Result result) {
        result.project().setColorLimit(result.colorLimit());
        if (result.failure() == null) {
            result.project().setPreview(result.output(), result.settings().signature());
        }
        if (!projects.isEmpty() && projects.get(currentIndex) == result.project()) {
            view.updateColorLimit(result.colorLimit());
            if (result.failure() == null && effectiveSettings(result.project()).signature().equals(result.settings().signature())) {
                view.showVector(result.output(), result.settings());
            }
        }
    }

    private long begin(Operation next, String message) {
        invalidateActiveTask();
        operation = next;
        view.setWorking(true, -1, message);
        return generation.get();
    }

    private void invalidateActiveTask() {
        generation.incrementAndGet();
        if (activeTask != null) activeTask.cancel(true);
        activeTask = null;
    }

    private void finish(String message) {
        operation = Operation.IDLE;
        activeTask = null;
        view.setEditingEnabled(true);
        view.setWorking(false, 1, message);
    }

    private boolean reportFailures(String title, List<BatchConverter.Result> results) {
        List<BatchConverter.Result> failed = results.stream().filter(result -> result.failure() != null).toList();
        if (failed.isEmpty()) return false;
        String details = failed.stream().limit(5)
                .map(result -> result.project().getDisplayName() + ": " + rootMessage(result.failure()))
                .collect(java.util.stream.Collectors.joining("\n"));
        view.showError(title, failed.size() + " file(s) failed.\n" + details);
        return true;
    }

    private boolean canNavigate() {
        return !closed && operation != Operation.IMPORT && operation != Operation.EXPORT;
    }

    private String progressText(String verb, int done, int total, boolean parallel) {
        return verb + (parallel ? " in parallel: " : ": ") + done + " / " + total;
    }

    private void post(long request, Runnable action) {
        if (closed) return;
        Platform.runLater(() -> {
            if (!closed && generation.get() == request) action.run();
        });
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Operation cancelled");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void openFolder(Path directory) {
        CompletableFuture.runAsync(() -> {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(directory.toFile());
            } catch (IOException | UnsupportedOperationException ignored) {
                // The completion dialog includes the exact path if Finder cannot be opened.
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        invalidateActiveTask();
        // Cleanup runs only after cancelled workers have released their source/output files.
        coordinator.submit(() -> {
            importer.close();
            for (Path directory : temporaryPreviews) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        });
        coordinator.shutdown();
    }
}
