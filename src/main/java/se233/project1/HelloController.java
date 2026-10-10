package se233.project1;

import javafx.scene.Scene;
import javafx.stage.Stage;
import se233.project1.controller.DropZoneController;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;
import se233.project1.view.DropZoneView;
import se233.project1.view.EditorView;

/** Main controller that owns the Stage and switches between application screens. */
public class HelloController {
    private static final double EDITOR_WIDTH = 1180;
    private static final double EDITOR_HEIGHT = 720;

    private final Stage stage;
    private final ConversionSettings settings = new ConversionSettings();
    private Stage editorStage;

    public HelloController(Stage stage) {
        this.stage = stage;
    }

    public void start() {
        showDropZone();
    }

    public void showDropZone() {
        if (editorStage != null) {
            Stage stageToClose = editorStage;
            editorStage = null;
            stage.show();
            stageToClose.close();
        }

        DropZoneController dropZoneController = new DropZoneController(this::showEditor);
        stage.setTitle("Drop Image");
        stage.setMinWidth(520);
        stage.setMinHeight(360);
        stage.setScene(new Scene(new DropZoneView(dropZoneController), 620, 420));
        stage.centerOnScreen();
        stage.show();
    }

    public void showEditor() {
        stage.hide();
        editorStage = new Stage();
        EditorController editorController = new EditorController();
        SettingsController settingsController = new SettingsController(settings, this::showDropZone);
        editorStage.setTitle("Vectorizer");
        editorStage.setMinWidth(940);
        editorStage.setMinHeight(600);
        editorStage.setScene(new Scene(
                new EditorView(editorController, settingsController),
                EDITOR_WIDTH,
                EDITOR_HEIGHT
        ));
        editorStage.centerOnScreen();
        editorStage.show();
    }
}
