package se233.project1.view;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.web.WebView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorViewTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    void secondScreenContainsOnlyTheRequiredWorkingControls() throws Exception {
        CompletableFuture<Void> checked = new CompletableFuture<>();
        Platform.runLater(() -> {
            ConversionSettings settings = new ConversionSettings();
            EditorController editorController = new EditorController(settings);
            try {
                AtomicBoolean returnedToDropZone = new AtomicBoolean();
                SettingsController settingsController = new SettingsController(
                        settings, () -> returnedToDropZone.set(true), () -> { });
                EditorView view = new EditorView(editorController, settingsController);
                new Scene(view, 1040, 600);
                view.applyCss();
                view.layout();

                assertEquals(3, view.lookupAll(".tool-button").size());
                assertEquals(2, view.lookupAll(".preview-title").size());
                assertEquals(5, view.lookupAll(".segment-button").size());
                assertTrue(hasLabeledText(view, "Export All"));
                assertTrue(hasLabeledText(view, "Export ZIP + report"));
                assertTrue(hasLabeledText(view, "Remove Background"));
                assertTrue(hasLabeledText(view, "Back"));
                assertTrue(hasLabeledText(view, "Next"));
                assertFalse(hasLabeledText(view, "Edit Result"));
                assertFalse(hasLabeledText(view, "Hand-pick Settings"));

                Button back = navigationButton(view, "Back");
                Button next = navigationButton(view, "Next");
                view.updateNavigation(false, false);
                assertFalse(back.isDisabled());
                assertTrue(next.isDisabled());
                back.fire();
                assertTrue(returnedToDropZone.get());

                view.updateNavigation(false, true);
                assertFalse(next.isDisabled());

                view.lookupAll(".tool-button").stream()
                        .map(node -> (Button) node)
                        .forEach(Button::fire);
                checked.complete(null);
            } catch (Throwable throwable) {
                checked.completeExceptionally(throwable);
            } finally {
                editorController.close();
            }
        });
        checked.get(10, TimeUnit.SECONDS);
    }

    @Test
    void largeSvgPreviewFitsViewportAndZoomReturnsToFit() throws Exception {
        Path svg = temporaryDirectory.resolve("large.svg");
        Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2000\" "
                + "height=\"1000\" viewBox=\"0 0 2000 1000\">"
                + "<rect width=\"2000\" height=\"1000\" fill=\"red\"/></svg>");

        CompletableFuture<Void> checked = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                CenterPreviewPane previews = new CenterPreviewPane();
                new Scene(previews, 900, 600);
                previews.showVector(svg, new ConversionSettings());
                previews.applyCss();
                previews.layout();
                WebView vector = (WebView) previews.lookup(".web-view");
                assertTrue(vector != null);
                Runnable verify = () -> {
                    try {
                        assertEquals("100%", vector.getEngine().executeScript(
                                "document.documentElement.getAttribute('width')"));
                        assertEquals("100%", vector.getEngine().executeScript(
                                "document.documentElement.getAttribute('height')"));
                        previews.applyZoom(1.5);
                        assertEquals("scale(1.5)", vector.getEngine().executeScript(
                                "document.documentElement.style.transform"));
                        previews.applyZoom(1.0);
                        assertEquals("scale(1)", vector.getEngine().executeScript(
                                "document.documentElement.style.transform"));
                        checked.complete(null);
                    } catch (Throwable throwable) {
                        checked.completeExceptionally(throwable);
                    }
                };
                vector.getEngine().getLoadWorker().stateProperty().addListener(
                        (observable, previous, state) -> {
                            if (state == Worker.State.SUCCEEDED) {
                                verify.run();
                            } else if (state == Worker.State.FAILED) {
                                checked.completeExceptionally(vector.getEngine().getLoadWorker().getException());
                            }
                        });
                if (vector.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED) {
                    verify.run();
                }
            } catch (Throwable throwable) {
                checked.completeExceptionally(throwable);
            }
        });
        checked.get(10, TimeUnit.SECONDS);
    }

    private Button navigationButton(EditorView view, String accessibleText) {
        return view.lookupAll(".navigation-button").stream()
                .map(Button.class::cast)
                .filter(button -> accessibleText.equals(button.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private boolean hasLabeledText(EditorView view, String text) {
        boolean labelFound = view.lookupAll(".label").stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
        boolean buttonFound = view.lookupAll(".button").stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
        boolean checkBoxFound = view.lookupAll(".check-box").stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
        return labelFound || buttonFound || checkBoxFound;
    }
}
