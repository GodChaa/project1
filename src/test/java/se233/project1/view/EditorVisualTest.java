package se233.project1.view;

import javafx.animation.AnimationTimer;
import javafx.concurrent.Worker;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se233.project1.FxTestSupport;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
class EditorVisualTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.start();
    }

    @Test
    void largeImageAndSvgStayAlignedAndAllControlsFitBothWindowSizes() throws Exception {
        Path original = createOriginal();
        Path vector = createVector();
        WindowFixture fixture = FxTestSupport.onFx(() -> createWindow(original, vector));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (!FxTestSupport.onFx(() -> fixture.original().getImage().getProgress() >= 1
                        && fixture.vector().getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED)) {
                    Thread.sleep(10);
                }
            });
            assertFalse(FxTestSupport.onFx(() -> fixture.original().getImage().isError()));
            pulse();

            Dimensions defaultFit = FxTestSupport.onFx(() -> dimensions(fixture));
            List<String> defaultProblems = FxTestSupport.onFx(() -> controlsOutsideViewport(fixture));
            writeScreenshot(fixture, "editor-default.png");

            FxTestSupport.onFx(() -> {
                fixture.view().applyZoom(1.5);
                return null;
            });
            pulse();
            Dimensions zoomed = FxTestSupport.onFx(() -> dimensions(fixture));

            FxTestSupport.onFx(() -> {
                fixture.view().applyZoom(1.0);
                fixture.stage().setWidth(fixture.stage().getWidth() + 900 - fixture.scene().getWidth());
                fixture.stage().setHeight(fixture.stage().getHeight() + 492 - fixture.scene().getHeight());
                return null;
            });
            pulse();
            FxTestSupport.onFx(() -> {
                fixture.view().applyCss();
                fixture.view().layout();
                return null;
            });
            pulse();
            Dimensions minimumFit = FxTestSupport.onFx(() -> dimensions(fixture));
            List<String> minimumProblems = FxTestSupport.onFx(() -> controlsOutsideViewport(fixture));
            writeScreenshot(fixture, "editor-minimum.png");

            assertAll(
                    () -> assertEquals(1040, defaultFit.sceneWidth(), 1),
                    () -> assertEquals(600, defaultFit.sceneHeight(), 1),
                    () -> assertEquals(900, minimumFit.sceneWidth(), 1),
                    () -> assertEquals(492, minimumFit.sceneHeight(), 1),
                    () -> assertMatchingArtwork(defaultFit, "Default fit"),
                    () -> assertMatchingArtwork(zoomed, "Zoomed"),
                    () -> assertMatchingArtwork(minimumFit, "Minimum fit"),
                    () -> assertEquals(defaultFit.originalWidth() * 1.5, zoomed.originalWidth(), 1),
                    () -> assertEquals(defaultFit.vectorWidth() * 1.5, zoomed.vectorWidth(), 1),
                    () -> assertTrue(defaultProblems.isEmpty(), String.join("\n", defaultProblems)),
                    () -> assertTrue(minimumProblems.isEmpty(), String.join("\n", minimumProblems))
            );
        } finally {
            FxTestSupport.onFx(() -> {
                fixture.vector().getEngine().load(null);
                fixture.stage().close();
                fixture.controller().close();
                return null;
            });
        }
    }

    private WindowFixture createWindow(Path original, Path vector) {
        ConversionSettings settings = new ConversionSettings();
        EditorController controller = new EditorController(settings);
        EditorView view = new EditorView(controller,
                new SettingsController(settings, () -> { }, controller::settingsChanged));
        Scene scene = new Scene(view, 1040, 600);
        Stage stage = new Stage();
        stage.setTitle("Vectorizer - visual verification");
        stage.setScene(scene);
        stage.show();
        view.showOriginal(original, "color-study.png", 2, 4);
        view.showVector(vector, settings);
        view.updateNavigation(true, true);
        view.setWorking(true, 0.5, "Preprocessing in parallel: 2 / 4");
        view.applyCss();
        view.layout();
        ImageView image = descendants(view).stream().filter(ImageView.class::isInstance)
                .map(ImageView.class::cast).findFirst().orElseThrow();
        WebView web = (WebView) view.lookup(".web-view");
        return new WindowFixture(stage, scene, view, controller, image, web);
    }

    private Dimensions dimensions(WindowFixture fixture) {
        Bounds original = fixture.original().localToScene(fixture.original().getBoundsInLocal());
        double vectorWidth = ((Number) fixture.vector().getEngine().executeScript(
                "document.getElementById('artwork').getBoundingClientRect().width")).doubleValue();
        double vectorHeight = ((Number) fixture.vector().getEngine().executeScript(
                "document.getElementById('artwork').getBoundingClientRect().height")).doubleValue();
        return new Dimensions(original.getWidth(), original.getHeight(), vectorWidth, vectorHeight,
                fixture.scene().getWidth(), fixture.scene().getHeight());
    }

    private void assertMatchingArtwork(Dimensions dimensions, String description) {
        assertAll(description,
                () -> assertTrue(dimensions.originalWidth() > 100),
                () -> assertEquals(dimensions.originalWidth(), dimensions.vectorWidth(), 2,
                        "Original and SVG displayed widths differ"),
                () -> assertEquals(dimensions.originalHeight(), dimensions.vectorHeight(), 2,
                        "Original and SVG displayed heights differ"),
                () -> assertEquals(2, dimensions.vectorWidth() / dimensions.vectorHeight(), 0.001)
        );
    }

    private List<String> controlsOutsideViewport(WindowFixture fixture) {
        List<String> failures = new ArrayList<>();
        for (Node node : descendants(fixture.view())) {
            if (!(node instanceof ButtonBase) && !node.getStyleClass().contains("work-progress")
                    && !node.getStyleClass().contains("work-status")) continue;
            if (!node.isVisible()) continue;
            Bounds bounds = node.localToScene(node.getLayoutBounds());
            if (bounds.getMinX() < -0.5 || bounds.getMinY() < -0.5
                    || bounds.getMaxX() > fixture.scene().getWidth() + 0.5
                    || bounds.getMaxY() > fixture.scene().getHeight() + 0.5) {
                failures.add(node.getAccessibleText() + " " + node.getStyleClass() + " " + bounds);
            }
        }
        return failures;
    }

    private List<Node> descendants(Node node) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) nodes.addAll(descendants(child));
        }
        return nodes;
    }

    private void pulse() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        FxTestSupport.onFx(() -> {
            new AnimationTimer() {
                private int pulses;

                @Override
                public void handle(long now) {
                    if (++pulses == 3) {
                        stop();
                        ready.complete(null);
                    }
                }
            }.start();
            return null;
        });
        ready.get(5, TimeUnit.SECONDS);
    }

    private void writeScreenshot(WindowFixture fixture, String name) throws Exception {
        BufferedImage screenshot = FxTestSupport.onFx(() -> {
            WritableImage pixels = fixture.scene().snapshot(null);
            int width = (int) pixels.getWidth();
            int height = (int) pixels.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            pixels.getPixelReader().getPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), data, 0, width);
            return image;
        });
        Path output = Path.of("target", "qa", name);
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(screenshot, "png", output.toFile()));
    }

    private Path createOriginal() throws Exception {
        BufferedImage image = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.decode("#fff5e8"));
            graphics.fillRect(0, 0, 2000, 1000);
            graphics.setColor(Color.decode("#337ca0"));
            graphics.fillRoundRect(200, 200, 600, 600, 140, 140);
            graphics.setColor(Color.decode("#e76f51"));
            graphics.fillOval(880, 200, 600, 600);
            graphics.setColor(Color.decode("#e9c46a"));
            graphics.fillPolygon(new int[]{1480, 1780, 1780}, new int[]{500, 200, 800}, 3);
        } finally {
            graphics.dispose();
        }
        Path original = temporaryDirectory.resolve("color-study.png");
        assertTrue(ImageIO.write(image, "png", original.toFile()));
        return original;
    }

    private Path createVector() throws Exception {
        Path vector = temporaryDirectory.resolve("color-study.svg");
        Files.writeString(vector, """
                <svg xmlns="http://www.w3.org/2000/svg" width="2000" height="1000" viewBox="0 0 2000 1000">
                  <rect id="artwork" width="2000" height="1000" fill="#fff5e8"/>
                  <rect x="200" y="200" width="600" height="600" rx="70" fill="#337ca0"/>
                  <circle cx="1180" cy="500" r="300" fill="#e76f51"/>
                  <path d="M1480 500 L1780 200 V800 Z" fill="#e9c46a"/>
                </svg>
                """);
        return vector;
    }

    private record WindowFixture(Stage stage, Scene scene, EditorView view, EditorController controller,
                                 ImageView original, WebView vector) { }

    private record Dimensions(double originalWidth, double originalHeight, double vectorWidth,
                              double vectorHeight, double sceneWidth, double sceneHeight) { }
}
