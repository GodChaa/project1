package se233.project1.controller;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Handles actions from the initial drag-and-drop screen. */
public class DropZoneController {
    private final Runnable showEditor;

    public DropZoneController(Runnable showEditor) {
        this.showEditor = showEditor;
    }

    public boolean handleDrop(List<File> files) {
        boolean hasSupportedFile = files.stream().anyMatch(this::isSupported);
        if (hasSupportedFile) {
            showEditor.run();
        }
        return hasSupportedFile;
    }

    private boolean isSupported(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".zip"));
    }
}
