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

public class OptimizerGUI extends JFrame {
    private JTextField filePathField;
    private JTextField stabilityMinField;
    private JTextField stabilityMaxField;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JButton startButton;
    private JButton saveInPlaceButton;
    private JButton saveAsButton;
    private JButton cancelButton;
    private JProgressBar progressBar;
    private JLabel totalScoreLabel;
    private JLabel apogeeScoreLabel;
    private JLabel durationScoreLabel;
    private Optimizer optimizer;
    private static final String CONFIG_FILE = System.getProperty("user.home") + "/.rocketoptimizer.properties";
    private Map<String, Boolean> enabledParams = new HashMap<>();
    private JLabel apogeeLabel;

    public OptimizerGUI() {
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

        stabilityMinField = new JTextField();
        stabilityMaxField = new JTextField();
        stabilityMinField.setToolTipText("Minimum stability margin in calibers");
        stabilityMaxField.setToolTipText("Maximum stability margin in calibers");
        inputPanel.add(new JLabel("Stability Min (cal):"));
        inputPanel.add(stabilityMinField);
        inputPanel.add(new JLabel("Stability Max (cal):"));
        inputPanel.add(stabilityMaxField);

        mainPanel.add(inputPanel, BorderLayout.NORTH);

        String[] columnNames = {"Enabled", "Parameter", "Value", "Min", "Max"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0 || column == 3 || column == 4;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                if (columnIndex == 3 || columnIndex == 4) {
                    // Check if this is the Number of Fins row
                    for (int row = 0; row < getRowCount(); row++) {
                        String paramName = (String) getValueAt(row, 1);
                        if ("Number of Fins".equals(paramName)) {
                            return Integer.class;
                        }
                    }
                    return Double.class;
                }
                return String.class;
            }
        };

        Object[][] parameterData = {
                {true, "Apogee", "", null, null},
                {true, "Duration", "", null, null},
                {true, "Fin Thickness (cm)", "", null, null},
                {true, "Root Chord (cm)", "", null, null},
                {true, "Fin Height (cm)", "", null, null},
                {true, "Number of Fins", "", null, null},
                {true, "Nose Cone Length (cm)", "", null, null}
        };

        for (Object[] row : parameterData) {
            tableModel.addRow(row);
            enabledParams.put((String) row[1], (Boolean) row[0]);
        }

        // Make sure Total Score is enabled by default
        enabledParams.put("Total Score", true);

        resultsTable = new JTable(tableModel);
        // Set a comfortable row height
        resultsTable.setRowHeight(25);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(60);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        resultsTable.getColumnModel().getColumn(4).setPreferredWidth(80);

        // Create custom editor for numeric input
        class NumericEditor extends javax.swing.DefaultCellEditor {
            private JTextField textField;
            
            public NumericEditor() {
                super(new JTextField());
                textField = (JTextField) getComponent();
            }

            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                    boolean isSelected, int row, int column) {
                Component editor = super.getTableCellEditorComponent(table, value, isSelected, row, column);
                String paramName = (String) table.getValueAt(row, 1);
                
                if ("Number of Fins".equals(paramName)) {
                    textField.setDocument(new javax.swing.text.PlainDocument() {
                        @Override
                        public void insertString(int offset, String str, javax.swing.text.AttributeSet attr) 
                                throws javax.swing.text.BadLocationException {
                            String currentText = getText(0, getLength());
                            String newText = currentText.substring(0, offset) + str + currentText.substring(offset);
                            if (newText.isEmpty() || newText.matches("\\d+")) {
                                super.insertString(offset, str, attr);
                            }
                        }
                    });
                } else {
                    textField.setDocument(new javax.swing.text.PlainDocument() {
                        @Override
                        public void insertString(int offset, String str, javax.swing.text.AttributeSet attr) 
                                throws javax.swing.text.BadLocationException {
                            String currentText = getText(0, getLength());
                            String newText = currentText.substring(0, offset) + str + currentText.substring(offset);
                            
                            // Allow empty string
                            if (newText.isEmpty()) {
                                super.insertString(offset, str, attr);
                                return;
                            }
                            
                            // Check if it's a valid number with at most one decimal point
                            if (newText.matches("\\d*\\.?\\d*")) {
                                super.insertString(offset, str, attr);
                            }
                        }
                    });
                }
                
                // Set the current value in the text field after setting the document
                textField.setText(value != null ? value.toString() : "");
                return editor;
            }
        }

        // Set custom editor for both min and max columns
        resultsTable.getColumnModel().getColumn(3).setCellEditor(new NumericEditor());
        resultsTable.getColumnModel().getColumn(4).setCellEditor(new NumericEditor());

        // Add input validation for Number of Fins parameter
        resultsTable.getModel().addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                String param = (String) tableModel.getValueAt(e.getFirstRow(), 1);
                boolean enabled = (Boolean) tableModel.getValueAt(e.getFirstRow(), 0);
                enabledParams.put(param, enabled);
            } else if (e.getColumn() == 3 || e.getColumn() == 4) {
                int row = e.getFirstRow();
                String param = (String) tableModel.getValueAt(row, 1);
                Object minObj = tableModel.getValueAt(row, 3);
                Object maxObj = tableModel.getValueAt(row, 4);

                // Save any bounds that are specified
                saveSettings(); // Save settings immediately when bounds are changed
            }
        });

        // Create a panel for the metrics display
        JPanel metricsPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        metricsPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

        // Apogee display
        JPanel apogeeScorePanel = new JPanel(new BorderLayout());
        JLabel apogeeScoreTitleLabel = new JLabel("Altitude Score:");
        apogeeScoreTitleLabel.setFont(new Font(apogeeScoreTitleLabel.getFont().getName(), Font.BOLD, 16));
        apogeeScoreLabel = new JLabel("--");
        apogeeScoreLabel.setFont(new Font(apogeeScoreLabel.getFont().getName(), Font.BOLD, 20));
        apogeeScoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        apogeeScorePanel.add(apogeeScoreTitleLabel, BorderLayout.WEST);
        apogeeScorePanel.add(apogeeScoreLabel, BorderLayout.CENTER);

        // Duration Score display
        JPanel durationScorePanel = new JPanel(new BorderLayout());
        JLabel durationScoreTitleLabel = new JLabel("Duration Score:");
        durationScoreTitleLabel.setFont(new Font(durationScoreTitleLabel.getFont().getName(), Font.BOLD, 16));
        durationScoreLabel = new JLabel("--");
        durationScoreLabel.setFont(new Font(durationScoreLabel.getFont().getName(), Font.BOLD, 20));
        durationScoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        durationScorePanel.add(durationScoreTitleLabel, BorderLayout.WEST);
        durationScorePanel.add(durationScoreLabel, BorderLayout.CENTER);

        // Total Score display
        JPanel totalScorePanel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("Total Score:");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 16));
        totalScoreLabel = new JLabel("--");
        totalScoreLabel.setFont(new Font(totalScoreLabel.getFont().getName(), Font.BOLD, 20));
        totalScoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        totalScorePanel.add(titleLabel, BorderLayout.WEST);
        totalScorePanel.add(totalScoreLabel, BorderLayout.CENTER);

        metricsPanel.add(apogeeScorePanel);
        metricsPanel.add(durationScorePanel);
        metricsPanel.add(totalScorePanel);

        // Create a panel to hold the table and metrics
        JPanel centerPanel = new JPanel(new BorderLayout());
        
        // Add the table with its header
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(resultsTable.getTableHeader(), BorderLayout.NORTH);
        tablePanel.add(resultsTable, BorderLayout.CENTER);
        centerPanel.add(tablePanel, BorderLayout.NORTH); // Add to NORTH to prevent vertical stretching
        
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
        cancelButton = new JButton("Cancel");
        cancelButton.setEnabled(false);
        
        startButton.addActionListener(new StartOptimizationAction());
        saveInPlaceButton.addActionListener(e -> saveOptimizedDesign(true));
        saveAsButton.addActionListener(e -> saveOptimizedDesign(false));
        cancelButton.addActionListener(e -> {
            if (optimizer != null) {
                optimizer.cancel();
                cancelButton.setEnabled(false);
                startButton.setEnabled(true);
                progressBar.setString("Cancelled");
            }
        });
        
        buttonPanel.add(startButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveInPlaceButton);
        buttonPanel.add(saveAsButton);
        southContainer.add(buttonPanel, BorderLayout.SOUTH);

        mainPanel.add(southContainer, BorderLayout.SOUTH);
        add(mainPanel);

        addDocumentListener(filePathField);
        addDocumentListener(stabilityMinField);
        addDocumentListener(stabilityMaxField);

        // Initialize apogeeLabel to prevent null pointer exception
        apogeeLabel = new JLabel("--");
        apogeeLabel.setFont(new Font(apogeeLabel.getFont().getName(), Font.BOLD, 20));
        apogeeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
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
            stabilityMinField.setText(props.getProperty("stabilityMin", "1.0"));
            stabilityMaxField.setText(props.getProperty("stabilityMax", "2.0"));
            
            // Load parameter bounds into the table
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                String minKey = paramName.replaceAll("\\s+", "") + ".min";
                String maxKey = paramName.replaceAll("\\s+", "") + ".max";
                
                if (props.containsKey(minKey)) {
                    try {
                        if ("Number of Fins".equals(paramName)) {
                            int minValue = Integer.parseInt(props.getProperty(minKey));
                            tableModel.setValueAt(minValue, row, 3);
                        } else {
                            double minValue = Double.parseDouble(props.getProperty(minKey));
                            tableModel.setValueAt(minValue, row, 3);
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid values
                    }
                }
                
                if (props.containsKey(maxKey)) {
                    try {
                        if ("Number of Fins".equals(paramName)) {
                            int maxValue = Integer.parseInt(props.getProperty(maxKey));
                            tableModel.setValueAt(maxValue, row, 4);
                        } else {
                            double maxValue = Double.parseDouble(props.getProperty(maxKey));
                            tableModel.setValueAt(maxValue, row, 4);
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid values
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("orkPath", filePathField.getText());
        props.setProperty("stabilityMin", stabilityMinField.getText());
        props.setProperty("stabilityMax", stabilityMaxField.getText());
        
        // Save parameter bounds from the table
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String paramName = (String) tableModel.getValueAt(row, 1);
            Object minObj = tableModel.getValueAt(row, 3);
            Object maxObj = tableModel.getValueAt(row, 4);
            
            if (minObj != null) {
                props.setProperty(paramName.replaceAll("\\s+", "") + ".min", minObj.toString());
            }
            if (maxObj != null) {
                props.setProperty(paramName.replaceAll("\\s+", "") + ".max", maxObj.toString());
            }
        }
        
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
                case "Apogee":
                    updateCell(row, results.get("apogee"), "%.1f");
                    break;
                case "Duration":
                    updateCell(row, results.get("duration"), "%.2f");
                    break;
                case "Fin Thickness (cm)":
                    updateCell(row, results.get("thickness"), "%.2f");
                    break;
                case "Root Chord (cm)":
                    updateCell(row, results.get("rootChord"), "%.2f");
                    break;
                case "Fin Height (cm)":
                    updateCell(row, results.get("height"), "%.2f");
                    break;
                case "Number of Fins":
                    updateCell(row, results.get("finCount"), "%.0f");
                    break;
            }
        }

        // Update scores separately
        if (results.containsKey("altitudeScore")) {
            apogeeScoreLabel.setText(String.format("%.1f", results.get("altitudeScore")));
        } else {
            apogeeScoreLabel.setText("--");
        }
        
        if (results.containsKey("durationScore")) {
            durationScoreLabel.setText(String.format("%.2f", results.get("durationScore")));
        } else {
            durationScoreLabel.setText("--");
        }

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
        if (currentParams == null) return;
        
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String paramName = (String) tableModel.getValueAt(row, 1);
            String key = mapParamNameToKey(paramName);
            if (currentParams.containsKey(key)) {
                Double value = currentParams.get(key);
                String format = getFormatForParameter(paramName);
                tableModel.setValueAt(value != null ? String.format(format, value) : "", row, 2);
            }
        }

        // Update metrics display with null checks
        if (currentParams.containsKey("apogee") && apogeeLabel != null) {
            apogeeLabel.setText(String.format("%.1f m", currentParams.get("apogee")));
        }
        
        if (currentParams.containsKey("altitudeScore") && apogeeScoreLabel != null) {
            apogeeScoreLabel.setText(String.format("%.1f", currentParams.get("altitudeScore")));
        }
        
        if (currentParams.containsKey("durationScore") && durationScoreLabel != null) {
            durationScoreLabel.setText(String.format("%.2f", currentParams.get("durationScore")));
        }
        
        if (currentParams.containsKey("totalScore") && totalScoreLabel != null) {
            totalScoreLabel.setText(String.format("%.2f", currentParams.get("totalScore")));
        }
    }

    private String getFormatForParameter(String paramName) {
        switch (paramName) {
            case "Altitude Score":
                return "%.1f";
            case "Duration Score":
                return "%.2f";
            case "Fin Thickness (cm)":
            case "Root Chord (cm)":
            case "Fin Height (cm)":
            case "Nose Cone Length (cm)":
                return "%.2f";
            case "Number of Fins":
                return "%.0f";
            default:
                return "%.2f";
        }
    }

    private String mapParamNameToKey(String displayName) {
        switch (displayName) {
            case "Fin Thickness (cm)": return "thickness";
            case "Root Chord (cm)": return "rootChord";
            case "Fin Height (cm)": return "height";
            case "Number of Fins": return "finCount";
            case "Altitude Score": return "altitudeScore";
            case "Duration Score": return "durationScore";
            case "Apogee": return "apogee";
            case "Duration": return "duration";
            case "Total Score": return "totalScore";
            case "Nose Cone Length (cm)": return "noseLength";
            default: return displayName.toLowerCase().replace(" ", "");
        }
    }

    private void updateParameterBounds(String paramName, double min, double max) {
        try {
            if (optimizer != null) {
                optimizer.updateParameterBounds(paramName, min, max);
            }
        } catch (IllegalArgumentException e) {
            // Show a popup for the error instead of just logging to console
            JOptionPane.showMessageDialog(this,
                    "Error setting bounds for " + paramName + ": " + e.getMessage(),
                    "Invalid Parameter Bounds",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private class BrowseAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("OpenRocket Files", "ork"));
            int result = fileChooser.showOpenDialog(OptimizerGUI.this);
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
                JOptionPane.showMessageDialog(OptimizerGUI.this, "Please select an ORK file.", "Error", JOptionPane.ERROR_MESSAGE);
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
                JOptionPane.showMessageDialog(OptimizerGUI.this, "Invalid stability range.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Validate parameter bounds
            StringBuilder errors = new StringBuilder();
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                Boolean enabled = (Boolean) tableModel.getValueAt(row, 0);
                if (!enabled) continue;

                String minStr = tableModel.getValueAt(row, 3) != null ? tableModel.getValueAt(row, 3).toString() : "";
                String maxStr = tableModel.getValueAt(row, 4) != null ? tableModel.getValueAt(row, 4).toString() : "";

                // Check for missing bounds
                if (minStr.isEmpty() || maxStr.isEmpty()) {
                    if (errors.length() > 0) errors.append("\n");
                    errors.append("Missing bounds for: ").append(paramName);
                    continue;
                }

                // Check for invalid number format
                try {
                    double min = Double.parseDouble(minStr);
                    double max = Double.parseDouble(maxStr);

                    // Check for min > max
                    if (min > max) {
                        if (errors.length() > 0) errors.append("\n");
                        errors.append("Minimum value cannot be greater than maximum value for: ").append(paramName);
                        continue;
                    }

                    // Check for non-integer values in Number of Fins
                    if ("Number of Fins".equals(paramName)) {
                        if (min != Math.floor(min) || max != Math.floor(max)) {
                            if (errors.length() > 0) errors.append("\n");
                            errors.append("Number of Fins must be an integer value (no decimals allowed)");
                        }
                    }
                } catch (NumberFormatException ex) {
                    if (errors.length() > 0) errors.append("\n");
                    errors.append("Invalid number format for: ").append(paramName);
                }
            }

            if (errors.length() > 0) {
                JOptionPane.showMessageDialog(OptimizerGUI.this,
                    "Please fix the following errors before starting optimization:\n" + errors,
                    "Validation Errors",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }

            startButton.setEnabled(false);
            cancelButton.setEnabled(true);
            saveInPlaceButton.setEnabled(false);
            saveAsButton.setEnabled(false);
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                tableModel.setValueAt("", i, 2);
            }
            totalScoreLabel.setText("--");
            progressBar.setValue(0);

            optimizer = new Optimizer();
            optimizer.getConfig().orkPath = orkPath;
            optimizer.getConfig().stabilityRange = new double[]{stabilityMin, stabilityMax};
            optimizer.getConfig().enabledParams = new HashMap<>(enabledParams);

            // Set parameter bounds from table
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                Object minObj = tableModel.getValueAt(row, 3);
                Object maxObj = tableModel.getValueAt(row, 4);
                if (minObj != null && maxObj != null) {
                    try {
                        double min = Double.parseDouble(minObj.toString());
                        double max = Double.parseDouble(maxObj.toString());
                        optimizer.updateParameterBounds(paramName, min, max);
                    } catch (NumberFormatException ex) {
                        // Skip invalid values
                    }
                }
            }

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

                            // Cap the progress percentage at 100% to avoid extreme values
                            double phaseProgress = Math.min(100.0, (double) currentEval / Math.max(1, totalEval) * 100);
                            String text = String.format("Phase %d/%d: %.1f%%", currentPhase + 1, totalPhases, phaseProgress);
                            progressBar.setString(text);
                            progressBar.setValue((int) phaseProgress);
                        } else if (chunk.startsWith("ERROR:")) {
                            JOptionPane.showMessageDialog(OptimizerGUI.this,
                                    "Optimization error: " + chunk.substring(6),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }

                @Override
                protected void done() {
                    startButton.setEnabled(true);
                    cancelButton.setEnabled(false);
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

                OptimizerGUI gui = new OptimizerGUI();
                gui.setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Initialization failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}