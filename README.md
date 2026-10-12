# SE233 Raster-to-Vector Project

JavaFX application for importing JPG/PNG images (or a ZIP archive), previewing Potrace-generated SVG curves, and exporting the results in a batch.

## Requirements

- JDK 24
- Potrace 1.16 or newer

Install Potrace on macOS:

```bash
brew install potrace
```

On Linux, install it with the system package manager, for example `sudo apt install potrace`. If the executable is not on `PATH`, set `POTRACE_PATH` to its full path. The application also checks `/opt/homebrew/bin/potrace` and `/usr/local/bin/potrace` automatically.

## Run

```bash
./mvnw clean javafx:run
```

Drop JPG/PNG files or one ZIP archive into the first window. The default Medium + Custom 2 colors configuration triggers parallel preprocessing when more than one image is imported.

## Test

```bash
./mvnw clean test
```

The integration tests call Potrace and verify JPG/PNG conversion, valid SVG XML, smooth curve commands, color limits, background removal, and ZIP extraction.
