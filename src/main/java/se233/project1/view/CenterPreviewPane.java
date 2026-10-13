package se233.project1.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import se233.project1.model.ConversionSettings;

import java.nio.file.Path;
public class CenterPreviewPane extends GridPane {
    private final ImageView originalImage = new ImageView();
    private final WebView vectorWebView = new WebView();
    private final StackPane originalCanvas = createCanvas();
    private final StackPane vectorCanvas = createCanvas();
    private final Label originalFooter = new Label("No image loaded");
    private final Label vectorFooter = new Label("Waiting for vector preview");
    private double zoom = 1.0;

    public CenterPreviewPane() {
        getStyleClass().add("comparison-grid");
        setHgap(1);

        ColumnConstraints originalColumn = new ColumnConstraints();
        originalColumn.setPercentWidth(50);
        originalColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints vectorColumn = new ColumnConstraints();
        vectorColumn.setPercentWidth(50);
        vectorColumn.setHgrow(Priority.ALWAYS);
        getColumnConstraints().addAll(originalColumn, vectorColumn);

        originalImage.setPreserveRatio(true);
        originalImage.setSmooth(true);
        originalImage.fitWidthProperty().bind(originalCanvas.widthProperty().subtract(20));
        originalImage.fitHeightProperty().bind(originalCanvas.heightProperty().subtract(20));
        originalCanvas.getChildren().add(originalImage);

        vectorWebView.setContextMenuEnabled(false);
        vectorWebView.setPageFill(Color.TRANSPARENT);
        vectorWebView.setMinSize(0, 0);
        vectorWebView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        vectorWebView.getEngine().getLoadWorker().stateProperty().addListener(
                (observable, previous, state) -> {
                    if (state == Worker.State.SUCCEEDED) fitVectorToViewport();
                });
        clearVector("Generating preview...");

        BorderPane originalPanel = createPreviewPanel("Original Image", originalCanvas, originalFooter);
        BorderPane vectorPanel = createPreviewPanel("Vectorized Result", vectorCanvas, vectorFooter);
        add(originalPanel, 0, 0);
        add(vectorPanel, 1, 0);
        GridPane.setHgrow(originalPanel, Priority.ALWAYS);
        GridPane.setHgrow(vectorPanel, Priority.ALWAYS);
        GridPane.setVgrow(originalPanel, Priority.ALWAYS);
        GridPane.setVgrow(vectorPanel, Priority.ALWAYS);
    }

    public void showOriginal(Path path, String name, int position, int total) {
        Image previousImage = originalImage.getImage();
        if (previousImage != null && previousImage.getProgress() < 1) previousImage.cancel();
        Image image = new Image(path.toUri().toString(), true);
        originalImage.setImage(image);
        String navigation = total > 1 ? "  •  " + position + " / " + total : "";
        originalFooter.setText(name + " — loading..." + navigation);
        Runnable updateFooter = () -> {
            if (originalImage.getImage() != image) return;
            if (image.isError()) {
                originalFooter.setText(name + " — could not load preview" + navigation);
            } else if (image.getProgress() >= 1) {
                originalFooter.setText(name + " (" + (int) image.getWidth() + " × "
                        + (int) image.getHeight() + " px)" + navigation);
            }
        };
        image.progressProperty().addListener((observable, before, after) -> updateFooter.run());
        image.errorProperty().addListener((observable, before, after) -> updateFooter.run());
        updateFooter.run();
    }

    public void showVector(Path svgPath, ConversionSettings settings) {
        vectorCanvas.getChildren().setAll(vectorWebView);
        vectorWebView.getEngine().load(svgPath.toUri().toString());
        String detail = titleCase(settings.getDetailLevel().name());
        String colors = settings.getColorMode() == ConversionSettings.ColorMode.UNLIMITED
                ? "Unlimited colors"
                : settings.getCustomColorCount() + " custom colors";
        String background = settings.isRemoveBackground() ? ", transparent" : "";
        vectorFooter.setText("Anti-aliased artwork, " + detail + ", " + colors + background);
    }

    public void clearVector(String message) {
        vectorWebView.getEngine().getLoadWorker().cancel();
        Label placeholder = new Label(message);
        placeholder.getStyleClass().add("preview-placeholder");
        vectorCanvas.getChildren().setAll(placeholder);
        vectorFooter.setText("Waiting for vector preview");
    }

    public void applyZoom(double zoom) {
        this.zoom = zoom;
        originalImage.setScaleX(zoom);
        originalImage.setScaleY(zoom);
        if (vectorWebView.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED) {
            fitVectorToViewport();
        }
    }

    private void fitVectorToViewport() {
        String scale = Double.toString(zoom);
        vectorWebView.getEngine().executeScript("var svg = document.documentElement;"
                + "svg.setAttribute('width', '100%');"
                + "svg.setAttribute('height', '100%');"
                + "svg.style.width = '100%';"
                + "svg.style.height = '100%';"
                + "svg.style.transformOrigin = 'center center';"
                + "svg.style.transform = 'scale(" + scale + ")';");
    }

    private BorderPane createPreviewPanel(String title, Node canvas, Label footer) {
        Label heading = new Label(title);
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setAlignment(Pos.CENTER);
        heading.getStyleClass().add("preview-title");

        ScrollPane scrollPane = new ScrollPane(canvas);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        scrollPane.getStyleClass().add("preview-scroll");

        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("preview-footer");

        BorderPane panel = new BorderPane(scrollPane, heading, null, footer, null);
        panel.setMinWidth(0);
        panel.getStyleClass().add("preview-panel");
        return panel;
    }

    private StackPane createCanvas() {
        StackPane canvas = new StackPane();
        canvas.setMinSize(260, 360);
        canvas.setPadding(new Insets(10));
        canvas.setBackground(checkerboardBackground());
        return canvas;
    }

    private Background checkerboardBackground() {
        int tileSize = 16;
        WritableImage tile = new WritableImage(tileSize, tileSize);
        var writer = tile.getPixelWriter();
        for (int y = 0; y < tileSize; y++) {
            for (int x = 0; x < tileSize; x++) {
                boolean alternate = (x < tileSize / 2) != (y < tileSize / 2);
                writer.setColor(x, y, alternate ? Color.web("#dddddd") : Color.WHITE);
            }
        }
        BackgroundImage image = new BackgroundImage(
                tile, BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
                BackgroundPosition.DEFAULT,
                new BackgroundSize(tileSize, tileSize, false, false, false, false));
        return new Background(
                java.util.List.of(new BackgroundFill(Color.WHITE, null, null)),
                java.util.List.of(image));
    }

    private String titleCase(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase();
    }
}
