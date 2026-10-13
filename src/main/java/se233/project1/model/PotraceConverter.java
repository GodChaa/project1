package se233.project1.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
public class PotraceConverter {
    private static final int MAX_DIAGNOSTIC_BYTES = 8_192;
    private final List<String> configuredCandidates;
    private final Duration traceTimeout;
    private final Duration probeTimeout;
    private volatile String executable;

    public PotraceConverter() {
        this(null, Duration.ofMinutes(2), Duration.ofSeconds(5));
    }
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
        Path absoluteOutput = svgOutput.toAbsolutePath();
        Path scratchDirectory = Files.createTempDirectory(absoluteOutput.getParent(), ".potrace-");
        Path stagedOutput = scratchDirectory.resolve("result.svg");
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
            ProcessResult result = runProcess(arguments, traceTimeout);
            if (result.exitCode() != 0) {
                throw new IOException("Potrace failed with exit code " + result.exitCode() + ": "
                        + result.diagnostics());
            }
            if (!Files.isRegularFile(stagedOutput) || Files.size(stagedOutput) == 0) {
                throw new IOException("Potrace did not produce an SVG file: " + result.diagnostics());
            }
            checkInterrupted();
            try {
                Files.move(stagedOutput, absoluteOutput,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(stagedOutput, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteQuietly(stagedOutput);
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
        try {
            if (candidate.contains("/") && !Files.isExecutable(Path.of(candidate))) return false;
            return runProcess(List.of(candidate, "--version"), probeTimeout).exitCode() == 0;
        } catch (InvalidPathException exception) {
            return false;
        } catch (IOException exception) {
            if (Thread.currentThread().isInterrupted()) throw exception;
            return false;
        }
    }

    private ProcessResult runProcess(List<String> arguments, Duration timeout) throws IOException {
        checkInterrupted();
        Process process = new ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start();
        DiagnosticBuffer diagnostics = new DiagnosticBuffer();
        Thread reader = Thread.ofPlatform().daemon().name("potrace-diagnostics").start(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) diagnostics.append(buffer, count);
            } catch (IOException ignored) {
            }
        });
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IOException("Potrace timed out after " + timeout.toMillis() + " ms.");
            }
            reader.join(1_000);
            return new ProcessResult(process.exitValue(), diagnostics.message());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Potrace was interrupted.", exception);
        } finally {
            terminateProcess(process);
            boolean interrupted = Thread.interrupted();
            try {
                reader.join(1_000);
            } catch (InterruptedException exception) {
                interrupted = true;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void terminateProcess(Process process) {
        if (process.isAlive()) {
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

    private static final class DiagnosticBuffer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_DIAGNOSTIC_BYTES + 1);

        synchronized void append(byte[] buffer, int count) {
            int remaining = MAX_DIAGNOSTIC_BYTES + 1 - bytes.size();
            if (remaining > 0) bytes.write(buffer, 0, Math.min(count, remaining));
        }

        synchronized String message() {
            byte[] prefix = bytes.toByteArray();
            String result = new String(prefix, 0, Math.min(prefix.length, MAX_DIAGNOSTIC_BYTES),
                    StandardCharsets.UTF_8).trim();
            if (prefix.length > MAX_DIAGNOSTIC_BYTES) result += " [diagnostic output truncated]";
            return result.isBlank() ? "No diagnostic output was provided." : result;
        }
    }

    private record ProcessResult(int exitCode, String diagnostics) { }

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
