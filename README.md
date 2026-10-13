# SE233 Raster-to-Vector Project

JavaFX application for importing JPG/PNG images (or a ZIP archive), previewing Potrace-generated SVG curves, and exporting the results in a batch.

## Requirements

- JDK 24 or newer (verified with JDK 26)
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

Drop JPG/PNG files or one ZIP archive into the first window. Every new import starts with Medium detail, Custom 2 colors, and background removal off. Each image is checked for its color limit before its preview is generated. For a one-color image the effective value is 1; the requested batch value remains 2 for the other images.

Each image keeps its own detail, color mode/count, and background-removal settings. Switching images restores that image's last selections, and both export options use the saved settings for each file.

The top toolbar contains Zoom In, Zoom Out, and Zoom to Fit. Both previews use the same fitted scale. Back/Next navigate the imported images; Back on the first image returns to the drop zone. Changing any conversion setting generates a new preview in the background and cancels an obsolete conversion.

## Conversion settings

- **Low / Medium / High:** change Potrace smoothing, small-feature filtering, and curve optimization. Custom mode also adjusts its working resolution.
- **Unlimited:** retains the original image's RGB colors and opacity without a palette cap or image downsampling. Substantial color regions use Potrace curves; sparse colors use SVG vector runs. No raster image is embedded. Photographs with many unique colors can therefore produce large SVG files.
- **Custom:** quantizes to the requested 1–5 colors. The selector is limited to the current image's detected color count, capped at 5. Transparent pixels do not count as colors.
- **Remove Background:** removes areas connected to the image border that match the detected border background; enclosed details are retained. Existing transparency is preserved. This is color-based removal, not semantic object segmentation.

## Export and additional feature

**Export All** opens the operating system's folder chooser and saves one SVG per image. Names are made unique, including when that folder already contains SVG files. After a successful export, the application opens the output folder when the OS supports it. If an individual conversion fails, the other completed files remain available and an error identifies the failed image.

Both preprocessing and exporting run images in parallel **only** for multiple images with Medium detail and Custom colors. Other configurations process images sequentially on a background thread. The progress bar and status show the completed count and explicitly identify parallel work.

**Export ZIP + report** is the additional feature. It saves all SVGs and a `manifest.csv` report containing source/output names, detail level, color mode, effective custom count, detected color limit, background setting, and output size. The archive is published only when every conversion succeeds. Existing ZIP files are not overwritten; choose a new filename.

Closing the editor cancels active work and cleans up extracted images and preview files. Original images and completed exports are retained.

## Test

```bash
./mvnw clean test
```

The tests cover real Potrace JPG/PNG conversion and valid SVG XML, curves and detail options, original palettes and alpha, custom colors, background removal, ZIP extraction/cleanup, missing tools and process timeouts, batch parallelism/cancellation, output naming, ZIP reports, editor navigation/settings, and matching preview scales. JavaFX tests need a graphical desktop. Potrace-dependent integration tests require the installed executable.

See [RUBRIC_CHECKLIST.md](RUBRIC_CHECKLIST.md) for the rubric mapping and a short demonstration checklist.
