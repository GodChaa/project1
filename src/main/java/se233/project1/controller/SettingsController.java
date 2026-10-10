package se233.project1.controller;

import se233.project1.model.ConversionSettings;

/** Updates the settings model from the right-side panel. */
public class SettingsController {
    private final ConversionSettings settings;
    private final Runnable showDropZone;

    public SettingsController(ConversionSettings settings, Runnable showDropZone) {
        this.settings = settings;
        this.showDropZone = showDropZone;
    }

    public ConversionSettings settings() {
        return settings;
    }

    public void selectDetail(ConversionSettings.DetailLevel detailLevel) {
        settings.setDetailLevel(detailLevel);
    }

    public void selectColorMode(ConversionSettings.ColorMode colorMode) {
        settings.setColorMode(colorMode);
    }

    public void setCustomColorCount(int colorCount) {
        settings.setCustomColorCount(colorCount);
    }

    public void setRemoveBackground(boolean selected) {
        settings.setRemoveBackground(selected);
    }

    public void backToDropZone() {
        showDropZone.run();
    }
}
