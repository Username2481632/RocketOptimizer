package com.rocket;

import com.google.inject.Guice;
import com.google.inject.Injector;
import net.sf.openrocket.startup.Application;
import net.sf.openrocket.startup.GuiModule;
import net.sf.openrocket.plugin.PluginModule;
import java.util.Properties;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;

public class RocketOptimizerGUI extends JFrame {
    private JTextField filePathField;
    private JTextField targetApogeeField;
    private JTextField stabilityMinField;
    private JTextField stabilityMaxField;
    private JTextField durationMinField;
    private JTextField durationMaxField;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JButton startButton;
    private JButton saveInPlaceButton;
    private JButton saveAsButton;
    private JProgressBar progressBar;
    private JLabel totalScoreLabel;
    private RocketOptimizer optimizer;
    private static final String CONFIG_FILE = System.getProperty("user.home") + "/.rocketoptimizer.properties";
    private Map<String, Boolean> enabledParams = new HashMap<>();
    private JLabel apogeeLabel;
    private JLabel durationLabel;

    public RocketOptimizerGUI() {
        super("Rocket Optimizer GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        initComponents();
        loadSettings();
        saveInPlaceButton.setEnabled(false);
        saveAsButton.setEnabled(false);
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel inputPanel = new JPanel(new GridLayout(0, 2, 5, 5));

        filePathField = new JTextField();
        filePathField.setToolTipText("Original OpenRocket design file");
        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(new BrowseAction());
        inputPanel.add(new JLabel("ORK File:"));
        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.add(filePathField, BorderLayout.CENTER);
        filePanel.add(browseButton, BorderLayout.EAST);
        inputPanel.add(filePanel);

        targetApogeeField = new JTextField();
        targetApogeeField.setToolTipText("Desired maximum altitude in meters");
        inputPanel.add(new JLabel("Target Apogee (m):"));
        inputPanel.add(targetApogeeField);

        stabilityMinField = new JTextField();
        stabilityMaxField = new JTextField();
        stabilityMinField.setToolTipText("Minimum stability margin in calibers");
        stabilityMaxField.setToolTipText("Maximum stability margin in calibers");
        inputPanel.add(new JLabel("Stability Min (cal):"));
        inputPanel.add(stabilityMinField);
        inputPanel.add(new JLabel("Stability Max (cal):"));
        inputPanel.add(stabilityMaxField);

        durationMinField = new JTextField();
        durationMaxField = new JTextField();
        durationMinField.setToolTipText("Minimum duration in seconds (perfect score range)");
        durationMaxField.setToolTipText("Maximum duration in seconds (perfect score range)");
        inputPanel.add(new JLabel("Duration Min (s):"));
        inputPanel.add(durationMinField);
        inputPanel.add(new JLabel("Duration Max (s):"));
        inputPanel.add(durationMaxField);

        mainPanel.add(inputPanel, BorderLayout.NORTH);

        String[] columnNames = {"Enabled", "Parameter", "Value"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };

        Object[][] parameterData = {
                {true, "Altitude Score", ""},
                {true, "Duration Score", ""},
                {true, "Fin Thickness", ""},
                {true, "Root Chord", ""},
                {true, "Fin Height", ""},
                {true, "Number of Fins", ""},
                {true, "Nose Cone Length", ""}
        };

        for (Object[] row : parameterData) {
            tableModel.addRow(row);
            enabledParams.put((String) row[1], (Boolean) row[0]);
        }

        // Make sure Total Score is enabled by default
        enabledParams.put("Total Score", true);

        resultsTable = new JTable(tableModel);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(60);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(100);

        resultsTable.getModel().addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                String param = (String) tableModel.getValueAt(e.getFirstRow(), 1);
                boolean enabled = (Boolean) tableModel.getValueAt(e.getFirstRow(), 0);
                enabledParams.put(param, enabled);
            }
        });

        JScrollPane tableScroll = new JScrollPane(resultsTable);

        // Create a panel for the metrics display
        JPanel metricsPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        metricsPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

        // Apogee display
        JPanel apogeePanel = new JPanel(new BorderLayout());
        JLabel apogeeTitleLabel = new JLabel("Apogee:");
        apogeeTitleLabel.setFont(new Font(apogeeTitleLabel.getFont().getName(), Font.BOLD, 16));
        apogeeLabel = new JLabel("--");
        apogeeLabel.setFont(new Font(apogeeLabel.getFont().getName(), Font.BOLD, 20));
        apogeeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        apogeePanel.add(apogeeTitleLabel, BorderLayout.WEST);
        apogeePanel.add(apogeeLabel, BorderLayout.CENTER);

        // Duration display
        JPanel durationPanel = new JPanel(new BorderLayout());
        JLabel durationTitleLabel = new JLabel("Duration:");
        durationTitleLabel.setFont(new Font(durationTitleLabel.getFont().getName(), Font.BOLD, 16));
        durationLabel = new JLabel("--");
        durationLabel.setFont(new Font(durationLabel.getFont().getName(), Font.BOLD, 20));
        durationLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        durationPanel.add(durationTitleLabel, BorderLayout.WEST);
        durationPanel.add(durationLabel, BorderLayout.CENTER);

        // Total Score display
        JPanel totalScorePanel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("Total Score:");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 16));
        totalScoreLabel = new JLabel("--");
        totalScoreLabel.setFont(new Font(totalScoreLabel.getFont().getName(), Font.BOLD, 20));
        totalScoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        totalScorePanel.add(titleLabel, BorderLayout.WEST);
        totalScorePanel.add(totalScoreLabel, BorderLayout.CENTER);

        metricsPanel.add(apogeePanel);
        metricsPanel.add(durationPanel);
        metricsPanel.add(totalScorePanel);

        // Create a panel to hold the table and metrics
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(tableScroll, BorderLayout.CENTER);
        centerPanel.add(metricsPanel, BorderLayout.SOUTH);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        JPanel southContainer = new JPanel(new BorderLayout());

        progressBar = new JProgressBar();
        progressBar.setForeground(new Color(40, 180, 40));
        progressBar.setStringPainted(true);
        southContainer.add(progressBar, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        startButton = new JButton("Start Optimization");
        saveInPlaceButton = new JButton("Save in Place");
        saveAsButton = new JButton("Save As...");
        startButton.addActionListener(new StartOptimizationAction());
        saveInPlaceButton.addActionListener(e -> saveOptimizedDesign(true));
        saveAsButton.addActionListener(e -> saveOptimizedDesign(false));
        buttonPanel.add(startButton);
        buttonPanel.add(saveInPlaceButton);
        buttonPanel.add(saveAsButton);
        southContainer.add(buttonPanel, BorderLayout.SOUTH);

        mainPanel.add(southContainer, BorderLayout.SOUTH);
        add(mainPanel);

        addDocumentListener(filePathField);
        addDocumentListener(targetApogeeField);
        addDocumentListener(stabilityMinField);
        addDocumentListener(stabilityMaxField);
        addDocumentListener(durationMinField);
        addDocumentListener(durationMaxField);
    }

    private void saveOptimizedDesign(boolean saveInPlace) {
        if (optimizer == null || !optimizer.hasBestValues()) {
            JOptionPane.showMessageDialog(this, "No optimized design available.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File file;
        if (saveInPlace) {
            // Save in place - use the original file path
            file = new File(filePathField.getText());
            if (!file.exists()) {
                JOptionPane.showMessageDialog(this, "Original file no longer exists.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            // Save As - show file chooser
            JFileChooser saver = new JFileChooser();
            saver.setFileFilter(new FileNameExtensionFilter("OpenRocket Files", "ork"));
            if (saver.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            file = saver.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".ork")) {
                file = new File(file.getParentFile(), file.getName() + ".ork");
            }
        }

        try {
            optimizer.saveOptimizedDesign(file);
            JOptionPane.showMessageDialog(this, "Design saved successfully!");
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Save failed:\n" + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void addDocumentListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { saveSettings(); }
            public void removeUpdate(DocumentEvent e) { saveSettings(); }
            public void changedUpdate(DocumentEvent e) { saveSettings(); }
        });
    }

    private void loadSettings() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(CONFIG_FILE)) {
            props.load(in);
            filePathField.setText(props.getProperty("orkPath", ""));
            targetApogeeField.setText(props.getProperty("targetApogee", "241.0"));
            stabilityMinField.setText(props.getProperty("stabilityMin", "1.0"));
            stabilityMaxField.setText(props.getProperty("stabilityMax", "2.0"));
            durationMinField.setText(props.getProperty("durationMin", "41.0"));
            durationMaxField.setText(props.getProperty("durationMax", "44.0"));
        } catch (IOException ignored) {}
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("orkPath", filePathField.getText());
        props.setProperty("targetApogee", targetApogeeField.getText());
        props.setProperty("stabilityMin", stabilityMinField.getText());
        props.setProperty("stabilityMax", stabilityMaxField.getText());
        props.setProperty("durationMin", durationMinField.getText());
        props.setProperty("durationMax", durationMaxField.getText());
        try (OutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "RocketOptimizer Settings");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to save settings: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateResultsTable(Map<String, Double> results) {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String paramName = (String) tableModel.getValueAt(row, 1);
            switch (paramName) {
                case "Altitude Score":
                    updateCell(row, results.get("altitudeScore"), "%.1f");
                    break;
                case "Duration Score":
                    updateCell(row, results.get("durationScore"), "%.2f");
                    break;
                case "Fin Thickness":
                    updateCell(row, results.get("thickness"), "%.2f");
                    break;
                case "Root Chord":
                    updateCell(row, results.get("rootChord"), "%.2f");
                    break;
                case "Fin Height":
                    updateCell(row, results.get("height"), "%.2f");
                    break;
                case "Number of Fins":
                    updateCell(row, results.get("finCount"), "%.0f");
                    break;
            }
        }

        // Update total score separately
        if (results.containsKey("totalScore")) {
            totalScoreLabel.setText(String.format("%.2f", results.get("totalScore")));
        } else {
            totalScoreLabel.setText("--");
        }
    }

    private void updateCell(int row, Double value, String format) {
        if (value != null) {
            tableModel.setValueAt(String.format(format, value), row, 2);
        } else {
            tableModel.setValueAt("", row, 2);
        }
    }

    private void updateCurrentParameters(Map<String, Double> currentParams) {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String paramName = (String) tableModel.getValueAt(row, 1);
            String key = mapParamNameToKey(paramName);
            if (currentParams.containsKey(key)) {
                Double value = currentParams.get(key);
                String format = getFormatForParameter(paramName);
                tableModel.setValueAt(value != null ? String.format(format, value) : "", row, 2);
            }
        }

        // Update metrics display
        if (currentParams.containsKey("apogee")) {
            apogeeLabel.setText(String.format("%.1f m", currentParams.get("apogee")));
        }
        if (currentParams.containsKey("duration")) {
            durationLabel.setText(String.format("%.2f s", currentParams.get("duration")));
        }
        if (currentParams.containsKey("totalScore")) {
            totalScoreLabel.setText(String.format("%.2f", currentParams.get("totalScore")));
        }
    }

    private String getFormatForParameter(String paramName) {
        switch (paramName) {
            case "Altitude Score":
                return "%.1f";
            case "Duration Score":
                return "%.2f";
            case "Fin Thickness":
            case "Root Chord":
            case "Fin Height":
            case "Nose Cone Length":
                return "%.2f";
            case "Number of Fins":
                return "%.0f";
            default:
                return "%.2f";
        }
    }

    private String mapParamNameToKey(String displayName) {
        switch (displayName) {
            case "Fin Thickness": return "thickness";
            case "Root Chord": return "rootChord";
            case "Fin Height": return "height";
            case "Number of Fins": return "finCount";
            case "Altitude Score": return "altitudeScore";
            case "Duration Score": return "durationScore";
            case "Apogee": return "apogee";
            case "Duration": return "duration";
            case "Total Score": return "totalScore";
            case "Nose Cone Length": return "noseLength";
            default: return displayName.toLowerCase().replace(" ", "");
        }
    }

    private class BrowseAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("OpenRocket Files", "ork"));
            int result = fileChooser.showOpenDialog(RocketOptimizerGUI.this);
            if (result == JFileChooser.APPROVE_OPTION) {
                filePathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }
        }
    }

    private class StartOptimizationAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            String orkPath = filePathField.getText();
            if (orkPath.isEmpty()) {
                JOptionPane.showMessageDialog(RocketOptimizerGUI.this, "Please select an ORK file.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            double targetApogee;
            try {
                targetApogee = Double.parseDouble(targetApogeeField.getText());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(RocketOptimizerGUI.this, "Invalid target apogee.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            double stabilityMin, stabilityMax;
            try {
                stabilityMin = Double.parseDouble(stabilityMinField.getText());
                stabilityMax = Double.parseDouble(stabilityMaxField.getText());
                if (stabilityMin >= stabilityMax) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(RocketOptimizerGUI.this, "Invalid stability range.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            double durationMin, durationMax;
            try {
                durationMin = Double.parseDouble(durationMinField.getText());
                durationMax = Double.parseDouble(durationMaxField.getText());
                if (durationMin >= durationMax) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(RocketOptimizerGUI.this, "Invalid duration range.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            startButton.setEnabled(false);
            saveInPlaceButton.setEnabled(false);
            saveAsButton.setEnabled(false);
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                tableModel.setValueAt("", i, 2);
            }
            totalScoreLabel.setText("--");
            progressBar.setValue(0);

            optimizer = new RocketOptimizer();
            optimizer.getConfig().orkPath = orkPath;
            optimizer.getConfig().targetApogee = targetApogee;
            optimizer.getConfig().stabilityRange = new double[]{stabilityMin, stabilityMax};
            optimizer.getConfig().durationRange = new double[]{durationMin, durationMax};
            optimizer.getConfig().enabledParams = new HashMap<>(enabledParams);

            SwingWorker<Void, String> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    try {
                        optimizer.setLogListener(message -> {
                            if (message.startsWith("=== Optimization Complete ===")) {
                                publish("RESULTS");
                            } else if (message.startsWith("INTERIM:")) {
                                publish(message);
                            }
                        });
                        optimizer.setStatusListener(status -> publish("STATUS:" + status));
                        optimizer.setProgressListener((currentPhase, currentEval, totalEval, totalPhases) ->
                                publish("PROGRESS:" + currentPhase + ":" + currentEval + ":" + totalEval + ":" + totalPhases));

                        optimizer.initialize();
                        optimizer.optimizeFins();
                    } catch (Exception ex) {
                        publish("ERROR:" + ex.getMessage());
                    }
                    return null;
                }

                @Override
                protected void process(List<String> chunks) {
                    for (String chunk : chunks) {
                        if (chunk.equals("RESULTS") && optimizer.hasBestValues()) {
                            updateResultsTable(optimizer.getBestValues());
                        } else if (chunk.startsWith("INTERIM:")) {
                            String data = chunk.substring(8);
                            Map<String, Double> currentParams = new HashMap<>();
                            for (String pair : data.split("\\|")) {
                                String[] kv = pair.split("=");
                                if (kv.length == 2) {
                                    try {
                                        currentParams.put(kv[0], Double.parseDouble(kv[1]));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            updateCurrentParameters(currentParams);
                        } else if (chunk.startsWith("STATUS:")) {
                            String status = chunk.substring(7);
                            Map<String, Double> currentParams = new HashMap<>();
                            String[] parts = status.split(" ");

                            // Extract individual param values
                            for (String part : parts) {
                                String[] keyVal = part.split("=");
                                if (keyVal.length == 2) {
                                    String paramName = keyVal[0];
                                    try {
                                        double value = Double.parseDouble(keyVal[1]);
                                        currentParams.put(paramName, value);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            updateCurrentParameters(currentParams);
                        } else if (chunk.startsWith("PROGRESS:")) {
                            String[] parts = chunk.substring(9).split(":");
                            int currentPhase = Integer.parseInt(parts[0]);
                            int currentEval = Integer.parseInt(parts[1]);
                            int totalEval = Integer.parseInt(parts[2]);
                            int totalPhases = Integer.parseInt(parts[3]);

                            double phaseProgress = (double) currentEval / totalEval * 100;
                            String text = String.format("Phase %d/%d: %.1f%%", currentPhase + 1, totalPhases, phaseProgress);
                            progressBar.setString(text);
                            progressBar.setValue((int) phaseProgress);
                        } else if (chunk.startsWith("ERROR:")) {
                            JOptionPane.showMessageDialog(RocketOptimizerGUI.this,
                                    "Optimization error: " + chunk.substring(6),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }

                @Override
                protected void done() {
                    startButton.setEnabled(true);
                    boolean hasBest = optimizer != null && optimizer.hasBestValues();
                    saveInPlaceButton.setEnabled(hasBest);
                    saveAsButton.setEnabled(hasBest);
                    if (hasBest) {
                        updateResultsTable(optimizer.getBestValues());
                    }
                    progressBar.setValue(100);
                    progressBar.setString("Optimization Complete");
                }
            };
            worker.execute();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                GuiModule guiModule = new GuiModule();
                Injector injector = Guice.createInjector(guiModule, new PluginModule());
                Application.setInjector(injector);
                guiModule.startLoader();

                RocketOptimizerGUI gui = new RocketOptimizerGUI();
                gui.setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Initialization failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}