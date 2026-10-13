package se233.project1.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.MAC, OS.LINUX})
class PotraceConverterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void tracesSuccessfullyEvenWhenDiagnosticsExceedThePipeBuffer() throws Exception {
        Path script = executable("success.sh", "exit 0", """
                i=0
                while [ "$i" -lt 6000 ]; do
                  printf 'A verbose diagnostic line from the tracing process.\n'
                  i=$((i + 1))
                done
                printf '<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>\n' > "$output"
                """);
        Path output = temporaryDirectory.resolve("output with spaces.svg");
        PotraceConverter converter = converter(script, Duration.ofSeconds(3));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> converter.trace(
                temporaryDirectory.resolve("input with spaces.pgm"), output,
                ConversionSettings.DetailLevel.MEDIUM));

        assertTrue(Files.readString(output).contains("<path"));
        assertScratchWasRemoved();
    }

    @Test
    void reportsFailureAndPreservesAnExistingOutput() throws Exception {
        Path script = executable("failure.sh", "exit 0", """
                printf '<partial' > "$output"
                printf 'Unsupported bitmap format\n' >&2
                exit 7
                """);
        Path output = temporaryDirectory.resolve("existing.svg");
        Files.writeString(output, "previous successful result");

        IOException failure = assertThrows(IOException.class, () -> converter(script, Duration.ofSeconds(1))
                .trace(temporaryDirectory.resolve("input.pgm"), output, ConversionSettings.DetailLevel.HIGH));

        assertTrue(failure.getMessage().contains("exit code 7"));
        assertTrue(failure.getMessage().contains("Unsupported bitmap format"));
        assertEquals("previous successful result", Files.readString(output));
        assertScratchWasRemoved();
    }

    @Test
    void doesNotTreatAStaleOutputAsASuccessfulTrace() throws Exception {
        Path script = executable("no-output.sh", "exit 0", "exit 0");
        Path output = temporaryDirectory.resolve("existing.svg");
        Files.writeString(output, "previous result");

        IOException failure = assertThrows(IOException.class, () -> converter(script, Duration.ofSeconds(1))
                .trace(temporaryDirectory.resolve("input.pgm"), output, ConversionSettings.DetailLevel.LOW));

        assertTrue(failure.getMessage().contains("did not produce an SVG"));
        assertEquals("previous result", Files.readString(output));
        assertScratchWasRemoved();
    }

    @Test
    void limitsDiagnosticSize() throws Exception {
        Path script = executable("verbose-failure.sh", "exit 0", """
                i=0
                while [ "$i" -lt 1000 ]; do
                  printf 'An error message with repeated diagnostic output.\n'
                  i=$((i + 1))
                done
                exit 1
                """);

        IOException failure = assertThrows(IOException.class, () -> converter(script, Duration.ofSeconds(2))
                .trace(temporaryDirectory.resolve("input.pgm"), temporaryDirectory.resolve("output.svg"),
                        ConversionSettings.DetailLevel.MEDIUM));

        assertTrue(failure.getMessage().contains("diagnostic output truncated"));
        assertTrue(failure.getMessage().length() < 9_000);
        assertScratchWasRemoved();
    }

    @Test
    void timesOutAndTerminatesAHungTrace() throws Exception {
        Path pid = temporaryDirectory.resolve("trace.pid");
        Path childPid = temporaryDirectory.resolve("child.pid");
        Path script = executable("hung.sh", "exit 0", hungProcess(pid, childPid));
        Path output = temporaryDirectory.resolve("output.svg");

        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                assertThrows(IOException.class, () -> converter(script, Duration.ofMillis(300))
                        .trace(temporaryDirectory.resolve("input.pgm"), output,
                                ConversionSettings.DetailLevel.MEDIUM)));

        assertTrue(failure.getMessage().contains("timed out"));
        assertFalse(Files.exists(output));
        assertProcessStopped(pid);
        assertProcessStopped(childPid);
        assertScratchWasRemoved();
    }

    @Test
    void anEndlesslyVerboseProcessStillTimesOutWithoutWritingDiagnosticFiles() throws Exception {
        Path script = executable("endless-output.sh", "exit 0", """
                while :; do
                  printf 'Repeated diagnostic output from a broken process.\n'
                done
                """);
        Path output = temporaryDirectory.resolve("output.svg");
        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                assertThrows(IOException.class, () -> converter(script, Duration.ofMillis(300))
                        .trace(temporaryDirectory.resolve("input.pgm"), output,
                                ConversionSettings.DetailLevel.MEDIUM)));

        assertTrue(failure.getMessage().contains("timed out"));
        assertFalse(Files.exists(output));
        assertScratchWasRemoved();
    }

    @Test
    void missingExecutableExplainsHowToInstallOrConfigurePotrace() {
        PotraceConverter converter = new PotraceConverter(
                List.of(temporaryDirectory.resolve("missing-potrace").toString()),
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        IOException failure = assertThrows(IOException.class, converter::requireExecutable);
        assertTrue(failure.getMessage().contains("brew install potrace"));
        assertTrue(failure.getMessage().contains("POTRACE_PATH"));
        assertFalse(converter.isAvailable());
    }

    @Test
    void timesOutAndTerminatesAHungVersionProbe() throws Exception {
        Path pid = temporaryDirectory.resolve("probe.pid");
        Path childPid = temporaryDirectory.resolve("probe-child.pid");
        Path script = executable("hung-version.sh", hungProcess(pid, childPid), "exit 0");
        PotraceConverter converter = new PotraceConverter(List.of(script.toString()),
                Duration.ofSeconds(1), Duration.ofMillis(300));

        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertFalse(converter.isAvailable()));

        assertProcessStopped(pid);
        assertProcessStopped(childPid);
    }

    @Test
    void interruptionStopsTheTraceAndPreservesTheInterruptFlag() throws Exception {
        Path pid = temporaryDirectory.resolve("interrupted.pid");
        Path childPid = temporaryDirectory.resolve("interrupted-child.pid");
        Path script = executable("interrupted.sh", "exit 0", hungProcess(pid, childPid));
        PotraceConverter converter = converter(script, Duration.ofSeconds(30));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                converter.trace(temporaryDirectory.resolve("input.pgm"),
                        temporaryDirectory.resolve("output.svg"), ConversionSettings.DetailLevel.MEDIUM);
            } catch (Throwable exception) {
                failure.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!Files.exists(childPid)) Thread.sleep(10);
            });
            worker.interrupt();
            worker.join(3_000);
            assertFalse(worker.isAlive(), "Cancellation must promptly stop the worker.");
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(failure.get().getMessage().contains("interrupted"));
            assertTrue(interrupted.get(), "The worker's interrupt flag must be retained.");
            assertProcessStopped(pid);
            assertProcessStopped(childPid);
            assertScratchWasRemoved();
        } finally {
            worker.interrupt();
            worker.join(3_000);
        }
    }

    private PotraceConverter converter(Path executable, Duration timeout) {
        return new PotraceConverter(List.of(executable.toString()), timeout, Duration.ofSeconds(1));
    }

    private Path executable(String name, String versionBody, String traceBody) throws IOException {
        Path script = temporaryDirectory.resolve(name);
        Files.writeString(script, "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then\n" + versionBody + "\nfi\n"
                + """
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "--output" ]; then
                    shift
                    output="$1"
                  fi
                  shift
                done
                """
                + "printf '%s' \"$output\" > " + quote(temporaryDirectory.resolve("staged-output.txt")) + "\n"
                + traceBody + "\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private String hungProcess(Path pid, Path childPid) {
        return "echo $$ > " + quote(pid) + "\nsleep 60 &\necho $! > " + quote(childPid) + "\nwait\n";
    }

    private String quote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private void assertScratchWasRemoved() throws IOException {
        Path stagedOutput = Path.of(Files.readString(temporaryDirectory.resolve("staged-output.txt")));
        assertFalse(Files.exists(stagedOutput.getParent()), "Temporary output and diagnostics must be cleaned up.");
    }

    private void assertProcessStopped(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(10);
        });
    }
}
