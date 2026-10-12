package se233.project1.model;

/** All editor options in one model class. */
public class ConversionSettings {
    public enum DetailLevel { LOW, MEDIUM, HIGH }
    public enum ColorMode { UNLIMITED, CUSTOM }

    private DetailLevel detailLevel = DetailLevel.MEDIUM;
    private ColorMode colorMode = ColorMode.CUSTOM;
    private int customColorCount = 2;
    private boolean removeBackground;

    public void reset() {
        detailLevel = DetailLevel.MEDIUM;
        colorMode = ColorMode.CUSTOM;
        customColorCount = 2;
        removeBackground = false;
    }

    public DetailLevel getDetailLevel() {
        return detailLevel;
    }

    public void setDetailLevel(DetailLevel detailLevel) {
        this.detailLevel = detailLevel;
    }

    public ColorMode getColorMode() {
        return colorMode;
    }

    public void setColorMode(ColorMode colorMode) {
        this.colorMode = colorMode;
    }

    public int getCustomColorCount() {
        return customColorCount;
    }

    public void setCustomColorCount(int customColorCount) {
        if (customColorCount < 1 || customColorCount > 5) {
            throw new IllegalArgumentException("Custom colors must be between 1 and 5.");
        }
        this.customColorCount = customColorCount;
    }

    public boolean isRemoveBackground() {
        return removeBackground;
    }

    public void setRemoveBackground(boolean removeBackground) {
        this.removeBackground = removeBackground;
    }

    public ConversionSettings copy() {
        ConversionSettings copy = new ConversionSettings();
        copy.detailLevel = detailLevel;
        copy.colorMode = colorMode;
        copy.customColorCount = customColorCount;
        copy.removeBackground = removeBackground;
        return copy;
    }

    public String signature() {
        return detailLevel + "-" + colorMode + "-"
                + (colorMode == ColorMode.CUSTOM ? customColorCount : "all") + "-" + removeBackground;
    }
}
