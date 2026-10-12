package se233.project1.controller;

import se233.project1.model.ConversionSettings;

/** Updates the settings model from the right-side panel. */
public class SettingsController {
    private final ConversionSettings settings;
    private final Runnable showDropZone;
    private final Runnable settingsChanged;

    public SettingsController(ConversionSettings settings, Runnable showDropZone, Runnable settingsChanged) {
        this.settings = settings;
        this.showDropZone = showDropZone;
        this.settingsChanged = settingsChanged;
    }

    public ConversionSettings settings() {
        return settings;
    }

    public void selectDetail(ConversionSettings.DetailLevel detailLevel) {
        settings.setDetailLevel(detailLevel);
        settingsChanged.run();
    }

    public void selectColorMode(ConversionSettings.ColorMode colorMode) {
        settings.setColorMode(colorMode);
        settingsChanged.run();
    }

    public void setCustomColorCount(int colorCount) {
        settings.setCustomColorCount(colorCount);
        settingsChanged.run();
    }

    public void setRemoveBackground(boolean selected) {
        settings.setRemoveBackground(selected);
        settingsChanged.run();
    }

    public void backToDropZone() {
        showDropZone.run();
    }
}
