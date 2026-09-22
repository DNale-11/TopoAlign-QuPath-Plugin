package io.github.dnale11.topoalign;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.prefs.Preferences;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;

/** Modeless registration form. Export and Python execution run off the JavaFX thread. */
final class RegistrationCommand {
    private final QuPathGUI qupath;
    private final Preferences prefs = Preferences.userNodeForPackage(RegistrationCommand.class);
    private final TextField python = new TextField(prefs.get("python", "python"));
    private final TextField source = new TextField(prefs.get("source", ""));
    private final TextField output = new TextField(prefs.get("output", Path.of(System.getProperty("user.home"), "TopoAlign").toString()));
    private final TextField fixed = new TextField(), moving = new TextField();
    private final ComboBox<String> mode = new ComboBox<>(), method = new ComboBox<>();
    private final TextField downsample = new TextField("1"), fixedChannel = new TextField("1"), movingChannel = new TextField("1");
    private final TextField topK = new TextField("50"), featureWeight = new TextField("1.0");
    private final TextField topologyWeight = new TextField("0.35"), positionWeight = new TextField("4.0");
    private final TextField distance = new TextField("2.0"), window = new TextField("100.0");
    private final CheckBox gpu = new CheckBox("Use GPU for Cellpose"), ransac = new CheckBox("RANSAC (rigid only)");
    private final TextArea log = new TextArea();
    private final Label status = new Label("Ready. Transform direction: moving → fixed.");
    private final Button run = new Button("Run registration"), cancel = new Button("Cancel");
    private final Button openRegistered = new Button("Open registered image"), openOverlay = new Button("Open overlay");
    private final Button openFolder = new Button("Open run folder");
    private Stage stage;
    private PythonRunner runner;
    private Task<JsonObject> task;
    private Path runDir;
    private JsonObject result;

    RegistrationCommand(QuPathGUI qupath) { this.qupath = qupath; }

    void show() {
        if (stage == null) build();
        stage.show();
        stage.toFront();
    }

    private void build() {
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle("TopoAlign · image registration");
        mode.getItems().addAll("Images (Cellpose segmentation)", "Label masks (skip segmentation)");
        mode.getSelectionModel().selectFirst();
        method.getItems().addAll("rigid", "similarity", "affine");
        method.getSelectionModel().selectFirst();
        source.setPromptText("Optional: folder containing cell_registration/service.py");
        var form = new GridPane();
        form.setHgap(10); form.setVgap(8);
        form.getColumnConstraints().add(new ColumnConstraints(170));
        var flexible = new ColumnConstraints(); flexible.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().add(flexible);
        addRow(form, 0, "Python executable", chooser(python, false));
        addRow(form, 1, "TopoAlign source folder", chooser(source, true));
        addRow(form, 2, "Input mode", mode);
        var useCurrent = new Button("Use current image as fixed");
        useCurrent.setOnAction(e -> useCurrent());
        addRow(form, 3, "Fixed / reference", chooser(fixed, false));
        addRow(form, 4, "", useCurrent);
        addRow(form, 5, "Moving / source", chooser(moving, false));
        addRow(form, 6, "Output parent folder", chooser(output, true));
        addRow(form, 7, "Downsample (≥ 1)", downsample);
        addRow(form, 8, "Fixed channel (1-based)", fixedChannel);
        addRow(form, 9, "Moving channel (1-based)", movingChannel);
        addRow(form, 10, "Transform", new HBox(12, method, ransac));
        addRow(form, 11, "Segmentation", gpu);
        var matching = new GridPane(); matching.setHgap(10); matching.setVgap(6);
        addRow(matching, 0, "Top K", topK);
        addRow(matching, 1, "Feature weight", featureWeight);
        addRow(matching, 2, "Topology weight", topologyWeight);
        addRow(matching, 3, "Position weight", positionWeight);
        addRow(matching, 4, "Distance threshold", distance);
        addRow(matching, 5, "Spatial window (export px)", window);
        var advanced = new TitledPane("Matching parameters", matching); advanced.setExpanded(false);
        var explanation = new Label("Images: exports one intensity channel, Z=0 / T=0. Increase downsample for large slides.\n"
                + "Masks: 2D integer TIFFs, one positive label per cell; coordinates refer to the supplied masks.");
        explanation.setWrapText(true);
        var inputs = new VBox(10, form, advanced, explanation);
        mode.valueProperty().addListener((obs, old, value) -> {
            boolean masks = mode.getSelectionModel().getSelectedIndex() == 1;
            downsample.setDisable(masks); fixedChannel.setDisable(masks); movingChannel.setDisable(masks); gpu.setDisable(masks);
        });
        method.valueProperty().addListener((obs, old, value) -> {
            ransac.setDisable(!"rigid".equals(value));
            if (ransac.isDisabled()) ransac.setSelected(false);
        });
        log.setEditable(false); log.setWrapText(true); log.setPrefRowCount(9);
        status.setWrapText(true); status.setMaxWidth(780);
        cancel.setDisable(true); openRegistered.setDisable(true); openOverlay.setDisable(true); openFolder.setDisable(true);
        run.setOnAction(e -> start(inputs));
        cancel.setOnAction(e -> { if (runner != null) { runner.cancel(); status.setText("Cancelling…"); cancel.setDisable(true); } });
        openRegistered.setOnAction(e -> openArtifact("registered_moving"));
        openOverlay.setOnAction(e -> openArtifact("overlay"));
        openFolder.setOnAction(e -> {
            try { if (runDir != null) java.awt.Desktop.getDesktop().open(runDir.toFile()); }
            catch (IOException | UnsupportedOperationException ex) { error("Run folder: " + runDir + "\n" + ex.getMessage()); }
        });
        var root = new VBox(12, inputs, new HBox(10, run, cancel, openFolder), status, log,
                new HBox(10, openRegistered, openOverlay));
        root.setPadding(new Insets(16));
        var scroll = new ScrollPane(root); scroll.setFitToWidth(true);
        stage.setScene(new Scene(scroll, 840, 850));
        // Hiding the window keeps its task and cancellation controls accessible on reopen.
        stage.setOnCloseRequest(e -> { e.consume(); stage.hide(); });
    }

    private static void addRow(GridPane grid, int row, String label, javafx.scene.Node node) {
        grid.add(new Label(label), 0, row); grid.add(node, 1, row); GridPane.setHgrow(node, Priority.ALWAYS);
    }

    private HBox chooser(TextField field, boolean directory) {
        var browse = new Button("Browse…");
        browse.setOnAction(e -> {
            java.io.File chosen;
            if (directory) { var dialog = new DirectoryChooser(); chosen = dialog.showDialog(stage); }
            else { var dialog = new FileChooser(); chosen = dialog.showOpenDialog(stage); }
            if (chosen != null) field.setText(chosen.getAbsolutePath());
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        return new HBox(8, field, browse);
    }

    private void useCurrent() {
        var data = qupath.getImageData();
        if (data == null) { error("Open an image in QuPath first."); return; }
        var uris = data.getServer().getURIs();
        if (uris.size() != 1 || !"file".equalsIgnoreCase(uris.iterator().next().getScheme())) {
            error("Select a local image file. Composite and remote servers are not supported in this version."); return;
        }
        fixed.setText(Path.of(uris.iterator().next()).toString());
    }

    private void start(VBox inputs) {
        try {
            if (python.getText().isBlank()) throw new IllegalArgumentException("Select a Python executable.");
            Path fixedPath = inputPath(fixed), movingPath = inputPath(moving);
            boolean masks = mode.getSelectionModel().getSelectedIndex() == 1;
            double scale = masks ? 1 : number(downsample, 1, "Downsample");
            int fc = masks ? 1 : integer(fixedChannel, "Fixed channel"), mc = masks ? 1 : integer(movingChannel, "Moving channel");
            var matching = new LinkedHashMap<String, Object>();
            matching.put("top_k", integer(topK, "Top K"));
            matching.put("feature_weight", number(featureWeight, 0, "Feature weight"));
            matching.put("topology_weight", number(topologyWeight, 0, "Topology weight"));
            matching.put("position_weight", number(positionWeight, 0, "Position weight"));
            matching.put("distance_threshold", number(distance, 0.000001, "Distance threshold"));
            matching.put("spatial_window_size", number(window, 0.000001, "Spatial window"));
            String executable = python.getText().strip(), sourceRoot = source.getText().strip();
            if (!sourceRoot.isEmpty() && !Files.isRegularFile(Path.of(sourceRoot, "cell_registration", "service.py")))
                throw new IllegalArgumentException("Source folder must contain cell_registration/service.py.");
            if (output.getText().isBlank()) throw new IllegalArgumentException("Select an output parent folder.");
            Path parent = Path.of(output.getText().strip()).toAbsolutePath(); Files.createDirectories(parent);
            runDir = Files.createTempDirectory(parent, "topoalign-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-");
            Path currentDir = runDir, logPath = currentDir.resolve("run.log");
            var request = new LinkedHashMap<String, Object>();
            request.put("mode", masks ? "mask" : "image"); request.put("method", method.getValue());
            request.put("gpu", !masks && gpu.isSelected()); request.put("use_ransac", ransac.isSelected());
            request.put("matching", matching); request.put("output_dir", currentDir.resolve("results").toString());
            request.put("fixed_downsample", scale); request.put("moving_downsample", scale);
            request.put("original_fixed", fixedPath.toString()); request.put("original_moving", movingPath.toString());
            request.put("fixed_channel", fc); request.put("moving_channel", mc);
            prefs.put("python", executable); prefs.put("source", sourceRoot); prefs.put("output", parent.toString());
            runner = new PythonRunner(); var currentRunner = runner;
            result = null; log.clear(); openRegistered.setDisable(true); openOverlay.setDisable(true);
            inputs.setDisable(true); run.setDisable(true); cancel.setDisable(false); openFolder.setDisable(false);
            task = new Task<>() {
                @Override protected JsonObject call() throws Exception {
                    Files.writeString(logPath, "Input fixed: " + fixedPath + "\nInput moving: " + movingPath + "\n");
                    Path fixedInput = fixedPath, movingInput = movingPath;
                    if (!masks) {
                        updateMessage("Exporting fixed image…"); currentRunner.checkCancelled();
                        fixedInput = currentDir.resolve("fixed.tif");
                        ImageExport.export(fixedPath, fixedInput, scale, fc);
                        updateMessage("Exporting moving image…"); currentRunner.checkCancelled();
                        movingInput = currentDir.resolve("moving.tif");
                        ImageExport.export(movingPath, movingInput, scale, mc);
                    }
                    currentRunner.checkCancelled();
                    request.put("fixed", fixedInput.toString()); request.put("moving", movingInput.toString());
                    Path requestPath = currentDir.resolve("request.json"), script = currentDir.resolve("topoalign_bridge.py");
                    Files.writeString(requestPath, new GsonBuilder().setPrettyPrinting().create().toJson(request));
                    try (var stream = RegistrationCommand.class.getResourceAsStream("/python/topoalign_bridge.py")) {
                        if (stream == null) throw new IOException("Python bridge resource is missing from the JAR");
                        Files.copy(stream, script);
                    }
                    updateMessage("Running TopoAlign… (see log below)");
                    currentRunner.run(executable, script, sourceRoot, requestPath, logPath);
                    var payload = JsonParser.parseString(Files.readString(currentDir.resolve("results/result.json"))).getAsJsonObject();
                    if (!"completed".equals(payload.get("status").getAsString())) throw new IOException("Registration did not complete; see result.json");
                    return payload;
                }
            };
            status.textProperty().bind(task.messageProperty());
            var timer = new Timeline(new KeyFrame(Duration.millis(700), e -> refreshLog(logPath)));
            timer.setCycleCount(Timeline.INDEFINITE); timer.play();
            task.setOnSucceeded(e -> {
                finish(inputs, timer, logPath); result = task.getValue();
                var d = result.getAsJsonObject("diagnostics");
                status.setText("Completed · matches: " + d.get("match_count") + " · mean residual (export px): "
                        + d.get("residual_mean_px") + " · overlap: " + d.get("valid_overlap_fraction"));
                log.appendText("\nWarnings: " + result.get("warnings") + "\nResults: " + currentDir.resolve("results"));
                openRegistered.setDisable(!hasArtifact("registered_moving")); openOverlay.setDisable(!hasArtifact("overlay"));
            });
            task.setOnFailed(e -> {
                finish(inputs, timer, logPath);
                var ex = task.getException();
                status.setText(ex instanceof CancellationException ? "Cancelled. Partial files are retained in the run folder."
                        : "Failed: " + String.valueOf(ex.getMessage()).lines().findFirst().orElse("See log."));
                log.appendText("\n" + ex);
            });
            var thread = new Thread(task, "topoalign-registration"); thread.setDaemon(true); thread.start();
        } catch (Exception ex) { error(ex.getMessage()); }
    }

    private void finish(VBox inputs, Timeline timer, Path logPath) {
        timer.stop(); status.textProperty().unbind(); refreshLog(logPath);
        inputs.setDisable(false); run.setDisable(false); cancel.setDisable(true);
    }

    private void refreshLog(Path path) {
        try { String text = PythonRunner.tail(path); if (!text.equals(log.getText())) { log.setText(text); log.positionCaret(text.length()); } }
        catch (IOException ignored) { /* Writer may be opening the log; retry next tick. */ }
    }

    private boolean hasArtifact(String key) { return result.getAsJsonObject("artifacts").has(key); }

    private void openArtifact(String key) {
        if (result == null || !hasArtifact(key)) return;
        try { qupath.openImage(qupath.getViewer(), result.getAsJsonObject("artifacts").get(key).getAsString(), true, true); }
        catch (IOException ex) { error(ex.getMessage()); }
    }

    private static Path inputPath(TextField field) {
        Path path = Path.of(field.getText().strip()).toAbsolutePath();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Input file does not exist: " + path);
        return path;
    }

    private static double number(TextField field, double minimum, String label) {
        double n;
        try { n = Double.parseDouble(field.getText().strip()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " must be numeric."); }
        if (!Double.isFinite(n) || n < minimum) throw new IllegalArgumentException(label + " must be finite and ≥ " + minimum);
        return n;
    }

    private static int integer(TextField field, String label) {
        double n = number(field, 1, label);
        if (n != Math.rint(n) || n > Integer.MAX_VALUE) throw new IllegalArgumentException(label + " must be a positive integer.");
        return (int) n;
    }

    private void error(String message) {
        var alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK); alert.initOwner(stage);
        alert.setHeaderText("TopoAlign"); alert.showAndWait();
    }
}
