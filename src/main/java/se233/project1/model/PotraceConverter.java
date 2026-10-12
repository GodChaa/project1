package se233.project1.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Safe adapter around the native Potrace command-line program. */
public class PotraceConverter {
    private static final int MAX_DIAGNOSTIC_BYTES = 8_192;
    private final List<String> configuredCandidates;
    private final Duration traceTimeout;
    private final Duration probeTimeout;
    private volatile String executable;

    public PotraceConverter() {
        this(null, Duration.ofMinutes(2), Duration.ofSeconds(5));
    }

    /** Allows deterministic process tests without changing global properties or the installed binary. */
    PotraceConverter(List<String> candidates, Duration traceTimeout, Duration probeTimeout) {
        if (traceTimeout.isZero() || traceTimeout.isNegative()
                || probeTimeout.isZero() || probeTimeout.isNegative()) {
            throw new IllegalArgumentException("Potrace timeouts must be positive.");
        }
        this.configuredCandidates = candidates == null ? null : List.copyOf(candidates);
        this.traceTimeout = traceTimeout;
        this.probeTimeout = probeTimeout;
    }

    public void trace(Path bitmapInput, Path svgOutput, ConversionSettings.DetailLevel detail)
            throws IOException {
        checkInterrupted();
        String command = requireExecutable();
        TraceOptions options = TraceOptions.forDetail(detail);
        Path scratchDirectory = Files.createTempDirectory("potrace-");
        Path stagedOutput = scratchDirectory.resolve("result.svg");
        Path diagnostics = scratchDirectory.resolve("diagnostics.log");
        List<String> arguments = List.of(
                command,
                "--svg",
                "--flat",
                "--unit", "1",
                "--turdsize", Integer.toString(options.turdSize()),
                "--alphamax", Double.toString(options.alphaMax()),
                "--opttolerance", Double.toString(options.optimizationTolerance()),
                "--output", stagedOutput.toString(),
                bitmapInput.toAbsolutePath().toString()
        );
        try {
            int exitCode = runProcess(arguments, traceTimeout, diagnostics);
            if (exitCode != 0) {
                throw new IOException("Potrace failed with exit code " + exitCode + ": "
                        + readDiagnostics(diagnostics));
            }
            if (!Files.isRegularFile(stagedOutput) || Files.size(stagedOutput) == 0) {
                throw new IOException("Potrace did not produce an SVG file: " + readDiagnostics(diagnostics));
            }
            checkInterrupted();
            Files.move(stagedOutput, svgOutput, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(stagedOutput);
            deleteQuietly(diagnostics);
            deleteQuietly(scratchDirectory);
        }
    }

    public boolean isAvailable() {
        try {
            requireExecutable();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public String requireExecutable() throws IOException {
        checkInterrupted();
        if (executable != null) return executable;
        synchronized (this) {
            checkInterrupted();
            if (executable != null) return executable;
            for (String candidate : candidates()) {
                checkInterrupted();
                if (works(candidate)) {
                    executable = candidate;
                    return candidate;
                }
            }
        }
        throw new IOException("Potrace was not found. Install it with 'brew install potrace' "
                + "(macOS) or set the POTRACE_PATH environment variable.");
    }

    private List<String> candidates() {
        if (configuredCandidates != null) return configuredCandidates;
        List<String> candidates = new ArrayList<>();
        addIfPresent(candidates, System.getProperty("potrace.path"));
        addIfPresent(candidates, System.getenv("POTRACE_PATH"));
        candidates.add("/opt/homebrew/bin/potrace");
        candidates.add("/usr/local/bin/potrace");
        candidates.add("potrace");
        return candidates;
    }

    private void addIfPresent(List<String> candidates, String value) {
        if (value != null && !value.isBlank()) candidates.add(value.trim());
    }

    private boolean works(String candidate) throws IOException {
        Path diagnostics = null;
        try {
            if (candidate.contains("/") && !Files.isExecutable(Path.of(candidate))) return false;
            diagnostics = Files.createTempFile("potrace-version-", ".log");
            return runProcess(List.of(candidate, "--version"), probeTimeout, diagnostics) == 0;
        } catch (InvalidPathException exception) {
            return false;
        } catch (IOException exception) {
            // Cancellation must stop discovery rather than trying every remaining executable.
            if (Thread.currentThread().isInterrupted()) throw exception;
            return false;
        } finally {
            deleteQuietly(diagnostics);
        }
    }

    private int runProcess(List<String> arguments, Duration timeout, Path diagnostics) throws IOException {
        checkInterrupted();
        Process process = new ProcessBuilder(arguments)
                .redirectErrorStream(true)
                // A file cannot fill the OS pipe buffer and block Potrace before waitFor returns.
                .redirectOutput(diagnostics.toFile())
                .start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IOException("Potrace timed out after " + timeout.toMillis() + " ms.");
            }
            return process.exitValue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Potrace was interrupted.", exception);
        } finally {
            terminateProcess(process);
        }
    }

    private void terminateProcess(Process process) {
        if (process.isAlive()) {
            // Stop children before their parent so a cancelled wrapper cannot leave native work running.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private String readDiagnostics(Path diagnostics) throws IOException {
        try (InputStream input = Files.newInputStream(diagnostics)) {
            byte[] bytes = input.readNBytes(MAX_DIAGNOSTIC_BYTES + 1);
            String result = new String(bytes, 0, Math.min(bytes.length, MAX_DIAGNOSTIC_BYTES),
                    StandardCharsets.UTF_8).trim();
            if (bytes.length > MAX_DIAGNOSTIC_BYTES) result += " [diagnostic output truncated]";
            return result.isBlank() ? "No diagnostic output was provided." : result;
        }
    }

    private void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Potrace was interrupted.");
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private record TraceOptions(int turdSize, double alphaMax, double optimizationTolerance) {
        static TraceOptions forDetail(ConversionSettings.DetailLevel detail) {
            return switch (detail) {
                case LOW -> new TraceOptions(12, 1.6, 0.50);
                case MEDIUM -> new TraceOptions(5, 1.2, 0.25);
                case HIGH -> new TraceOptions(2, 1.0, 0.10);
            };
        }
    }
}
