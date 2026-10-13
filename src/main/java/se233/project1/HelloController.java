package se233.project1;

import javafx.scene.Scene;
import javafx.stage.Stage;
import se233.project1.controller.DropZoneController;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;
import se233.project1.view.DropZoneView;
import se233.project1.view.EditorView;

import java.io.File;
import java.util.List;
public class HelloController {
    private static final double EDITOR_WIDTH = 1040;
    private static final double EDITOR_HEIGHT = 600;

    private final Stage stage;
    private final ConversionSettings settings = new ConversionSettings();
    private Stage editorStage;
    private EditorController editorController;

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
            stageToClose.setOnCloseRequest(null);
            if (editorController != null) {
                editorController.close();
                editorController = null;
            }
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

    public void showEditor(List<File> files) {
        stage.hide();
        editorStage = new Stage();
        editorController = new EditorController(settings);
        SettingsController settingsController = new SettingsController(
                settings, this::showDropZone, editorController::settingsChanged);
        editorStage.setTitle("Vectorizer");
        editorStage.setMinWidth(900);
        editorStage.setMinHeight(520);
        EditorView editorView = new EditorView(editorController, settingsController);
        editorStage.setScene(new Scene(
                editorView,
                EDITOR_WIDTH,
                EDITOR_HEIGHT
        ));
        editorStage.setOnCloseRequest(event -> {
            if (editorController != null) editorController.close();
            stage.close();
        });
        editorStage.centerOnScreen();
        editorStage.show();
        editorController.loadInputs(files);
    }
}
