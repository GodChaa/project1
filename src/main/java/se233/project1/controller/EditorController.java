package se233.project1.controller;

/** Holds the UI-only zoom state for the editor preview. */
public class EditorController {
    private static final double ZOOM_STEP = 0.15;
    private double zoom = 1.0;

    public double zoomIn() {
        zoom += ZOOM_STEP;
        return zoom;
    }

    public double zoomOut() {
        zoom = Math.max(ZOOM_STEP, zoom - ZOOM_STEP);
        return zoom;
    }

    public double zoomToFit() {
        zoom = 1.0;
        return zoom;
    }

}
