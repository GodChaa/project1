package se233.project1.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;

import java.util.ArrayList;
import java.util.List;

/** JavaFX-only editor mockup based on the supplied Vector Magic reference. */
public class EditorView extends BorderPane {
    private static final String BUTTON_STYLE = "-fx-background-color: #f3f4f6; "
            + "-fx-border-color: #cfd4da; -fx-border-radius: 5; -fx-background-radius: 5;";
    private final List<Label> previewPlaceholders = new ArrayList<>();

    public EditorView(EditorController editorController, SettingsController settingsController) {
        setTop(createToolbar(editorController));
        setCenter(createPreviewArea());
        setRight(createSettingsPanel(settingsController));
        setStyle("-fx-background-color: #ffffff;");
    }

    private HBox createToolbar(EditorController controller) {
        Button zoomIn = toolbarButton("＋", "Zoom In");
        zoomIn.setOnAction(event -> applyZoom(controller.zoomIn()));
        Button zoomOut = toolbarButton("−", "Zoom Out");
        zoomOut.setOnAction(event -> applyZoom(controller.zoomOut()));
        Button zoomToFit = toolbarButton("⛶", "Zoom to Fit");
        zoomToFit.setOnAction(event -> applyZoom(controller.zoomToFit()));

        HBox toolbar = new HBox(6, zoomIn, zoomOut, zoomToFit);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(7, 12, 7, 12));
        toolbar.setPrefHeight(46);
        toolbar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #d9dde3; "
                + "-fx-border-width: 0 0 1 0;");
        return toolbar;
    }

    private GridPane createPreviewArea() {
        GridPane previews = new GridPane();
        previews.setHgap(2);
        previews.setPadding(new Insets(0, 2, 2, 2));
        previews.setStyle("-fx-background-color: #d7dbe0;");

        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints right = new ColumnConstraints();
        left.setPercentWidth(50);
        right.setPercentWidth(50);
        left.setHgrow(Priority.ALWAYS);
        right.setHgrow(Priority.ALWAYS);
        previews.getColumnConstraints().addAll(left, right);

        BorderPane original = createPreview("Original Image", "Drop an image to preview");
        BorderPane vector = createPreview("Vectorized Result", "Vector preview");
        previews.add(original, 0, 0);
        previews.add(vector, 1, 0);
        GridPane.setHgrow(original, Priority.ALWAYS);
        GridPane.setHgrow(vector, Priority.ALWAYS);
        GridPane.setVgrow(original, Priority.ALWAYS);
        GridPane.setVgrow(vector, Priority.ALWAYS);
        return previews;
    }

    private BorderPane createPreview(String title, String placeholderText) {
        Label heading = new Label(title);
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setAlignment(Pos.CENTER);
        heading.setPadding(new Insets(8));
        heading.setStyle("-fx-font-size: 15px; -fx-background-color: rgba(255,255,255,0.88); "
                + "-fx-border-color: #d4d8dd; -fx-border-width: 0 0 1 0;");

        Label placeholder = new Label(placeholderText);
        placeholder.setStyle("-fx-font-size: 16px; -fx-text-fill: #8a9098; "
                + "-fx-background-color: rgba(255,255,255,0.78); -fx-padding: 12 18; "
                + "-fx-background-radius: 7;");
        previewPlaceholders.add(placeholder);
        StackPane canvas = new StackPane(placeholder);
        canvas.setBackground(checkerboardBackground());

        Label footer = new Label("900 × 900 px");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(7));
        footer.setStyle("-fx-background-color: rgba(255,255,255,0.9); -fx-text-fill: #555b63;");

        BorderPane preview = new BorderPane(canvas, heading, null, footer, null);
        preview.setMinWidth(0);
        return preview;
    }

    private VBox createSettingsPanel(SettingsController controller) {
        ConversionSettings settings = controller.settings();
        Button export = new Button("⇩  Export All");
        export.setMaxWidth(Double.MAX_VALUE);
        export.setPrefHeight(44);
        export.setStyle("-fx-background-color: #1976b9; -fx-text-fill: white; "
                + "-fx-font-size: 15px; -fx-font-weight: bold; -fx-background-radius: 6;");

        Label heading = new Label("Improve result");
        heading.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #34383d;");

        ToggleGroup detailGroup = new ToggleGroup();
        ToggleButton low = optionButton("Low", detailGroup);
        ToggleButton medium = optionButton("Medium", detailGroup);
        ToggleButton high = optionButton("High", detailGroup);
        low.setOnAction(event -> controller.selectDetail(ConversionSettings.DetailLevel.LOW));
        medium.setOnAction(event -> controller.selectDetail(ConversionSettings.DetailLevel.MEDIUM));
        high.setOnAction(event -> controller.selectDetail(ConversionSettings.DetailLevel.HIGH));
        switch (settings.getDetailLevel()) {
            case LOW -> low.setSelected(true);
            case MEDIUM -> medium.setSelected(true);
            case HIGH -> high.setSelected(true);
        }

        ToggleGroup colorGroup = new ToggleGroup();
        ToggleButton unlimited = optionButton("🌈 Unlimited", colorGroup);
        ToggleButton custom = optionButton("▦ Custom", colorGroup);

        Spinner<Integer> colorCount = new Spinner<>(1, 5, 2);
        colorCount.setMaxWidth(Double.MAX_VALUE);
        colorCount.getValueFactory().setValue(settings.getCustomColorCount());
        boolean isCustom = settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM;
        custom.setSelected(isCustom);
        unlimited.setSelected(!isCustom);
        colorCount.setDisable(!isCustom);
        unlimited.setOnAction(event -> {
            controller.selectColorMode(ConversionSettings.ColorMode.UNLIMITED);
            colorCount.setDisable(true);
        });
        custom.setOnAction(event -> {
            controller.selectColorMode(ConversionSettings.ColorMode.CUSTOM);
            colorCount.setDisable(false);
        });
        colorCount.valueProperty().addListener((value, oldValue, newValue) ->
                controller.setCustomColorCount(newValue));

        CheckBox removeBackground = new CheckBox("Remove Background");
        removeBackground.setSelected(settings.isRemoveBackground());
        removeBackground.selectedProperty().addListener((value, oldValue, selected) ->
                controller.setRemoveBackground(selected));
        removeBackground.setMaxWidth(Double.MAX_VALUE);
        removeBackground.setStyle("-fx-padding: 9 11; -fx-background-color: #f3f4f6; "
                + "-fx-border-color: #cfd4da; -fx-border-radius: 5; -fx-background-radius: 5;");

        Button back = new Button("← Back");
        back.setStyle(BUTTON_STYLE);
        back.setOnAction(event -> controller.backToDropZone());
        Button next = new Button("Next →");
        next.setStyle(BUTTON_STYLE);

        Region spacer = new Region();
        VBox panel = new VBox(10,
                export,
                heading,
                sectionLabel("Detail level"), equalButtonRow(low, medium, high),
                sectionLabel("Colors"), equalButtonRow(unlimited, custom), colorCount,
                sectionLabel("Advanced"), removeBackground,
                spacer, equalButtonRow(back, next));
        VBox.setVgrow(spacer, Priority.ALWAYS);
        panel.setPadding(new Insets(12));
        panel.setPrefWidth(275);
        panel.setMinWidth(245);
        panel.setStyle("-fx-background-color: #ffffff; -fx-border-color: #d9dde3; "
                + "-fx-border-width: 0 0 0 1;");
        return panel;
    }

    private void applyZoom(double zoom) {
        for (Label placeholder : previewPlaceholders) {
            placeholder.setScaleX(zoom);
            placeholder.setScaleY(zoom);
        }
    }

    private Button toolbarButton(String icon, String text) {
        Button button = new Button(icon + "  " + text);
        button.setStyle(BUTTON_STYLE + " -fx-font-size: 13px;");
        return button;
    }

    private ToggleButton optionButton(String text, ToggleGroup group) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setStyle(BUTTON_STYLE);
        return button;
    }

    private HBox equalButtonRow(ButtonBase... buttons) {
        HBox row = new HBox(5);
        for (ButtonBase button : buttons) {
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            row.getChildren().add(button);
        }
        return row;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #474c53;");
        return label;
    }

    private Background checkerboardBackground() {
        int tileSize = 20;
        WritableImage tile = new WritableImage(tileSize, tileSize);
        var writer = tile.getPixelWriter();
        for (int y = 0; y < tileSize; y++) {
            for (int x = 0; x < tileSize; x++) {
                boolean alternate = (x < tileSize / 2) != (y < tileSize / 2);
                writer.setColor(x, y, alternate ? Color.web("#e6e8eb") : Color.WHITE);
            }
        }
        BackgroundImage image = new BackgroundImage(
                tile, BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
                BackgroundPosition.DEFAULT,
                new BackgroundSize(tileSize, tileSize, false, false, false, false));
        return new Background(
                java.util.List.of(new BackgroundFill(Color.WHITE, null, null)),
                java.util.List.of(image)
        );
    }
}
