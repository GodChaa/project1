package se233.project1.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InputImporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsOnlyImagesFromZipAndCleansTemporaryFiles() throws Exception {
        Path first = createPng("one.png", Color.RED);
        Path second = createPng("two.png", Color.BLUE);
        Path archive = temporaryDirectory.resolve("images.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, first, "folder/one.png");
            add(zip, second, "two.png");
            addBytes(zip, "notes.txt", "ignored".getBytes());
        }

        InputImporter importer = new InputImporter();
        List<ImageProject> projects = importer.importFiles(List.of(archive.toFile()));
        assertEquals(2, projects.size());
        assertEquals("one.png", projects.get(0).getDisplayName());
        Path extracted = projects.get(0).getSourcePath();
        assertTrue(Files.exists(extracted));

        importer.close();
        importer.close();
        assertFalse(Files.exists(extracted));
        assertTrue(Files.exists(first), "Cleanup must not delete original input files.");
        assertTrue(Files.exists(archive));
    }

    @Test
    void importsMultipleImagesInTheirOriginalOrder() throws Exception {
        Path png = createPng("UPPER.PNG", Color.RED);
        Path jpeg = temporaryDirectory.resolve("photo.JPEG");
        assertTrue(ImageIO.write(new BufferedImage(3, 4, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg.toFile()));
        try (InputImporter importer = new InputImporter()) {
            List<ImageProject> projects = importer.importFiles(List.of(jpeg.toFile(), png.toFile()));
            assertEquals(List.of("photo.JPEG", "UPPER.PNG"),
                    projects.stream().map(ImageProject::getDisplayName).toList());
            assertEquals(jpeg.toAbsolutePath(), projects.getFirst().getSourcePath());
        }
        assertTrue(Files.exists(jpeg));
        assertTrue(Files.exists(png));
    }

    @Test
    void ignoresMacResourceForksAndKeepsDuplicateFilenames() throws Exception {
        Path png = createPng("image.png", Color.BLUE);
        Path archive = temporaryDirectory.resolve("mac.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addBytes(zip, "__MACOSX/folder/._image.png", new byte[]{0, 1, 2});
            addBytes(zip, "folder/._image.png", new byte[]{0, 1, 2});
            add(zip, png, "first/image.png");
            add(zip, png, "second/image.png");
        }
        try (InputImporter importer = new InputImporter()) {
            List<ImageProject> projects = importer.importFiles(List.of(archive.toFile()));
            assertEquals(2, projects.size());
            assertEquals("image.png", projects.getFirst().getDisplayName());
            assertNotEquals(projects.get(0).getSourcePath(), projects.get(1).getSourcePath());
        }
    }

    @Test
    void flattensUnsafeAndWindowsZipPathsInsideTemporaryStorage() throws Exception {
        Path png = createPng("source.png", Color.GREEN);
        Path archive = temporaryDirectory.resolve("paths.zip");
        Path escaped = temporaryDirectory.resolve("escaped.png");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip, png, "../escaped.png");
            add(zip, png, escaped.toString());
            add(zip, png, "C:\\folder\\image.PNG");
        }
        try (InputImporter importer = new InputImporter()) {
            List<ImageProject> projects = importer.importFiles(List.of(archive.toFile()));
            assertEquals(3, projects.size());
            assertEquals("image.PNG", projects.get(2).getDisplayName());
            assertFalse(Files.exists(escaped));
            for (ImageProject project : projects) {
                assertTrue(Files.exists(project.getSourcePath()));
                assertFalse(project.getSourcePath().startsWith(temporaryDirectory));
            }
        }
    }

    @Test
    void reportsCorruptImagesWithTheirUserVisibleName() throws Exception {
        Path corrupt = temporaryDirectory.resolve("broken.png");
        Files.writeString(corrupt, "not an image");
        try (InputImporter importer = new InputImporter()) {
            IOException direct = assertThrows(IOException.class,
                    () -> importer.importFiles(List.of(corrupt.toFile())));
            assertTrue(direct.getMessage().contains("broken.png"));
            Path archive = temporaryDirectory.resolve("broken.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                add(zip, corrupt, "nested/broken.png");
            }
            IOException zipped = assertThrows(IOException.class,
                    () -> importer.importFiles(List.of(archive.toFile())));
            assertTrue(zipped.getMessage().contains("broken.png"));
            assertTrue(zipped.getMessage().contains("broken.zip"));
        }
    }

    @Test
    void rejectsCorruptOrEmptyArchivesAndDisguisedUnsupportedImages() throws Exception {
        Path corrupt = temporaryDirectory.resolve("corrupt.zip");
        Files.writeString(corrupt, "not a zip");
        Path empty = temporaryDirectory.resolve("empty.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(empty))) {
            addBytes(zip, "notes.txt", "no images".getBytes());
        }
        Path disguised = temporaryDirectory.resolve("actually-gif.png");
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", disguised.toFile()));
        try (InputImporter importer = new InputImporter()) {
            assertThrows(IOException.class, () -> importer.importFiles(List.of(corrupt.toFile())));
            assertThrows(IOException.class, () -> importer.importFiles(List.of(empty.toFile())));
            assertThrows(IOException.class, () -> importer.importFiles(List.of(disguised.toFile())));
        }
    }

    @Test
    void rejectsInvalidSelectionsAndImportsAfterClose() throws Exception {
        Path png = createPng("one.png", Color.RED);
        Path zip = temporaryDirectory.resolve("empty.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) { }
        InputImporter importer = new InputImporter();
        assertThrows(IOException.class, () -> importer.importFiles(null));
        assertThrows(IOException.class, () -> importer.importFiles(List.of()));
        assertThrows(IOException.class, () -> importer.importFiles(List.of(zip.toFile(), png.toFile())));
        assertThrows(IOException.class, () -> importer.importFiles(List.of(zip.toFile(), zip.toFile())));
        assertThrows(IOException.class, () -> importer.importFiles(List.of(temporaryDirectory.toFile())));
        importer.close();
        assertThrows(IOException.class, () -> importer.importFiles(List.of(png.toFile())));
    }

    private Path createPng(String name, Color color) throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, color.getRGB());
        }
        Path path = temporaryDirectory.resolve(name);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
        return path;
    }

    private void add(ZipOutputStream zip, Path source, String entryName) throws Exception {
        addBytes(zip, entryName, Files.readAllBytes(source));
    }

    private void addBytes(ZipOutputStream zip, String entryName, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(bytes);
        zip.closeEntry();
    }
}
