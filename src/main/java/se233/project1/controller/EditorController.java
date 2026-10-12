package se233.project1.controller;

import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import se233.project1.model.ConversionSettings;
import se233.project1.model.ImageProject;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class EditorController {

    private static final double ZOOM_STEP = 0.15;
    private double zoom = 1.0;

    private List<ImageProject> projects;
    private ConversionSettings currentSettings;

    public EditorController() {
        this.currentSettings = new ConversionSettings(); // กำหนดค่าเริ่มต้น
    }

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

    // เมธอดกำหนดรายการโปรเจกต์ (เรียกใช้เมื่อมีการลากไฟล์หรือเปิดไฟล์ .zip)
    public void setProjects(List<ImageProject> projects) {
        this.projects = projects;
    }

    public ConversionSettings getSettings() {
        return currentSettings;
    }

    // เมธอดรองรับปุ่ม Export All / Download Result
    public void handleExportAll(Window ownerWindow) {
        if (projects == null || projects.isEmpty()) {
            return;
        }

        // 1. เปิดหน้าต่างเลือกโฟลเดอร์ปลายทางผ่าน OS file browser[cite: 3]
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Output Folder for SVG Export");
        File selectedDirectory = directoryChooser.showDialog(ownerWindow);

        if (selectedDirectory != null) {
            String outputPath = selectedDirectory.getAbsolutePath();

            // 2. ตรวจสอบเงื่อนไข Constraint-triggered parallel processing:
            // หลายไฟล์ + Medium Detail + Custom Colors[cite: 3]
            boolean isMultipleFiles = projects.size() > 1;
            boolean isMediumDetail = currentSettings.getDetailLevel() == ConversionSettings.DetailLevel.MEDIUM;
            boolean isCustomColors = currentSettings.getColorMode() == ConversionSettings.ColorMode.CUSTOM;

            boolean triggerParallel = isMultipleFiles && isMediumDetail && isCustomColors;

            if (triggerParallel) {
                processFilesInParallel(outputPath);
            } else {
                processFilesSequentially(outputPath);
            }
        }
    }

    private void processFilesInParallel(String outputPath) {
        int coreCount = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(projects.size(), coreCount));

        for (ImageProject project : projects) {
            executor.submit(() -> {
                try {
                    convertAndSaveSvg(project, outputPath);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processFilesSequentially(String outputPath) {
        for (ImageProject project : projects) {
            try {
                convertAndSaveSvg(project, outputPath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void convertAndSaveSvg(ImageProject project, String outputPath) {
        String sourceFilePath = project.getFilePath();
        File sourceFile = new File(sourceFilePath);
        String fileName = sourceFile.getName();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

        File targetFile = new File(outputPath + File.separator + baseName + ".svg");

        // TODO: เชื่อมต่อ Potrace / AutoTrace wrapper เพื่อแปลงไฟล์และบันทึกลง targetFile[cite: 3]
    }
}