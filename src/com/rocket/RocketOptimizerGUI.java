package com.rocket;

import com.google.inject.Guice;
import com.google.inject.Injector;
import net.sf.openrocket.startup.Application;
import net.sf.openrocket.startup.GuiModule;
import net.sf.openrocket.plugin.PluginModule;
import java.util.Properties;
import java.util.List;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
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
    private JTextArea logArea;
    private JTextArea statusArea;
    private JButton startButton;
    private JButton saveButton;
    private JProgressBar progressBar;
    private RocketOptimizer optimizer;
    private static final String CONFIG_FILE = System.getProperty("user.home") + "/.rocketoptimizer.properties";

    public RocketOptimizerGUI() {
        super("Rocket Optimizer GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        initComponents();
        loadSettings();
        saveButton.setEnabled(false);
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Input panel
        JPanel inputPanel = new JPanel(new GridLayout(0, 2, 5, 5));

        // File selection
        filePathField = new JTextField();
        filePathField.setToolTipText("Original OpenRocket design file");
        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(new BrowseAction());
        inputPanel.add(new JLabel("ORK File:"));
        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.add(filePathField, BorderLayout.CENTER);
        filePanel.add(browseButton, BorderLayout.EAST);
        inputPanel.add(filePanel);

        // Target apogee
        targetApogeeField = new JTextField();
        targetApogeeField.setToolTipText("Desired maximum altitude in meters");
        inputPanel.add(new JLabel("Target Apogee (m):"));
        inputPanel.add(targetApogeeField);

        // Stability range
        stabilityMinField = new JTextField();
        stabilityMaxField = new JTextField();
        stabilityMinField.setToolTipText("Minimum stability margin in calibers");
        stabilityMaxField.setToolTipText("Maximum stability margin in calibers");
        inputPanel.add(new JLabel("Stability Min (cal):"));
        inputPanel.add(stabilityMinField);
        inputPanel.add(new JLabel("Stability Max (cal):"));
        inputPanel.add(stabilityMaxField);

        // Duration range
        durationMinField = new JTextField();
        durationMaxField = new JTextField();
        durationMinField.setToolTipText("Minimum duration in seconds (perfect score range)");
        durationMaxField.setToolTipText("Maximum duration in seconds (perfect score range)");
        inputPanel.add(new JLabel("Duration Min (s):"));
        inputPanel.add(durationMinField);
        inputPanel.add(new JLabel("Duration Max (s):"));
        inputPanel.add(durationMaxField);

        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                e.getAdjustable().setValue(e.getAdjustable().getMaximum());
            }
        });

        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusArea = new JTextArea(2, 20);
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);
        statusPanel.add(new JLabel("Current Parameters:"), BorderLayout.NORTH);
        statusPanel.add(statusScroll, BorderLayout.CENTER);

        // Progress bar
        progressBar = new JProgressBar();
        progressBar.setForeground(new Color(40, 180, 40));
        progressBar.setStringPainted(true);
        statusPanel.add(progressBar, BorderLayout.SOUTH);

        // Create a container for the SOUTH region
        JPanel southContainer = new JPanel(new BorderLayout());
        southContainer.add(statusPanel, BorderLayout.CENTER);

        // Button panel with FlowLayout to hold buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        startButton = new JButton("Start Optimization");
        saveButton = new JButton("Save Optimized Design");
        startButton.addActionListener(new StartOptimizationAction());
        saveButton.addActionListener(e -> saveOptimizedDesign());
        buttonPanel.add(startButton);
        buttonPanel.add(saveButton);

        // Add button panel to the SOUTH of the container
        southContainer.add(buttonPanel, BorderLayout.SOUTH);

        // Add the container to the main panel's SOUTH
        mainPanel.add(southContainer, BorderLayout.SOUTH);

        // Add the log area to the center
        mainPanel.add(logScroll, BorderLayout.CENTER);

        add(mainPanel);

        // Add document listeners to save settings
        addDocumentListener(filePathField);
        addDocumentListener(targetApogeeField);
        addDocumentListener(stabilityMinField);
        addDocumentListener(stabilityMaxField);
        addDocumentListener(durationMinField);
        addDocumentListener(durationMaxField);
    }

    private void saveOptimizedDesign() {
        if (optimizer == null || !optimizer.hasBestValues()) {
            JOptionPane.showMessageDialog(this, "No optimized design available.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JFileChooser saver = new JFileChooser();
        saver.setFileFilter(new FileNameExtensionFilter("OpenRocket Files", "ork"));
        if (saver.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = saver.getSelectedFile();
            try {
                if (!file.getName().toLowerCase().endsWith(".ork")) {
                    file = new File(file.getParentFile(), file.getName() + ".ork");
                }
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
            saveButton.setEnabled(false);
            logArea.setText("");
            statusArea.setText("");
            progressBar.setValue(0);

            optimizer = new RocketOptimizer();
            optimizer.getConfig().orkPath = orkPath;
            optimizer.getConfig().targetApogee = targetApogee;
            optimizer.getConfig().stabilityRange = new double[]{stabilityMin, stabilityMax};
            optimizer.getConfig().durationRange = new double[]{durationMin, durationMax};

            SwingWorker<Void, String> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    try {
                        optimizer.setLogListener(message -> publish("LOG:" + message));
                        optimizer.setStatusListener(status -> publish("STATUS:" + status));
                        optimizer.setProgressListener((currentPhase, currentEval, totalEval, totalPhases) ->
                                publish("PROGRESS:" + currentPhase + ":" + currentEval + ":" + totalEval + ":" + totalPhases));

                        optimizer.initialize();
                        optimizer.optimizeFins();
                    } catch (Exception ex) {
                        publish("LOG:Error: " + ex.getMessage());
                    }
                    return null;
                }

                @Override
                protected void process(List<String> chunks) {
                    for (String chunk : chunks) {
                        if (chunk.startsWith("LOG:")) {
                            logArea.append(chunk.substring(4) + "\n");
                        } else if (chunk.startsWith("STATUS:")) {
                            statusArea.setText(chunk.substring(7));
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
                        }
                    }
                }

                @Override
                protected void done() {
                    startButton.setEnabled(true);
                    boolean hasBest = optimizer != null && optimizer.hasBestValues();
                    saveButton.setEnabled(hasBest);
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