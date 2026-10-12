package se233.project1.model;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One imported raster image and its most recently generated preview. */
public class ImageProject {
    private final Path sourcePath;
    private final String displayName;
    private final Map<String, Path> previews = new ConcurrentHashMap<>();
    private volatile Path previewSvg;
    private volatile int colorLimit = 5;

    public ImageProject(String filePath) {
        this(Path.of(filePath), Path.of(filePath).getFileName().toString());
    }

    public ImageProject(Path sourcePath, String displayName) {
        this.sourcePath = sourcePath.toAbsolutePath().normalize();
        this.displayName = displayName;
    }

    public String getFilePath() {
        return sourcePath.toString();
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Path getPreviewSvg() {
        return previewSvg;
    }

    public boolean hasPreview(String signature) {
        return previews.containsKey(signature);
    }

    public Path getPreview(String signature) {
        return previews.get(signature);
    }

    public int getColorLimit() {
        return colorLimit;
    }

    public void setColorLimit(int colorLimit) {
        this.colorLimit = Math.max(1, Math.min(5, colorLimit));
    }

    public void setPreview(Path previewSvg, String signature) {
        this.previewSvg = previewSvg;
        previews.put(signature, previewSvg);
    }
}
