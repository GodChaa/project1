package se233.project1.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
public final class BatchConverter {
    private final ImageVectorizer vectorizer;

    public BatchConverter(ImageVectorizer vectorizer) {
        this.vectorizer = java.util.Objects.requireNonNull(vectorizer);
    }

    public record Result(ImageProject project, Path output, ConversionSettings settings,
                         int colorLimit, Exception failure) { }

    public record Progress(int completed, int total, boolean parallel, Result result) { }

    public static boolean shouldRunInParallel(int count, ConversionSettings settings) {
        return count > 1 && settings.getDetailLevel() == ConversionSettings.DetailLevel.MEDIUM
                && settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM;
    }
    public List<Result> convert(List<ImageProject> projects, ConversionSettings settings,
                                Path outputDir, Consumer<Progress> progress)
            throws IOException, InterruptedException {
        ConversionSettings snapshot = settings.copy();
        return convert(projects, ignored -> snapshot, outputDir, progress);
    }

    public List<Result> convert(List<ImageProject> projects,
                                Function<ImageProject, ConversionSettings> settingsForProject,
                                Path outputDir, Consumer<Progress> progress)
            throws IOException, InterruptedException {
        checkInterrupted();
        List<ImageProject> images = List.copyOf(projects);
        List<ConversionSettings> snapshots = images.stream()
                .map(settingsForProject)
                .map(ConversionSettings::copy)
                .toList();
        Path directory = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        List<Path> destinations = chooseDestinations(images, directory);
        boolean parallel = images.size() > 1 && snapshots.stream().allMatch(settings ->
                settings.getDetailLevel() == ConversionSettings.DetailLevel.MEDIUM
                        && settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM);
        Consumer<Progress> listener = progress == null ? ignored -> { } : progress;
        if (!parallel) {
            List<Result> results = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                checkInterrupted();
                Result result = convertOne(images.get(index), snapshots.get(index), destinations.get(index));
                results.add(result);
                listener.accept(new Progress(results.size(), images.size(), false, result));
            }
            checkInterrupted();
            return List.copyOf(results);
        }

        int workers = Math.min(images.size(), Math.max(2,
                Math.min(4, Runtime.getRuntime().availableProcessors())));
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "vector-batch-worker");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<IndexedResult>> futures = new ArrayList<>();
        Result[] ordered = new Result[images.size()];
        try {
            ExecutorCompletionService<IndexedResult> completed = new ExecutorCompletionService<>(executor);
            for (int index = 0; index < images.size(); index++) {
                final int position = index;
                futures.add(completed.submit(() -> new IndexedResult(position,
                        convertOne(images.get(position), snapshots.get(position), destinations.get(position)))));
            }
            for (int count = 1; count <= images.size(); count++) {
                checkInterrupted();
                IndexedResult next;
                try {
                    next = completed.take().get();
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof InterruptedException interrupted) throw interrupted;
                    if (exception.getCause() instanceof Error error) throw error;
                    throw new IOException("A conversion worker failed.", exception.getCause());
                }
                ordered[next.index()] = next.result();
                listener.accept(new Progress(count, images.size(), true, next.result()));
            }
            checkInterrupted();
            return List.of(ordered);
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            boolean interrupted = false;
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    public List<Result> exportZip(List<ImageProject> projects, ConversionSettings settings,
                                  Path outputZip, Consumer<Progress> progress)
            throws IOException, InterruptedException {
        ConversionSettings snapshot = settings.copy();
        return exportZip(projects, ignored -> snapshot, outputZip, progress);
    }

    public List<Result> exportZip(List<ImageProject> projects,
                                  Function<ImageProject, ConversionSettings> settingsForProject,
                                  Path outputZip, Consumer<Progress> progress)
            throws IOException, InterruptedException {
        checkInterrupted();
        Path destination = outputZip.toAbsolutePath().normalize();
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".vector-export-");
        try {
            List<Result> results = convert(projects, settingsForProject, staging.resolve("svg"), progress);
            if (results.stream().anyMatch(result -> result.failure() != null)) return results;
            checkInterrupted();
            Path archive = staging.resolve("bundle.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
                byte[] buffer = new byte[64 * 1024];
                for (Result result : results) {
                    checkInterrupted();
                    zip.putNextEntry(new ZipEntry(result.output().getFileName().toString()));
                    try (InputStream input = Files.newInputStream(result.output())) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkInterrupted();
                            zip.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("manifest.csv"));
                zip.write(manifest(results).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            checkInterrupted();
            publishNoReplace(archive, destination);
            return results;
        } finally {
            deleteTree(staging);
        }
    }

    private Result convertOne(ImageProject image, ConversionSettings requested, Path output)
            throws InterruptedException {
        ConversionSettings effective = requested.copy();
        int detected = 5;
        Path temporary = null;
        try {
            checkInterrupted();
            detected = Math.max(1, Math.min(5, vectorizer.detectColorLimit(image.getSourcePath())));
            checkInterrupted();
            if (effective.getColorMode() == ConversionSettings.ColorMode.CUSTOM) {
                effective.setCustomColorCount(Math.min(effective.getCustomColorCount(), detected));
            }
            temporary = Files.createTempFile(output.getParent(), ".vector-", ".svg");
            vectorizer.vectorize(image.getSourcePath(), temporary, effective);
            checkInterrupted();
            if (Files.size(temporary) == 0) throw new IOException("The converter produced an empty SVG.");
            publishNoReplace(temporary, output);
            return new Result(image, output, effective, detected, null);
        } catch (IOException | RuntimeException exception) {
            if (Thread.currentThread().isInterrupted() || exception instanceof InterruptedIOException) {
                InterruptedException interrupted = new InterruptedException("Conversion cancelled.");
                interrupted.initCause(exception);
                throw interrupted;
            }
            return new Result(image, output, effective, detected, exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private List<Path> chooseDestinations(List<ImageProject> images, Path directory) throws IOException {
        Set<String> used = new HashSet<>();
        try (var existing = Files.list(directory)) {
            existing.forEach(path -> used.add(path.getFileName().toString().toLowerCase(Locale.ROOT)));
        }
        List<Path> outputs = new ArrayList<>();
        for (ImageProject image : images) {
            String name = image.getDisplayName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            if (name.isBlank() || name.equals(".") || name.equals("..")) name = "image";
            if (name.length() > 120) name = name.substring(0, 120);
            String candidate = name + ".svg";
            int suffix = 2;
            while (!used.add(candidate.toLowerCase(Locale.ROOT))) candidate = name + "-" + suffix++ + ".svg";
            outputs.add(directory.resolve(candidate));
        }
        return outputs;
    }

    private static void publishNoReplace(Path staged, Path destination) throws IOException {
        try {
            Files.createLink(destination, staged);
        } catch (FileAlreadyExistsException exception) {
            throw exception;
        } catch (UnsupportedOperationException | IOException exception) {
            Files.move(staged, destination);
            return;
        }
        Files.delete(staged);
    }

    private static String manifest(List<Result> results) throws IOException {
        StringBuilder csv = new StringBuilder("source,svg,detail,color_mode,custom_colors,detected_color_limit,remove_background,output_bytes\r\n");
        for (Result result : results) {
            ConversionSettings settings = result.settings();
            csv.append(csvCell(result.project().getDisplayName())).append(',')
                    .append(csvCell(result.output().getFileName().toString())).append(',')
                    .append(settings.getDetailLevel()).append(',').append(settings.getColorMode()).append(',')
                    .append(settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM
                            ? settings.getCustomColorCount() : "").append(',')
                    .append(result.colorLimit()).append(',').append(settings.isRemoveBackground()).append(',')
                    .append(Files.size(result.output())).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvCell(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Conversion cancelled.");
    }

    private static void deleteTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record IndexedResult(int index, Result result) { }
}
