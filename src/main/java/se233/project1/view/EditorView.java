package se233.project1.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;
import se233.project1.model.ImageProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** JavaFX-only editor mockup based on the supplied Vector Magic reference. */
public class EditorView extends BorderPane {
    private static final String BUTTON_STYLE = "-fx-background-color: #f3f4f6; " + "-fx-border-color: #cfd4da; -fx-border-radius: 5; -fx-background-radius: 5;";
    private final List<Label> previewPlaceholders = new ArrayList<>();
    private final EditorController editorController;
    private StackPane originalCanvas;
    private Button btnNext;

    public EditorView(EditorController editorController, SettingsController settingsController) {
        this.editorController = editorController;
        setTop(createToolbar(editorController));
        setCenter(createPreviewArea());
        setRight(createSettingsPanel(settingsController));
        setStyle("-fx-background-color: #ffffff;");

        // เปิดใช้งานระบบ Drag-and-Drop
        setupDragAndDrop(editorController);
    }

    private void setupDragAndDrop(EditorController controller) {
        this.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        this.setOnDragDropped(event -> {
            javafx.scene.input.Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                List<File> files = db.getFiles();
                List<ImageProject> projects = new ArrayList<>();

                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                        projects.add(new ImageProject(file.getAbsolutePath()));
                    } else if (name.endsWith(".zip")) {
                        // TODO: รองรับการแตกไฟล์ .zip ตามข้อกำหนด
                    }
                }

                if (!projects.isEmpty()) {
                    // ส่งรายการโปรเจกต์ให้ Controller
                    controller.setProjects(projects);

                    // แสดงภาพแรกบนพรีวิวฝั่งซ้าย (Original Image)
                    displayOriginalImage(projects.get(0).getFilePath());
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    public void displayOriginalImage(String imagePath) {
        if (originalCanvas != null) {
            try {
                File file = new File(imagePath);
                Image image = new Image(file.toURI().toString());
                ImageView imageView = new ImageView(image);
                imageView.setPreserveRatio(true);
                imageView.fitWidthProperty().bind(originalCanvas.widthProperty().subtract(20));
                imageView.fitHeightProperty().bind(originalCanvas.heightProperty().subtract(20));

                originalCanvas.getChildren().setAll(imageView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Top panel: Zoom controls only
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

    private Button toolbarButton(String icon, String text) {
        Button button = new Button(text.isEmpty() ? icon : icon + "  " + text);
        button.setStyle(BUTTON_STYLE + " -fx-font-size: 13px;");
        return button;
    }

    // Center preview: Side-by-side Original and Vectorized result
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

        BorderPane original = createOriginalPreview("Original Image", "Drop an image to preview");
        BorderPane vector = createVectorPreview("Vectorized Result", "Vector preview");

        previews.add(original, 0, 0);
        previews.add(vector, 1, 0);

        GridPane.setHgrow(original, Priority.ALWAYS);
        GridPane.setHgrow(vector, Priority.ALWAYS);
        GridPane.setVgrow(original, Priority.ALWAYS);
        GridPane.setVgrow(vector, Priority.ALWAYS);

        return previews;
    }

    private BorderPane createOriginalPreview(String title, String placeholderText) {
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

        originalCanvas = new StackPane(placeholder);
        originalCanvas.setBackground(checkerboardBackground());

        Label footer = new Label("900 × 900 px");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(7));
        footer.setStyle("-fx-background-color: rgba(255,255,255,0.9); -fx-text-fill: #555b63;");

        BorderPane preview = new BorderPane(originalCanvas, heading, null, footer, null);
        preview.setMinWidth(0);
        return preview;
    }

    private BorderPane createVectorPreview(String title, String placeholderText) {
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

        Button export = new Button("↓  Download Result");
        export.setMaxWidth(Double.MAX_VALUE);
        export.setPrefHeight(40);
        export.setStyle("-fx-background-color: #2b73b7; -fx-text-fill: white; "
                + "-fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 4;");

        // เชื่อมโยงปุ่ม Export All กับ EditorController เพื่อเปิด DirectoryChooser
        export.setOnAction(event -> {
            javafx.stage.Window window = export.getScene().getWindow();
            if (editorController != null) {
                editorController.handleExportAll(window);
            }
        });

        Label heading = new Label("Improve result");
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #222;");

        HBox detailHeader = new HBox(5, sectionLabel("Detail level"), createInstructionLink());
        detailHeader.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup detailGroup = new ToggleGroup();
        ToggleButton low = segmentButton("Low", detailGroup);
        ToggleButton medium = segmentButton("Medium", detailGroup);
        ToggleButton high = segmentButton("High", detailGroup);

        low.setOnAction(event -> controller.selectDetail(ConversionSettings.DetailLevel.LOW));
        medium.setOnAction(event -> controller.selectDetail(ConversionSettings.DetailLevel.MEDIUM));
        high.setOnAction(event -> controller.selectDetail(ConversionSettings.DetailLevel.HIGH));

        switch (settings.getDetailLevel()) {
            case LOW -> low.setSelected(true);
            case MEDIUM -> medium.setSelected(true);
            case HIGH -> high.setSelected(true);
        }

        HBox colorHeader = new HBox(5, sectionLabel("Colors"), createInstructionLink());
        colorHeader.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup colorGroup = new ToggleGroup();
        ToggleButton unlimited = segmentButton(" Unlimited", colorGroup);
        ToggleButton custom = segmentButton(" Custom", colorGroup);

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

        Label advancedHeading = new Label("Advanced");
        advancedHeading.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #444;");

        Button editResult = new Button("✏️ Edit Result");
        editResult.setMaxWidth(Double.MAX_VALUE);
        editResult.setStyle(BUTTON_STYLE);
        editResult.setDisable(true);

        CheckBox removeBackground = new CheckBox("Remove Background");
        removeBackground.setSelected(settings.isRemoveBackground());
        removeBackground.selectedProperty().addListener((value, oldValue, selected) ->
                controller.setRemoveBackground(selected));
        removeBackground.setMaxWidth(Double.MAX_VALUE);
        removeBackground.setStyle("-fx-padding: 8 10; -fx-background-color: #ffffff; "
                + "-fx-border-color: #d0d5dd; -fx-border-radius: 4; -fx-background-radius: 4;");

        Button handPick = new Button("⚙️ Hand-pick Settings");
        handPick.setMaxWidth(Double.MAX_VALUE);
        handPick.setStyle(BUTTON_STYLE);
        handPick.setDisable(true);

        Button btnBack = new Button("← Back");
        btnBack.setStyle(BUTTON_STYLE);
        btnNext = new Button("Next →");
        btnNext.setStyle(BUTTON_STYLE);

        Region spacer = new Region();
        VBox panel = new VBox(8,
                export,
                heading,
                detailHeader, segmentedRow(low, medium, high),
                colorHeader, segmentedRow(unlimited, custom), colorCount,
                new Separator(),
                advancedHeading,
                editResult,
                removeBackground,
                handPick,
                spacer,
                segmentedRow(btnBack, btnNext));

        VBox.setVgrow(spacer, Priority.ALWAYS);
        panel.setPadding(new Insets(12));
        panel.setPrefWidth(260);
        panel.setMinWidth(240);
        panel.setStyle("-fx-background-color: #fcfcfc; -fx-border-color: #d9dde3; "
                + "-fx-border-width: 0 0 0 1;");
        return panel;
    }

    private Hyperlink createInstructionLink() {
        Hyperlink link = new Hyperlink("Instructions ↗");
        link.setStyle("-fx-font-size: 11px; -fx-text-fill: #1a73e8; -fx-padding: 0;");
        return link;
    }

    private void applyZoom(double zoom) {
        for (Label placeholder : previewPlaceholders) {
            placeholder.setScaleX(zoom);
            placeholder.setScaleY(zoom);
        }
    }

    private ToggleButton segmentButton(String text, ToggleGroup group) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setStyle(BUTTON_STYLE + " -fx-font-size: 12px;");
        return button;
    }

    private HBox segmentedRow(ButtonBase... buttons) {
        HBox row = new HBox(0);
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