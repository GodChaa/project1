package se233.project1.controller;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class DropZoneController {
    private final Consumer<List<File>> showEditor;
    private String validationMessage = "Drop JPG/PNG files, or one ZIP archive.";

    public DropZoneController(Consumer<List<File>> showEditor) {
        this.showEditor = showEditor;
    }

    public boolean handleDrop(List<File> files) {
        if (files == null || files.isEmpty()) {
            validationMessage = "No files were selected.";
            return false;
        }

        long zipCount = files.stream().filter(this::isZip).count();
        boolean validImages = files.stream().allMatch(this::isImage);
        boolean oneZip = files.size() == 1 && zipCount == 1;
        if (!validImages && !oneZip) {
            validationMessage = "Choose JPG/PNG images, or a single ZIP archive.";
            return false;
        }

        validationMessage = "Loading " + files.size() + " item(s)...";
        showEditor.accept(List.copyOf(files));
        return true;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    private boolean isImage(File file) {
        if (!file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    private boolean isZip(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
