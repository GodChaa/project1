package se233.project1.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import se233.project1.controller.EditorController;
import se233.project1.controller.SettingsController;
import se233.project1.model.ConversionSettings;

import java.net.URL;
import java.nio.file.Path;

/** The rebuilt second screen shown after files are dropped. */
public class EditorView extends BorderPane implements EditorController.View {
    private final EditorController editorController;
    private final SettingsController settingsController;
    private final CenterPreviewPane previews = new CenterPreviewPane();
    private final Button download = new Button();
    private final Button exportBundle = new Button();
    private final Button previous = new Button();
    private final Button next = new Button();
    private final Label colorCountValue = new Label();
    private final Button colorCountUp = new Button();
    private final Button colorCountDown = new Button();
    private final HBox colorCountRow = new HBox();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label();
    private final ToggleGroup detailGroup = new ToggleGroup();
    private final ToggleGroup colorGroup = new ToggleGroup();
    private final ToggleButton removeBackground = new ToggleButton();
    private final VBox configurationPanel = new VBox(7);
    private int currentColorCount;
    private int maximumColorCount = 5;
    private boolean hasPreviousImage;
    private boolean hasNextImage;
    private boolean editingEnabled = true;
    private boolean updatingSettings;
    private boolean hasImages;

    public EditorView(EditorController editorController, SettingsController settingsController) {
        this.editorController = editorController;
        this.settingsController = settingsController;
        editorController.attachView(this);

        getStyleClass().add("editor-root");
        setTop(createToolbar());
        setCenter(previews);
        setRight(createSettingsPanel());

        URL stylesheet = getClass().getResource("/se233/project1/editor.css");
        if (stylesheet != null) getStylesheets().add(stylesheet.toExternalForm());
    }

    /** Only the three controls required by the PDF appear in the top panel. */
    private StackPane createToolbar() {
        Button zoomIn = toolbarButton(magnifierIcon(true), "Zoom In");
        Button zoomOut = toolbarButton(magnifierIcon(false), "Zoom Out");
        Button zoomToFit = toolbarButton(fitIcon(), "Zoom to Fit");
        zoomIn.setOnAction(event -> editorController.zoomIn());
        zoomOut.setOnAction(event -> editorController.zoomOut());
        zoomToFit.setOnAction(event -> editorController.zoomToFit());

        HBox controls = new HBox(3, zoomIn, zoomOut, zoomToFit);
        controls.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(controls, Pos.CENTER_LEFT);

        Label title = new Label("Try Vectorizer.AI");
        title.getStyleClass().add("toolbar-title");

        StackPane toolbar = new StackPane(controls, title);
        toolbar.setPadding(new Insets(3, 6, 3, 6));
        toolbar.getStyleClass().add("top-toolbar");
        return toolbar;
    }

    private Button toolbarButton(Node icon, String description) {
        Button button = new Button();
        button.setGraphic(icon);
        button.setTooltip(new Tooltip(description));
        button.setAccessibleText(description);
        button.getStyleClass().add("tool-button");
        return button;
    }

    private Node magnifierIcon(boolean plus) {
        Circle lens = new Circle(6.25, 6.25, 4.5);
        lens.setFill(Color.TRANSPARENT);
        lens.getStyleClass().add("button-icon-stroke");
        Line handle = iconLine(9.5, 9.5, 14.5, 14.5);
        Line horizontal = iconLine(3.9, 6.25, 8.6, 6.25);
        Pane icon = iconPane(16, 16, lens, handle, horizontal);
        if (plus) icon.getChildren().add(iconLine(6.25, 3.9, 6.25, 8.6));
        return icon;
    }

    private Node fitIcon() {
        Pane icon = iconPane(16, 16,
                iconLine(2, 6, 2, 2), iconLine(2, 2, 6, 2),
                iconLine(10, 2, 14, 2), iconLine(14, 2, 14, 6),
                iconLine(14, 10, 14, 14), iconLine(14, 14, 10, 14),
                iconLine(6, 14, 2, 14), iconLine(2, 14, 2, 10));
        icon.getStyleClass().add("fit-icon");
        return icon;
    }

    private Line iconLine(double startX, double startY, double endX, double endY) {
        Line line = new Line(startX, startY, endX, endY);
        line.getStyleClass().add("button-icon-stroke");
        return line;
    }

    private Pane iconPane(double width, double height, Node... shapes) {
        Pane pane = new Pane(shapes);
        pane.setMinSize(width, height);
        pane.setPrefSize(width, height);
        pane.setMaxSize(width, height);
        return pane;
    }

    private VBox createSettingsPanel() {
        ConversionSettings settings = settingsController.settings();

        download.setMaxWidth(Double.MAX_VALUE);
        download.setGraphic(downloadGraphic());
        download.setAccessibleText("Export All");
        download.setTooltip(new Tooltip("Save every imported image as an SVG in a folder"));
        download.setDisable(true);
        download.getStyleClass().add("download-button");
        download.setOnAction(event -> editorController.exportAll(download.getScene().getWindow()));

        exportBundle.setGraphic(labeledGraphic(bundleIcon(), "Export ZIP + report", "segment-text"));
        exportBundle.setAccessibleText("Export ZIP and report");
        exportBundle.setTooltip(new Tooltip("Package every SVG with a CSV conversion report for sharing"));
        exportBundle.setMaxWidth(Double.MAX_VALUE);
        exportBundle.getStyleClass().add("bundle-button");
        exportBundle.setDisable(true);
        exportBundle.setOnAction(event -> editorController.exportZip(exportBundle.getScene().getWindow()));

        Label improveHeading = new Label("Improve result");
        improveHeading.getStyleClass().add("improve-heading");

        ToggleButton low = segmentButton("Low", detailGroup);
        ToggleButton medium = segmentButton("Medium", detailGroup);
        ToggleButton high = segmentButton("High", detailGroup);
        low.setUserData(ConversionSettings.DetailLevel.LOW);
        medium.setUserData(ConversionSettings.DetailLevel.MEDIUM);
        high.setUserData(ConversionSettings.DetailLevel.HIGH);
        switch (settings.getDetailLevel()) {
            case LOW -> low.setSelected(true);
            case MEDIUM -> medium.setSelected(true);
            case HIGH -> high.setSelected(true);
        }
        low.setOnAction(event -> keepSelected(low,
                () -> settingsController.selectDetail(ConversionSettings.DetailLevel.LOW)));
        medium.setOnAction(event -> keepSelected(medium,
                () -> settingsController.selectDetail(ConversionSettings.DetailLevel.MEDIUM)));
        high.setOnAction(event -> keepSelected(high,
                () -> settingsController.selectDetail(ConversionSettings.DetailLevel.HIGH)));

        ToggleButton unlimited = colorSegmentButton("Unlimited", rainbowIcon(), colorGroup);
        ToggleButton custom = colorSegmentButton("Custom", colorGridIcon(), colorGroup);
        unlimited.setUserData(ConversionSettings.ColorMode.UNLIMITED);
        custom.setUserData(ConversionSettings.ColorMode.CUSTOM);
        boolean customSelected = settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM;
        custom.setSelected(customSelected);
        unlimited.setSelected(!customSelected);

        currentColorCount = settings.getCustomColorCount();
        Node colorCounter = createColorCounter();
        updateColorCounter();
        Label colorCountLabel = new Label("Number of colors");
        colorCountLabel.getStyleClass().add("color-count-label");
        colorCountRow.getChildren().addAll(colorCountLabel, colorCounter);
        colorCountRow.setSpacing(7);
        colorCountRow.setAlignment(Pos.CENTER_LEFT);
        setColorCountVisible(customSelected);

        unlimited.setOnAction(event -> keepSelected(unlimited, () -> {
            setColorCountVisible(false);
            settingsController.selectColorMode(ConversionSettings.ColorMode.UNLIMITED);
        }));
        custom.setOnAction(event -> keepSelected(custom, () -> {
            setColorCountVisible(true);
            settingsController.selectColorMode(ConversionSettings.ColorMode.CUSTOM);
        }));

        removeBackground.setGraphic(backgroundGraphic());
        removeBackground.setAccessibleText("Remove Background");
        removeBackground.setSelected(settings.isRemoveBackground());
        removeBackground.setMaxWidth(Double.MAX_VALUE);
        removeBackground.getStyleClass().add("background-option");
        removeBackground.setAccessibleRole(javafx.scene.AccessibleRole.CHECK_BOX);
        removeBackground.selectedProperty().addListener((observable, oldValue, selected) -> {
            if (!updatingSettings) settingsController.setRemoveBackground(selected);
        });

        previous.getStyleClass().add("navigation-button");
        next.getStyleClass().add("navigation-button");
        previous.setGraphic(navigationGraphic("Back", false));
        next.setGraphic(navigationGraphic("Next", true));
        previous.setAccessibleText("Back");
        next.setAccessibleText("Next");
        previous.setTooltip(new Tooltip("Previous image, or return to the drop zone"));
        next.setTooltip(new Tooltip("Next image"));
        next.setDisable(true);
        previous.setOnAction(event -> {
            if (hasPreviousImage) editorController.previous();
            else settingsController.backToDropZone();
        });
        next.setOnAction(event -> editorController.next());

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("work-progress");
        status.setWrapText(true);
        status.getStyleClass().add("work-status");
        setWorkStatusVisible(false);

        Region spacer = new Region();
        configurationPanel.getChildren().addAll(
                improveHeading,
                sectionLabel("Detail level"), segmentedRow(low, medium, high),
                sectionLabel("Colors"), segmentedRow(unlimited, custom), colorCountRow,
                new Separator(),
                sectionLabel("Advanced"), removeBackground);
        VBox panel = new VBox(7,
                download, exportBundle, configurationPanel,
                progress, status,
                spacer,
                segmentedRow(previous, next));
        VBox.setVgrow(spacer, Priority.ALWAYS);
        panel.setPadding(new Insets(7));
        panel.getStyleClass().add("settings-panel");
        return panel;
    }

    private ToggleButton segmentButton(String text, ToggleGroup group) {
        ToggleButton button = new ToggleButton();
        button.setToggleGroup(group);
        button.setGraphic(labeledGraphic(null, text, "segment-text"));
        button.setAccessibleText(text);
        button.getStyleClass().add("segment-button");
        return button;
    }

    private ToggleButton colorSegmentButton(String text, Node icon, ToggleGroup group) {
        ToggleButton button = new ToggleButton();
        button.setToggleGroup(group);
        button.setGraphic(labeledGraphic(icon, text, "segment-text"));
        button.setAccessibleText(text);
        button.getStyleClass().addAll("segment-button", "color-segment-button");
        return button;
    }

    private HBox labeledGraphic(Node icon, String text, String labelStyleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(labelStyleClass);
        HBox content = new HBox(4);
        if (icon != null) content.getChildren().add(icon);
        content.getChildren().add(label);
        content.setAlignment(Pos.CENTER);
        return content;
    }

    private Node downloadGraphic() {
        Rectangle shaft = new Rectangle(6, 1, 4, 9);
        Polygon head = new Polygon(2.0, 8.0, 14.0, 8.0, 8.0, 14.0);
        Rectangle base = new Rectangle(2, 15, 12, 2);
        for (Node part : new Node[]{shaft, head, base}) {
            part.getStyleClass().add("download-icon");
        }
        Pane arrow = iconPane(16, 18, shaft, head, base);
        HBox content = labeledGraphic(arrow, "Export All", "download-label");
        content.setSpacing(10);
        content.setAlignment(Pos.CENTER_LEFT);
        return content;
    }

    private Node bundleIcon() {
        Rectangle box = new Rectangle(1, 2, 14, 13);
        box.setFill(Color.TRANSPARENT);
        box.getStyleClass().add("button-icon-stroke");
        Pane icon = iconPane(16, 17, box, iconLine(1, 5, 15, 5));
        for (int y = 6; y <= 12; y += 3) {
            Rectangle tooth = new Rectangle(7, y, 2, 2);
            tooth.setFill(Color.web("#3d4145"));
            icon.getChildren().add(tooth);
        }
        return icon;
    }

    private Node navigationGraphic(String text, boolean pointsRight) {
        double tipX = pointsRight ? 13 : 3;
        double tailX = pointsRight ? 3 : 13;
        Line shaft = iconLine(tailX, 8, tipX, 8);
        Line upper = iconLine(tipX, 8, pointsRight ? 9 : 7, 4);
        Line lower = iconLine(tipX, 8, pointsRight ? 9 : 7, 12);
        Pane arrow = iconPane(16, 16, shaft, upper, lower);
        return labeledGraphic(arrow, text, "navigation-label");
    }

    private Node backgroundGraphic() {
        Rectangle box = new Rectangle(1, 1, 13, 13);
        box.getStyleClass().add("background-box-shape");
        Line checkStart = iconLine(3.5, 7.5, 6.3, 10.3);
        Line checkEnd = iconLine(6.3, 10.3, 12, 4.2);
        checkStart.getStyleClass().add("background-check-mark");
        checkEnd.getStyleClass().add("background-check-mark");
        Pane checkBox = iconPane(16, 16, box, checkStart, checkEnd);
        return labeledGraphic(checkBox, "Remove Background", "background-label");
    }

    private Node createColorCounter() {
        colorCountValue.getStyleClass().add("color-counter-value");
        colorCountValue.setAlignment(Pos.CENTER_LEFT);

        colorCountUp.setGraphic(triangleIcon(true));
        colorCountDown.setGraphic(triangleIcon(false));
        colorCountUp.setAccessibleText("Increase number of colors");
        colorCountDown.setAccessibleText("Decrease number of colors");
        colorCountUp.getStyleClass().add("counter-button");
        colorCountDown.getStyleClass().add("counter-button");
        colorCountUp.setOnAction(event -> setColorCount(currentColorCount + 1));
        colorCountDown.setOnAction(event -> setColorCount(currentColorCount - 1));

        VBox arrows = new VBox(0, colorCountUp, colorCountDown);
        arrows.getStyleClass().add("counter-arrows");
        HBox counter = new HBox(0, colorCountValue, arrows);
        counter.setAlignment(Pos.CENTER_RIGHT);
        counter.getStyleClass().add("color-counter");
        return counter;
    }

    private Node triangleIcon(boolean pointsUp) {
        Polygon triangle = pointsUp
                ? new Polygon(1.0, 5.0, 5.0, 1.0, 9.0, 5.0)
                : new Polygon(1.0, 1.0, 5.0, 5.0, 9.0, 1.0);
        triangle.getStyleClass().add("counter-triangle");
        return iconPane(10, 6, triangle);
    }

    private void setColorCount(int requestedCount) {
        int boundedCount = Math.max(1, Math.min(maximumColorCount, requestedCount));
        if (boundedCount != currentColorCount) {
            currentColorCount = boundedCount;
            settingsController.setCustomColorCount(currentColorCount);
        }
        updateColorCounter();
    }

    private void updateColorCounter() {
        colorCountValue.setText(Integer.toString(currentColorCount));
        colorCountUp.setDisable(currentColorCount >= maximumColorCount);
        colorCountDown.setDisable(currentColorCount <= 1);
    }

    private Node rainbowIcon() {
        HBox stripes = new HBox(0);
        Color[] colors = {
                Color.web("#df3c32"), Color.web("#f19c22"), Color.web("#e8d326"),
                Color.web("#4fa653"), Color.web("#3d80c5"), Color.web("#7651a8")
        };
        for (Color color : colors) {
            Rectangle stripe = new Rectangle(2, 12, color);
            stripes.getChildren().add(stripe);
        }
        StackPane swatch = new StackPane(stripes);
        swatch.getStyleClass().add("color-swatch");
        return swatch;
    }

    private Node colorGridIcon() {
        Pane grid = iconPane(12, 12);
        Color[] colors = {
                Color.web("#e34b42"), Color.web("#f0c52f"), Color.web("#55a65b"),
                Color.web("#4389c8"), Color.web("#7a58ae"), Color.web("#e88a34")
        };
        for (int index = 0; index < colors.length; index++) {
            Rectangle cell = new Rectangle((index % 3) * 4, (index / 3) * 6, 4, 6);
            cell.setFill(colors[index]);
            grid.getChildren().add(cell);
        }
        StackPane swatch = new StackPane(grid);
        swatch.getStyleClass().add("color-swatch");
        return swatch;
    }

    private void keepSelected(ToggleButton selectedButton, Runnable action) {
        if (!selectedButton.isSelected()) selectedButton.setSelected(true);
        action.run();
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
        label.getStyleClass().add("section-label");
        return label;
    }

    private void setColorCountVisible(boolean visible) {
        colorCountRow.setVisible(visible);
        colorCountRow.setManaged(visible);
    }

    private void setWorkStatusVisible(boolean visible) {
        progress.setVisible(visible);
        progress.setManaged(visible);
        status.setVisible(visible);
        status.setManaged(visible);
    }

    @Override
    public void showOriginal(Path path, String name, int position, int total) {
        hasImages = true;
        previews.showOriginal(path, name, position, total);
    }

    @Override
    public void showVector(Path svgPath, ConversionSettings settings) {
        previews.showVector(svgPath, settings);
    }

    @Override
    public void clearVector(String message) {
        previews.clearVector(message);
    }

    @Override
    public void updateNavigation(boolean canGoBack, boolean canGoNext) {
        hasPreviousImage = canGoBack;
        hasNextImage = canGoNext;
        // Back remains available on the first image so the user can return to the drop zone.
        previous.setDisable(!editingEnabled);
        next.setDisable(!editingEnabled || !canGoNext);
    }

    @Override
    public void updateColorLimit(int maximum) {
        maximumColorCount = Math.max(1, Math.min(5, maximum));
        // The detected limit affects this image's displayed value, not the batch's requested value.
        currentColorCount = Math.min(settingsController.settings().getCustomColorCount(), maximumColorCount);
        updateColorCounter();
    }

    @Override
    public void showSettings(ConversionSettings settings) {
        updatingSettings = true;
        try {
            detailGroup.getToggles().forEach(toggle -> toggle.setSelected(toggle.getUserData() == settings.getDetailLevel()));
            colorGroup.getToggles().forEach(toggle -> toggle.setSelected(toggle.getUserData() == settings.getColorMode()));
            removeBackground.setSelected(settings.isRemoveBackground());
            currentColorCount = Math.min(settings.getCustomColorCount(), maximumColorCount);
            updateColorCounter();
            setColorCountVisible(settings.getColorMode() == ConversionSettings.ColorMode.CUSTOM);
        } finally {
            updatingSettings = false;
        }
    }

    @Override
    public void setEditingEnabled(boolean enabled) {
        editingEnabled = enabled;
        configurationPanel.setDisable(!enabled);
        updateNavigation(hasPreviousImage, hasNextImage);
    }

    @Override
    public void setWorking(boolean working, double value, String message) {
        setWorkStatusVisible(working);
        progress.setProgress(value);
        status.setText(message);
        download.setDisable(working || !hasImages);
        exportBundle.setDisable(working || !hasImages);
    }

    @Override
    public void showError(String title, String message) {
        showAlert(Alert.AlertType.ERROR, title, message);
    }

    @Override
    public void showInfo(String title, String message) {
        showAlert(Alert.AlertType.INFORMATION, title, message);
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.show();
    }

    @Override
    public void applyZoom(double zoom) {
        previews.applyZoom(zoom);
    }
}
