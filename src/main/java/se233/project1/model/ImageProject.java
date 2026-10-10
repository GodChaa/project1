package se233.project1.model;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Imported image collection and navigation state. */
public class ImageProject {
    public record ImageItem(Path sourcePath, Path svgPath) {
        public ImageItem(Path sourcePath) {
            this(sourcePath.toAbsolutePath().normalize(), null);
        }
    }

    private final List<ImageItem> images = new ArrayList<>();
    private int currentIndex;

    public boolean importFiles(List<File> files) {
        List<ImageItem> imported = files.stream()
                .filter(File::isFile)
                .filter(file -> isImage(file.getName()))
                .map(file -> new ImageItem(file.toPath()))
                .toList();
        if (imported.isEmpty()) {
            return false;
        }
        images.clear();
        images.addAll(imported);
        currentIndex = 0;
        return true;
    }

    public Optional<ImageItem> current() {
        return images.isEmpty() ? Optional.empty() : Optional.of(images.get(currentIndex));
    }

    public void previous() {
        if (currentIndex > 0) currentIndex--;
    }

    public void next() {
        if (currentIndex < images.size() - 1) currentIndex++;
    }

    public int size() {
        return images.size();
    }

    private boolean isImage(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }
}
