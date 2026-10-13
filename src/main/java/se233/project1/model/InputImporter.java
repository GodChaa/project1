package se233.project1.model;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
public class InputImporter implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final Set<Path> temporaryDirectories = new HashSet<>();
    private final Path temporaryRoot;
    private volatile boolean closed;

    public InputImporter() {
        this(null);
    }
    InputImporter(Path temporaryRoot) {
        this.temporaryRoot = temporaryRoot;
    }

    public List<ImageProject> importFiles(List<File> files) throws IOException {
        checkAvailable();
        validateSelection(files);
        if (isZip(files.getFirst().getName())) {
            return extractZip(files.getFirst().toPath());
        }

        List<ImageProject> projects = new ArrayList<>();
        for (File file : files) {
            checkAvailable();
            validateImage(file.toPath(), file.getName());
            projects.add(new ImageProject(file.toPath(), file.getName()));
        }
        checkAvailable();
        return List.copyOf(projects);
    }

    private void validateSelection(List<File> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IOException("No files were selected. Choose JPG/PNG images, or one ZIP archive.");
        }
        for (File file : files) {
            if (file == null || !file.isFile() || !file.canRead()) {
                throw new IOException("An input file is missing or cannot be read.");
            }
            if (!isImage(file.getName()) && !(files.size() == 1 && isZip(file.getName()))) {
                throw new IOException("Choose JPG/PNG images, or a single ZIP archive.");
            }
        }
    }

    private List<ImageProject> extractZip(Path archive) throws IOException {
        Path directory;
        synchronized (lifecycleLock) {
            checkAvailable();
            directory = temporaryRoot == null
                    ? Files.createTempDirectory("vectorizer-import-")
                    : Files.createTempDirectory(temporaryRoot, "vectorizer-import-");
            temporaryDirectories.add(directory);
        }

        boolean complete = false;
        List<ImageProject> projects = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkAvailable();
                ZipEntry entry = entries.nextElement();
                String normalizedName = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !isImage(normalizedName) || isMetadata(normalizedName)) {
                    continue;
                }
                String displayName = normalizedName.substring(normalizedName.lastIndexOf('/') + 1);
                Path target = Files.createTempFile(directory, "image-", extension(displayName));
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkAvailable();
                        output.write(buffer, 0, count);
                    }
                }
                validateImage(target, displayName);
                projects.add(new ImageProject(target, displayName));
            }
            if (projects.isEmpty()) {
                throw new IOException("No readable JPG or PNG images were found in this ZIP archive.");
            }
            checkAvailable();
            complete = true;
            return List.copyOf(projects);
        } catch (IOException exception) {
            throw new IOException("Could not import " + archive.getFileName() + ": "
                    + exception.getMessage(), exception);
        } finally {
            if (!complete || closed) {
                deleteDirectory(directory);
                synchronized (lifecycleLock) {
                    temporaryDirectories.remove(directory);
                }
            }
        }
    }

    private boolean isMetadata(String name) {
        for (String component : name.split("/")) {
            if (component.equalsIgnoreCase("__MACOSX") || component.startsWith("._")) return true;
        }
        return false;
    }

    private String extension(String name) {
        return name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    }

    private void validateImage(Path path, String displayName) throws IOException {
        checkAvailable();
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IOException("The file cannot be opened.");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("The image data is not recognized.");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
                    throw new IOException("Only JPG and PNG image data are supported.");
                }
                reader.setInput(input, true, true);
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("The image data is incomplete.");
                image.flush();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            throw new IOException(displayName + " is not a readable JPG/PNG image: "
                    + exception.getMessage(), exception);
        }
        checkAvailable();
    }

    private void checkAvailable() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Image import was cancelled.");
        }
    }

    private boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private boolean isZip(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    @Override
    public void close() {
        List<Path> directories;
        synchronized (lifecycleLock) {
            closed = true;
            directories = List.copyOf(temporaryDirectories);
            temporaryDirectories.clear();
        }
        directories.forEach(this::deleteDirectory);
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
