module se233.project1 {
    requires javafx.controls;
    requires javafx.fxml;


    opens se233.project1 to javafx.fxml;
    exports se233.project1;
}