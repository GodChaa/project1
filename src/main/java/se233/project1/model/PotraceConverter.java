package se233.project1.model;

import java.io.IOException;
import java.nio.file.Path;

/** Potrace adapter; color preparation and SVG merging will also live here. */
public class PotraceConverter {
    public void trace(Path potraceExecutable, Path bitmapInput, Path svgOutput)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                potraceExecutable.toString(), bitmapInput.toString(),
                "--svg", "--output", svgOutput.toString()
        ).redirectErrorStream(true).start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Potrace exited with code " + exitCode + ".");
        }
    }
}
