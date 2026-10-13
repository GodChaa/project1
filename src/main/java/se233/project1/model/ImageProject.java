package se233.project1.model;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ImageProject {
    private final Path sourcePath;
    private final String displayName;
    private final Map<String, Path> previews = new ConcurrentHashMap<>();
    private volatile int colorLimit = 5;

    public ImageProject(Path sourcePath, String displayName) {
        this.sourcePath = sourcePath.toAbsolutePath().normalize();
        this.displayName = displayName;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public String getDisplayName() {
        return displayName;
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
        previews.put(signature, previewSvg);
    }
}
