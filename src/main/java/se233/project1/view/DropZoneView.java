package se233.project1.view;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import se233.project1.controller.DropZoneController;

/** Drop-zone screen matching the project1prototype design. */
public class DropZoneView extends StackPane {
    private static final String BACKGROUND = "#f2e9e9";

    public DropZoneView(DropZoneController controller) {
        Rectangle tray = new Rectangle(78, 52);
        tray.setArcWidth(10);
        tray.setArcHeight(10);
        tray.setFill(Color.TRANSPARENT);
        tray.setStroke(Color.web("#595959"));
        tray.setStrokeWidth(3);

        Label arrow = new Label("↓");
        arrow.setStyle("-fx-font-size: 62px; -fx-font-weight: 200; -fx-text-fill: #595959;");
        StackPane icon = new StackPane(tray, arrow);
        StackPane.setAlignment(arrow, Pos.TOP_CENTER);
        icon.setPrefSize(95, 90);

        Label message = new Label("Drag file(s) here");
        message.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333333;");
        Label formats = new Label("JPG, PNG, or ZIP");
        formats.setStyle("-fx-font-size: 12px; -fx-text-fill: #7a7272;");
        Label feedback = new Label(" ");
        feedback.setStyle("-fx-font-size: 12px; -fx-text-fill: #a33b3b;");

        VBox content = new VBox(12, icon, message, formats, feedback);
        content.setAlignment(Pos.CENTER);
        content.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        content.setStyle("-fx-background-color: " + BACKGROUND + ";");

        getChildren().add(content);
        setStyle("-fx-background-color: " + BACKGROUND + ";");

        setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                content.setStyle("-fx-background-color: #eadede;");
            }
            event.consume();
        });
        setOnDragExited(event -> {
            content.setStyle("-fx-background-color: " + BACKGROUND + ";");
            event.consume();
        });
        setOnDragDropped(event -> {
            content.setStyle("-fx-background-color: " + BACKGROUND + ";");
            boolean accepted = event.getDragboard().hasFiles()
                    && controller.handleDrop(event.getDragboard().getFiles());
            if (!accepted) feedback.setText(controller.getValidationMessage());
            event.setDropCompleted(accepted);
            event.consume();
        });
    }
}
