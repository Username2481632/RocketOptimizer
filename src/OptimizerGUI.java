import com.google.inject.Guice;
import com.google.inject.Injector;
import net.sf.openrocket.preset.ComponentPreset;
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
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import javax.swing.table.DefaultTableCellRenderer;
import java.util.ArrayList;
import java.util.EventObject;
import javax.swing.Box;
import javax.swing.BoxLayout;

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
    private JButton showInOpenRocketButton;
    private JComboBox<String> stage1ParachuteComboBox;
    private JComboBox<String> stage2ParachuteComboBox;
    private int stage1ParachuteRow = -1;
    private int stage2ParachuteRow = -1;
    private List<String> parachutePresets;

    public OptimizerGUI() {
        super("Rocket Optimizer GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        initComponents();
        loadSettings();
        saveInPlaceButton.setEnabled(false);
        saveAsButton.setEnabled(false);
        updateShowInOpenRocketButton();
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
                // Check if this is a parachute row
                String paramName = (String) getValueAt(row, 1);
                if ("Stage 1 Parachute".equals(paramName) || "Stage 2 Parachute".equals(paramName)) {
                    // Make only the 'Enabled' column editable for parachutes, not min/max
                    return column == 0;
                }
                // For other rows, allow editing enabled, min, and max columns
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
                {true, "Nose Cone Length (cm)", "", null, null},
                {true, "Nose Cone Wall Thickness (cm)", "", null, null},
                {true, "Stage 1 Parachute", "", null, null},
                {true, "Stage 2 Parachute", "", null, null}
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
        
        // Add tooltips for parameters
        resultsTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = resultsTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    String paramName = (String) tableModel.getValueAt(row, 1);
                    if ("Nose Cone Wall Thickness (cm)".equals(paramName)) {
                        resultsTable.setToolTipText(
                            "<html>Wall thickness of the nose cone.<br>" +
                            "Leave the maximum field empty for no upper limit.<br>" +
                            "Physical limitation: the maximum thickness cannot exceed half the nose cone's base diameter.</html>");
                    } else if ("Number of Fins".equals(paramName)) {
                        resultsTable.setToolTipText("Number of fins. Must be an integer value.");
                    } else {
                        resultsTable.setToolTipText(null);
                    }
                }
            }
        });

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
        
        // Add tooltips for column headers
        JTableHeader header = resultsTable.getTableHeader();
        header.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int col = resultsTable.columnAtPoint(e.getPoint());
                if (col == 3) {
                    header.setToolTipText("Minimum value for parameter (required)");
                } else if (col == 4) {
                    header.setToolTipText("<html>Maximum value for parameter<br>Leave empty for unlimited maximum</html>");
                } else {
                    header.setToolTipText(null);
                }
            }
        });
        
        // Add custom renderer for the Max column to show "Unlimited" for empty cells
        resultsTable.getColumnModel().getColumn(4).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus,
                                                          int row, int column) {
                String paramName = (String) table.getValueAt(row, 1);
                // For parachute rows, use a special renderer with blank text
                if ("Stage 1 Parachute".equals(paramName) || "Stage 2 Parachute".equals(paramName)) {
                    setForeground(Color.LIGHT_GRAY);
                    setBackground(new Color(240, 240, 240));
                    setHorizontalAlignment(SwingConstants.CENTER);
                    setText("");
                    return this;
                }
                
                // For other rows, use the regular "Unlimited" renderer
                if (value == null || value.toString().isEmpty()) {
                    value = "Unlimited";
                    setForeground(Color.GRAY);
                    setHorizontalAlignment(SwingConstants.CENTER);
                } else {
                    setForeground(table.getForeground());
                    setHorizontalAlignment(SwingConstants.LEFT);
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        });

        // Add custom renderer for the Min column to handle parachute rows
        resultsTable.getColumnModel().getColumn(3).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus,
                                                          int row, int column) {
                String paramName = (String) table.getValueAt(row, 1);
                // For parachute rows, use a special renderer with blank text
                if ("Stage 1 Parachute".equals(paramName) || "Stage 2 Parachute".equals(paramName)) {
                    setForeground(Color.LIGHT_GRAY);
                    setBackground(new Color(240, 240, 240));
                    setHorizontalAlignment(SwingConstants.CENTER);
                    setText("");
                    return this;
                }
                
                // For other rows with empty values, show "Unlimited"
                if (value == null || value.toString().isEmpty()) {
                    value = "Unlimited";
                    setForeground(Color.GRAY);
                    setHorizontalAlignment(SwingConstants.CENTER);
                } else {
                    setForeground(table.getForeground());
                    setHorizontalAlignment(SwingConstants.LEFT);
                }
                
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        });

        // Add input validation for Number of Fins parameter
        resultsTable.getModel().addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                String param = (String) tableModel.getValueAt(e.getFirstRow(), 1);
                boolean enabled = (Boolean) tableModel.getValueAt(e.getFirstRow(), 0);
                enabledParams.put(param, enabled);
                saveSettings(); // Save settings immediately when enabled status changes
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
        JPanel centerPanel = new JPanel(new BorderLayout(10, 0));
        
        // Add the table with its header
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(resultsTable.getTableHeader(), BorderLayout.NORTH);
        tablePanel.add(resultsTable, BorderLayout.CENTER);
        centerPanel.add(tablePanel, BorderLayout.NORTH);
        
        // Apply negative top margin to move scores up closer to the table
        JPanel spacerPanel = new JPanel(new BorderLayout());
        spacerPanel.setBorder(BorderFactory.createEmptyBorder(-5, 0, 0, 0));
        spacerPanel.add(metricsPanel, BorderLayout.NORTH);
        centerPanel.add(spacerPanel, BorderLayout.CENTER);

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
                progressBar.setString("Optimization Cancelled");
                
                // Only enable save buttons if we have best values
                boolean hasBest = optimizer.hasBestValues();
                saveInPlaceButton.setEnabled(hasBest);
                saveAsButton.setEnabled(hasBest);
                
                // Update the table with best values if available, otherwise keep current values
                if (hasBest) {
                    updateResultsTable(optimizer.getBestValues());
                }
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

        showInOpenRocketButton = new JButton("Show in OpenRocket");
        showInOpenRocketButton.setEnabled(false);
        buttonPanel.add(showInOpenRocketButton);
        showInOpenRocketButton.addActionListener(e -> showInOpenRocket());

        // Setup custom renderers for the Min and Max columns
        TableColumn minColumn = resultsTable.getColumnModel().getColumn(3);
        minColumn.setCellRenderer(new ParachuteAwareCellRenderer(3));
        
        TableColumn maxColumn = resultsTable.getColumnModel().getColumn(4);
        maxColumn.setCellRenderer(new ParachuteAwareCellRenderer(4));
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
            public void insertUpdate(DocumentEvent e) { 
                saveSettings();
                if (field == filePathField) {
                    updateShowInOpenRocketButton();
                    loadInitialValues(field.getText());
                }
            }
            public void removeUpdate(DocumentEvent e) { 
                saveSettings();
                if (field == filePathField) {
                    updateShowInOpenRocketButton();
                    loadInitialValues(field.getText());
                }
            }
            public void changedUpdate(DocumentEvent e) { 
                saveSettings();
                if (field == filePathField) {
                    updateShowInOpenRocketButton();
                    loadInitialValues(field.getText());
                }
            }
        });
    }

    private void updateShowInOpenRocketButton() {
        String openRocketPath = getOpenRocketPath();
        showInOpenRocketButton.setEnabled(openRocketPath != null);
    }

    private String getOpenRocketPath() {
        String filePath = filePathField.getText();
        boolean hasValidFile = !filePath.isEmpty() && new File(filePath).exists();
        return isOpenRocketInstalled() && hasValidFile ? filePath : null;
    }

    private void showInOpenRocket() {
        String filePath = filePathField.getText();
        if (filePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a file first.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this, "Selected file no longer exists.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            File fileToOpen;
            if (optimizer != null && optimizer.hasBestValues()) {
                // Create a temporary directory with a fixed name
                File tempDir = new File(System.getProperty("java.io.tmpdir"), "rocket_optimizer");
                if (!tempDir.exists()) {
                    tempDir.mkdir();
                }
                
                // Use a clear temporary file name
                fileToOpen = new File(tempDir, "TEMP_optimizer_result.ork");
                
                // Save to the temp file
                optimizer.saveOptimizedDesign(fileToOpen);
                
                // Schedule for deletion when JVM exits
                fileToOpen.deleteOnExit();
                tempDir.deleteOnExit();
            } else {
                // Use the original file
                fileToOpen = file;
            }
            
            // Launch OpenRocket with the file
            Process process;
            if (System.getProperty("os.name").toLowerCase().contains("linux")) {
                process = Runtime.getRuntime().exec(new String[]{"xdg-open", fileToOpen.getAbsolutePath()});
            } else if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", fileToOpen.getAbsolutePath()});
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                process = Runtime.getRuntime().exec(new String[]{"open", fileToOpen.getAbsolutePath()});
            } else {
                throw new RuntimeException("Unsupported operating system");
            }
            
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to open in OpenRocket:\n" + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean isOpenRocketInstalled() {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            // Check for desktop entry on Linux
            File[] desktopDirs = {
                new File(System.getProperty("user.home") + "/.local/share/applications"),
                new File("/usr/share/applications"),
                new File("/usr/local/share/applications")
            };
            
            for (File dir : desktopDirs) {
                if (dir.exists() && dir.isDirectory()) {
                    File[] entries = dir.listFiles((d, name) -> name.toLowerCase().contains("openrocket"));
                    if (entries != null && entries.length > 0) {
                        return true;
                    }
                }
            }
        } else if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            // Check for OpenRocket in Program Files on Windows
            File[] programFiles = {
                new File("C:\\Program Files\\OpenRocket"),
                new File("C:\\Program Files (x86)\\OpenRocket")
            };
            
            for (File dir : programFiles) {
                if (dir.exists() && dir.isDirectory()) {
                    return true;
                }
            }
        } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            // Check for OpenRocket.app on macOS
            File[] appLocations = {
                new File("/Applications/OpenRocket.app"),
                new File(System.getProperty("user.home") + "/Applications/OpenRocket.app")
            };
            
            for (File app : appLocations) {
                if (app.exists() && app.isDirectory()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadSettings() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(CONFIG_FILE)) {
            props.load(in);
            filePathField.setText(props.getProperty("orkPath", ""));
            stabilityMinField.setText(props.getProperty("stabilityMin", ""));
            stabilityMaxField.setText(props.getProperty("stabilityMax", ""));
            
            // Load parameter bounds and enabled status into the table
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                String minKey = paramName.replaceAll("\\s+", "") + ".min";
                String maxKey = paramName.replaceAll("\\s+", "") + ".max";
                String enabledKey = paramName.replaceAll("\\s+", "") + ".enabled";
                
                // Load enabled status
                if (props.containsKey(enabledKey)) {
                    boolean enabled = Boolean.parseBoolean(props.getProperty(enabledKey));
                    tableModel.setValueAt(enabled, row, 0);
                    enabledParams.put(paramName, enabled);
                }
                
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
        setupParachuteComboBoxes();
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("orkPath", filePathField.getText());
        props.setProperty("stabilityMin", stabilityMinField.getText());
        props.setProperty("stabilityMax", stabilityMaxField.getText());
        
        // Save parameter bounds and enabled status from the table
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String paramName = (String) tableModel.getValueAt(row, 1);
            Object minObj = tableModel.getValueAt(row, 3);
            Object maxObj = tableModel.getValueAt(row, 4);
            Boolean enabled = (Boolean) tableModel.getValueAt(row, 0);
            
            // Save enabled status
            props.setProperty(paramName.replaceAll("\\s+", "") + ".enabled", enabled.toString());
            
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
                case "Nose Cone Length (cm)":
                    updateCell(row, results.get("noseLength"), "%.2f");
                    break;
                case "Nose Cone Wall Thickness (cm)":
                    updateCell(row, results.get("noseWallThickness"), "%.2f");
                    break;
                case "Stage 1 Parachute":
                    if (optimizer != null) {
                        String parachuteName = optimizer.getBestStage1Parachute();
                        // Only update if we have a valid parachute name (not None or null)
                        if (parachuteName != null && !parachuteName.equals("None") && !parachuteName.equals("null")) {
                            tableModel.setValueAt(parachuteName, row, 2);
                        }
                    }
                    break;
                case "Stage 2 Parachute":
                    if (optimizer != null) {
                        String parachuteName = optimizer.getBestStage2Parachute();
                        // Only update if we have a valid parachute name (not None or null)
                        if (parachuteName != null && !parachuteName.equals("None") && !parachuteName.equals("null")) {
                            tableModel.setValueAt(parachuteName, row, 2);
                        }
                    }
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
        
        // Check if we have string parameters for parachutes
        Map<String, String> stringParams = new HashMap<>();
        
        // Only update parachute display values if the optimizer explicitly provides them
        // Don't overwrite existing values with numeric placeholders
        if (optimizer != null) {
            String stage1Value = optimizer.getBestStage1Parachute();
            String stage2Value = optimizer.getBestStage2Parachute();
            
            // Only update if we have a valid parachute name (not None or null)
            if (stage1Value != null && !stage1Value.equals("None") && !stage1Value.equals("null")) {
                stringParams.put("stage1Parachute", stage1Value);
            }
            
            if (stage2Value != null && !stage2Value.equals("None") && !stage2Value.equals("null")) {
                stringParams.put("stage2Parachute", stage2Value);
            }
        }
        
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String paramName = (String) tableModel.getValueAt(row, 1);
            
            // Handle parachute parameters separately
            if ("Stage 1 Parachute".equals(paramName)) {
                if (stringParams.containsKey("stage1Parachute")) {
                    String value = stringParams.get("stage1Parachute");
                    tableModel.setValueAt(value, row, 2);
                } else if (optimizer != null) {
                    // Get the current parachute value directly to ensure it's up to date
                    String currentParachute = optimizer.getBestStage1Parachute();
                    if (currentParachute != null && !currentParachute.equals("None") && !currentParachute.equals("null")) {
                        tableModel.setValueAt(currentParachute, row, 2);
                    }
                }
                continue;
            } else if ("Stage 2 Parachute".equals(paramName)) {
                if (stringParams.containsKey("stage2Parachute")) {
                    String value = stringParams.get("stage2Parachute");
                    tableModel.setValueAt(value, row, 2);
                } else if (optimizer != null) {
                    // Get the current parachute value directly to ensure it's up to date
                    String currentParachute = optimizer.getBestStage2Parachute();
                    if (currentParachute != null && !currentParachute.equals("None") && !currentParachute.equals("null")) {
                        tableModel.setValueAt(currentParachute, row, 2);
                    }
                }
                continue;
            }
            
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
            case "Nose Cone Wall Thickness (cm)":
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
            case "Nose Cone Wall Thickness (cm)": return "noseWallThickness";
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

    private void loadInitialValues(String filePath) {
        try {
            Optimizer tempOptimizer = new Optimizer();
            tempOptimizer.getConfig().orkPath = filePath;
            tempOptimizer.initialize();
            Map<String, Double> initialValues = tempOptimizer.getInitialValues();
            
            // Update the table with initial values
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                String key = mapParamNameToKey(paramName);
                
                if (initialValues.containsKey(key)) {
                    Double value = initialValues.get(key);
                    String format = getFormatForParameter(paramName);
                    double displayValue = (value != null) ? value : 0.0; // Default to 0 if null

                    // Convert meters to cm for display where applicable
                    if (value != null && (
                        "Fin Thickness (cm)".equals(paramName) ||
                        "Root Chord (cm)".equals(paramName) ||
                        "Fin Height (cm)".equals(paramName) ||
                        "Nose Cone Length (cm)".equals(paramName) ||
                        "Nose Cone Wall Thickness (cm)".equals(paramName))) {
                       displayValue = value * 100.0;
                    }
                    
                    // Handle special cases for parachutes
                    if ("Stage 1 Parachute".equals(paramName)) {
                        String p1Name = tempOptimizer.getBestStage1Parachute();
                        tableModel.setValueAt(p1Name != null ? p1Name : "", row, 2); // Show empty if null
                        stage1ParachuteRow = row;
                    } else if ("Stage 2 Parachute".equals(paramName)) {
                        String p2Name = tempOptimizer.getBestStage2Parachute();
                        tableModel.setValueAt(p2Name != null ? p2Name : "", row, 2); // Show empty if null
                        stage2ParachuteRow = row;
                    } else {
                        // Use displayValue which might have been converted
                        tableModel.setValueAt(String.format(format, displayValue), row, 2);
                    }
                }
            }
            
            // --- REMOVED --- Score labels are no longer updated here, as getInitialValues doesn't run a simulation ---
            /*
            // Update score labels
            if (initialValues.containsKey("altitudeScore")) {
                apogeeScoreLabel.setText(String.format("%.1f", initialValues.get("altitudeScore")));
            } else {
                apogeeScoreLabel.setText("--"); // Handle case where score is missing
            }
            if (initialValues.containsKey("durationScore")) {
                durationScoreLabel.setText(String.format("%.2f", initialValues.get("durationScore")));
            } else {
                durationScoreLabel.setText("--"); // Handle case where score is missing
            }
            if (initialValues.containsKey("totalScore")) {
                totalScoreLabel.setText(String.format("%.2f", initialValues.get("totalScore")));
            } else {
                totalScoreLabel.setText("--"); // Handle case where score is missing
            }
            */
            // --- END REMOVED ---
            
        } catch (Exception e) {
            // Show error message if loading initial values fails
             JOptionPane.showMessageDialog(this,
                    "Failed to load initial values from ORK file:\n" + e.getMessage(),
                    "Initialization Error",
                    JOptionPane.WARNING_MESSAGE);
             // Clear potentially incorrect values shown before error
             clearValueColumn();
             clearScoreLabels();
        }
    }

    // Helper method to clear the value column
    private void clearValueColumn() {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            tableModel.setValueAt("", row, 2);
        }
    }
    
    // Helper method to clear score labels
    private void clearScoreLabels() {
        apogeeScoreLabel.setText("--");
        durationScoreLabel.setText("--");
        totalScoreLabel.setText("--");
    }

    private class BrowseAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("OpenRocket Files", "ork"));
            int result = fileChooser.showOpenDialog(OptimizerGUI.this);
            if (result == JFileChooser.APPROVE_OPTION) {
                String filePath = fileChooser.getSelectedFile().getAbsolutePath();
                filePathField.setText(filePath);
                loadInitialValues(filePath);
            }
        }
    }

    /**
     * Maps GUI display names to the keys expected by the Optimizer's enabledParams map.
     * For FinParameter items, it uses the internal key (e.g., "thickness").
     * For others (Scores, Parachutes), it uses the display name.
     */
    private String getOptimizerParamKey(String displayName) {
        if (displayName.startsWith("Fin Thickness")) return "thickness";
        if (displayName.startsWith("Root Chord")) return "rootChord";
        if (displayName.startsWith("Fin Height")) return "height";
        if (displayName.startsWith("Number of Fins")) return "finCount";
        if (displayName.startsWith("Nose Cone Length")) return "noseLength";
        if (displayName.startsWith("Nose Cone Wall Thickness")) return "noseWallThickness";
        // Handle specific GUI names just in case
        switch (displayName) {
            case "Fin Thickness (cm)": return "thickness";
            case "Root Chord (cm)": return "rootChord";
            case "Fin Height (cm)": return "height";
            case "Nose Cone Length (cm)": return "noseLength";
            case "Nose Cone Wall Thickness (cm)": return "noseWallThickness";
            // For non-FinParameter items, use the display name as the key
            case "Apogee":
            case "Duration":
            case "Stage 1 Parachute":
            case "Stage 2 Parachute":
            case "Altitude Score": 
            case "Duration Score": 
            case "Total Score": // Include Total Score if needed elsewhere
                return displayName; 
            default: 
                // Fallback, should ideally not be reached for known params
                System.err.println("Warning: Unknown parameter display name encountered: " + displayName);
                return displayName.toLowerCase().replace(" ", ""); 
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
                    throw new IllegalArgumentException("Stability min must be less than stability max.");
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(OptimizerGUI.this, "Invalid stability range format.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            } catch (IllegalArgumentException ex) {
                 JOptionPane.showMessageDialog(OptimizerGUI.this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                 return;
            }


            // --- Pre-validation and Clamping ---
            StringBuilder validationErrors = new StringBuilder();
            StringBuilder clampingWarnings = new StringBuilder();
            Optimizer tempOptimizer = null;

            try {
                tempOptimizer = new Optimizer();
                tempOptimizer.getConfig().orkPath = orkPath;
                // Temporarily set stability range for initialization if needed by physical bounds calculation
                tempOptimizer.getConfig().stabilityRange = new double[]{stabilityMin, stabilityMax}; 
                tempOptimizer.initialize(); // This calculates physical bounds
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(OptimizerGUI.this,
                        "Error reading ORK file for validation: " + ex.getMessage(),
                        "Initialization Error", JOptionPane.ERROR_MESSAGE);
                return; // Cannot proceed without physical bounds
            }

            // Validate parameter bounds and clamp if necessary
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                Boolean enabled = (Boolean) tableModel.getValueAt(row, 0);

                // Skip disabled parameters and non-optimizable rows (Apogee, Duration, Parachutes for this check)
                if (!enabled || paramName.contains("Parachute") || "Apogee".equals(paramName) || "Duration".equals(paramName)) {
                    continue;
                }

                Object minObj = tableModel.getValueAt(row, 3);
                Object maxObj = tableModel.getValueAt(row, 4);
                String minStr = (minObj != null) ? minObj.toString() : "";
                String maxStr = (maxObj != null) ? maxObj.toString() : "";

                Optimizer.FinParameter finParam = tempOptimizer.getFinParameter(paramName);
                if (finParam == null) {
                    validationErrors.append("\nInternal error: Could not find parameter details for ").append(paramName);
                    continue; // Should not happen
                }

                double userMin = Double.NEGATIVE_INFINITY;
                double userMax = Double.POSITIVE_INFINITY; // Use POSITIVE_INFINITY for unlimited max

                try {
                    if (!minStr.isEmpty()) {
                        if ("Number of Fins".equals(paramName)) {
                            userMin = Integer.parseInt(minStr);
                        } else {
                            userMin = Double.parseDouble(minStr);
                        }
                    }

                    if (!maxStr.isEmpty()) {
                         if ("Number of Fins".equals(paramName)) {
                            userMax = Integer.parseInt(maxStr);
                        } else {
                            userMax = Double.parseDouble(maxStr);
                        }
                    } else {
                        // If max string is empty, it means unlimited
                        userMax = Double.POSITIVE_INFINITY; 
                    }
                    
                    // Basic validation: min <= max
                    if (userMin > userMax) {
                         if (validationErrors.length() > 0) validationErrors.append("\n");
                         validationErrors.append("Minimum value cannot be greater than maximum value for: ").append(paramName);
                         continue; // Skip clamping checks if basic validation fails
                    }

                    // Get physical bounds from the temporary optimizer
                    double physicalMin = finParam.absoluteMin;
                    double physicalMax = finParam.absoluteMax;
                    
                    double clampedMin = userMin;
                    double clampedMax = userMax;
                    boolean minClamped = false;
                    boolean maxClamped = false;

                    // Check and clamp Minimum
                    if (userMin < physicalMin) {
                        clampedMin = physicalMin;
                        minClamped = true;
                        // Only warn if the user actually *entered* a specific value below the physical minimum
                        if (!minStr.isEmpty()) { // i.e., userMin was not originally Double.NEGATIVE_INFINITY
                             if (clampingWarnings.length() > 0) clampingWarnings.append("\n");
                             clampingWarnings.append(String.format("%s minimum clamped to physical limit %.2f", paramName, physicalMin));
                        }
                    }

                    // Check and clamp Maximum
                    // Only clamp if physicalMax has a finite limit (is not MAX_VALUE or POSITIVE_INFINITY)
                    if (physicalMax != Double.MAX_VALUE && physicalMax != Double.POSITIVE_INFINITY && userMax > physicalMax) {
                        clampedMax = physicalMax;
                        maxClamped = true;
                         // Only warn if the user actually *entered* a specific value above the physical maximum
                        if (!maxStr.isEmpty()) { // i.e., userMax was not originally Double.POSITIVE_INFINITY
                            if (clampingWarnings.length() > 0) clampingWarnings.append("\n");
                            clampingWarnings.append(String.format("%s maximum clamped to physical limit %.2f", paramName, physicalMax));
                        }
                    }
                    
                    // Ensure clampedMin is not > clampedMax after potential clamping
                    if (clampedMin > clampedMax) {
                       // This should ideally not happen if physicalMin <= physicalMax, but as a safeguard:
                       clampedMin = clampedMax; 
                       // No need to mark as clamped here, the individual checks handle warnings
                    }

                    // --- DO NOT Update the table model visually for clamping --- 
                    // The GUI will continue to show the user's original input.

                    // Check for non-integer values in Number of Fins using clamped values
                    if ("Number of Fins".equals(paramName)) {
                        if ((!minStr.isEmpty() && clampedMin != Math.floor(clampedMin)) ||
                            (!maxStr.isEmpty() && clampedMax != Double.POSITIVE_INFINITY && clampedMax != Math.floor(clampedMax))) {
                            if (validationErrors.length() > 0) validationErrors.append("\n");
                            validationErrors.append("Number of Fins must be an integer value (no decimals allowed)");
                        }
                    }

                } catch (NumberFormatException ex) {
                    if (validationErrors.length() > 0) validationErrors.append("\n");
                    validationErrors.append("Invalid number format for: ").append(paramName);
                }
            }

            // Validate parachute components existence
             try {
                 // Use the already initialized tempOptimizer
                 if (stage1ParachuteRow >= 0) {
                     boolean enabled = (Boolean) tableModel.getValueAt(stage1ParachuteRow, 0);
                     if (enabled && !tempOptimizer.hasStage1Parachute()) {
                         if (validationErrors.length() > 0) validationErrors.append("\n");
                         validationErrors.append("Stage 1 Parachute component not found in the provided ORK file.");
                     }
                 }
                 if (stage2ParachuteRow >= 0) {
                     boolean enabled = (Boolean) tableModel.getValueAt(stage2ParachuteRow, 0);
                     if (enabled && !tempOptimizer.hasStage2Parachute()) {
                         if (validationErrors.length() > 0) validationErrors.append("\n");
                         validationErrors.append("Stage 2 Parachute component not found in the provided ORK file.");
                     }
                 }
             } catch (Exception ex) {
                 // Catch potential issues during hasStageXParachute calls
                 if (validationErrors.length() > 0) validationErrors.append("\n");
                 validationErrors.append("Error validating parachute components: ").append(ex.getMessage());
             }
             
            // --- Show Errors or Warnings ---
            if (validationErrors.length() > 0) {
                JOptionPane.showMessageDialog(OptimizerGUI.this,
                        "Please fix the following errors before starting optimization:\n" + validationErrors.toString(),
                        "Validation Errors",
                        JOptionPane.ERROR_MESSAGE);
                return; // Stop if there are fatal errors
            }

            if (clampingWarnings.length() > 0) {
                JOptionPane.showMessageDialog(OptimizerGUI.this,
                        "The following bounds were adjusted to physical limits:\n" + clampingWarnings.toString() +
                        "\n\nOptimization will proceed with the adjusted values.",
                        "Bounds Clamped",
                        JOptionPane.WARNING_MESSAGE);
                // Continue after warning
            }

            // --- Proceed with Optimization Setup ---
            startButton.setEnabled(false);
            cancelButton.setEnabled(true);
            saveInPlaceButton.setEnabled(false);
            saveAsButton.setEnabled(false);
            progressBar.setValue(0);

            optimizer = new Optimizer(); // Create the real optimizer instance
            optimizer.getConfig().orkPath = orkPath;
            optimizer.getConfig().stabilityRange = new double[]{stabilityMin, stabilityMax};

            // Set range for altitude and duration from the table (these are not clamped)
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                Object minObj = tableModel.getValueAt(row, 3);
                Object maxObj = tableModel.getValueAt(row, 4);
                double minVal, maxVal;

                try {
                     minVal = (minObj != null && !minObj.toString().isEmpty()) 
                         ? Double.parseDouble(minObj.toString()) 
                         : Double.NEGATIVE_INFINITY;
                    
                     maxVal = (maxObj != null && !maxObj.toString().isEmpty()) 
                         ? Double.parseDouble(maxObj.toString()) 
                         : Double.POSITIVE_INFINITY; // Use POSITIVE_INFINITY for unlimited

                    if ("Apogee".equals(paramName)) {
                        optimizer.getConfig().altitudeRange[0] = minVal;
                        optimizer.getConfig().altitudeRange[1] = maxVal;
                    } else if ("Duration".equals(paramName)) {
                        optimizer.getConfig().durationRange[0] = minVal;
                        optimizer.getConfig().durationRange[1] = maxVal;
                    }
                } catch (NumberFormatException ex) {
                     // Handle cases where Apogee/Duration bounds might be invalid, though ideally caught earlier
                     JOptionPane.showMessageDialog(OptimizerGUI.this, 
                         "Invalid number format for bounds of " + paramName, "Error", JOptionPane.ERROR_MESSAGE);
                     startButton.setEnabled(true); // Re-enable start button
                     cancelButton.setEnabled(false);
                     return;
                }
            }

            // Set enabled parameters map
            Map<String, Boolean> enabledParamsMap = new HashMap<>();
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String displayName = (String) tableModel.getValueAt(row, 1);
                Boolean enabled = (Boolean) tableModel.getValueAt(row, 0);
                // Use the mapping function to get the correct key for the optimizer config
                String optimizerKey = getOptimizerParamKey(displayName);
                enabledParamsMap.put(optimizerKey, enabled); 
            }
            optimizer.getConfig().enabledParams = enabledParamsMap;

            // Set parameter bounds for the real optimizer using values from the (potentially updated) table
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String paramName = (String) tableModel.getValueAt(row, 1);
                Boolean enabled = (Boolean) tableModel.getValueAt(row, 0);

                // Skip non-optimizable parameters and disabled ones
                 if (!enabled || paramName.contains("Parachute") || "Apogee".equals(paramName) || "Duration".equals(paramName)) {
                    continue;
                }

                Object minObj = tableModel.getValueAt(row, 3);
                Object maxObj = tableModel.getValueAt(row, 4);
                
                try {
                    double minFromTable;
                    double maxFromTable;

                    if (minObj == null || minObj.toString().isEmpty()) {
                        minFromTable = Double.NEGATIVE_INFINITY; // No minimum limit
                    } else {
                         if ("Number of Fins".equals(paramName)) {
                             minFromTable = Integer.parseInt(minObj.toString());
                         } else {
                             minFromTable = Double.parseDouble(minObj.toString());
                         }
                    }
                    
                    if (maxObj == null || maxObj.toString().isEmpty()) {
                        maxFromTable = Double.POSITIVE_INFINITY; // Represent unlimited max
                    } else {
                         if ("Number of Fins".equals(paramName)) {
                            maxFromTable = Integer.parseInt(maxObj.toString());
                         } else {
                            maxFromTable = Double.parseDouble(maxObj.toString());
                         }
                    }
                    
                    // Pass the values read directly from the table (which were not modified by clamping)
                    // The updateParameterBounds method in Optimizer will handle the final clamping logic.
                    optimizer.updateParameterBounds(paramName, minFromTable, maxFromTable);

                } catch (NumberFormatException ex) {
                    // This shouldn't happen if validation passed, but handle defensively
                    JOptionPane.showMessageDialog(OptimizerGUI.this,
                            "Internal error setting bounds for " + paramName + ": " + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    startButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                    return;
                }
            }

            // --- Start SwingWorker ---
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

                        // Set parachute selections if available
                        if (stage1ParachuteRow >= 0) {
                            Object value = tableModel.getValueAt(stage1ParachuteRow, 2);
                            String parachuteName = (value != null && !value.toString().isEmpty()) 
                                ? value.toString() : "None";
                            optimizer.setStage1Parachute(parachuteName);
                        }
                        
                        if (stage2ParachuteRow >= 0) {
                            Object value = tableModel.getValueAt(stage2ParachuteRow, 2);
                            String parachuteName = (value != null && !value.toString().isEmpty()) 
                                ? value.toString() : "None";
                            optimizer.setStage2Parachute(parachuteName);
                        }

                        optimizer.initialize();
                        // Show initial values from the file
                        Map<String, Double> initialValues = optimizer.getInitialValues();
                        publish("INITIAL_VALUES:" + serializeMap(initialValues));
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
                        } else if (chunk.startsWith("INITIAL_VALUES:")) {
                            String data = chunk.substring(14);
                            Map<String, Double> initialValues = deserializeMap(data);
                            updateCurrentParameters(initialValues);
                        } else if (chunk.startsWith("INTERIM:")) {
                            String data = chunk.substring(8);
                            Map<String, Double> currentParams = new HashMap<>();
                            Map<String, String> stringParams = new HashMap<>();
                            
                            for (String pair : data.split("\\|")) {
                                String[] kv = pair.split("=");
                                if (kv.length == 2) {
                                    String key = kv[0];
                                    String value = kv[1];
                                    
                                    // Handle parachute parameters as strings
                                    if (key.equals("stage1Parachute") || key.equals("stage2Parachute")) {
                                        stringParams.put(key, value);
                                    } else {
                                        // Regular numeric parameters
                                        try {
                                            currentParams.put(key, Double.parseDouble(value));
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                            }
                            
                            // Update the table with both numeric and string parameters
                            updateCurrentParameters(currentParams);
                            
                            // Manually update parachute rows if needed
                            if (!stringParams.isEmpty()) {
                                for (int row = 0; row < tableModel.getRowCount(); row++) {
                                    String paramName = (String) tableModel.getValueAt(row, 1);
                                    
                                    if ("Stage 1 Parachute".equals(paramName) && stringParams.containsKey("stage1Parachute")) {
                                        String value = stringParams.get("stage1Parachute");
                                        if (value != null && !value.equals("None") && !value.equals("null")) {
                                            tableModel.setValueAt(value, row, 2);
                                        }
                                    } else if ("Stage 2 Parachute".equals(paramName) && stringParams.containsKey("stage2Parachute")) {
                                        String value = stringParams.get("stage2Parachute");
                                        if (value != null && !value.equals("None") && !value.equals("null")) {
                                            tableModel.setValueAt(value, row, 2);
                                        }
                                    }
                                }
                            }
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
                            int currentStep = Integer.parseInt(parts[1]);
                            int totalSteps = Integer.parseInt(parts[2]);

                            // Calculate overall progress percentage
                            double progressPercent = (totalSteps > 0) ? 
                                    Math.min(100.0, (double) currentStep / totalSteps * 100) : 0.0;
                            
                            String text = String.format("Progress: %.1f%%", progressPercent);
                            
                            progressBar.setString(text);
                            progressBar.setValue((int) progressPercent);
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
                    // Keep cancel button enabled to allow reverting to original values
                    boolean hasBest = optimizer != null && optimizer.hasBestValues();
                    saveInPlaceButton.setEnabled(hasBest);
                    saveAsButton.setEnabled(hasBest);
                    if (hasBest) {
                        updateResultsTable(optimizer.getBestValues());
                    }
                    if (!optimizer.isCancelled()) {
                        progressBar.setValue(100);
                        progressBar.setString("Optimization Complete");
                    }
                }
            };
            worker.execute();
        }

        private String serializeMap(Map<String, Double> map) {
            StringBuilder sb = new StringBuilder();
            
            // Add regular numeric parameters
            for (Map.Entry<String, Double> entry : map.entrySet()) {
                if (sb.length() > 0) sb.append("|");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            
            // Add parachute information separately
            if (optimizer != null) {
                if (sb.length() > 0) sb.append("|");
                sb.append("stage1Parachute=").append(optimizer.getBestStage1Parachute());
                
                if (sb.length() > 0) sb.append("|");
                sb.append("stage2Parachute=").append(optimizer.getBestStage2Parachute());
            }
            
            return sb.toString();
        }

        private Map<String, Double> deserializeMap(String data) {
            Map<String, Double> map = new HashMap<>();
            for (String pair : data.split("\\|")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    String key = kv[0];
                    String value = kv[1];
                    
                    // Skip parachute values in the numeric map - they'll be handled separately through the optimizer
                    if (key.equals("stage1Parachute") || key.equals("stage2Parachute")) {
                        continue;
                    } else {
                        // For regular numeric values
                        try {
                            map.put(key, Double.parseDouble(value));
                        } catch (NumberFormatException ignored) {
                            // Skip values that can't be parsed
                        }
                    }
                }
            }
            return map;
        }
    }

    /**
     * Setup combo boxes for parachute selection
     */
    private void setupParachuteComboBoxes() {
        // Find the row indices for parachute parameters
        stage1ParachuteRow = -1;
        stage2ParachuteRow = -1;
        
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String param = (String) tableModel.getValueAt(i, 1);
            if ("Stage 1 Parachute".equals(param)) {
                stage1ParachuteRow = i;
            } else if ("Stage 2 Parachute".equals(param)) {
                stage2ParachuteRow = i;
            }
        }
        
        // Load parachute presets if rows exist
        if (stage1ParachuteRow >= 0 || stage2ParachuteRow >= 0) {
            loadParachutePresets();
        }
        
        // Try to load initial parachute data from the file
        String filePath = filePathField.getText();
        if (!filePath.isEmpty() && new File(filePath).exists()) {
            try {
                Optimizer testOptimizer = new Optimizer();
                testOptimizer.getConfig().orkPath = filePath;
                testOptimizer.initialize();
                
                // Update table with parachute info
                if (stage1ParachuteRow >= 0) {
                    if (testOptimizer.hasStage1Parachute()) {
                        tableModel.setValueAt(testOptimizer.getBestStage1Parachute(), stage1ParachuteRow, 2);
                    } else {
                        tableModel.setValueAt("", stage1ParachuteRow, 2);
                    }
                }
                
                if (stage2ParachuteRow >= 0) {
                    if (testOptimizer.hasStage2Parachute()) {
                        tableModel.setValueAt(testOptimizer.getBestStage2Parachute(), stage2ParachuteRow, 2);
                    } else {
                        tableModel.setValueAt("", stage2ParachuteRow, 2);
                    }
                }
            } catch (Exception e) {
                // Ignore errors - might not be a valid file
            }
        }
    }
    
    /**
     * Load parachute presets from OpenRocket database
     */
    private void loadParachutePresets() {
        if (parachutePresets == null) {
            parachutePresets = new ArrayList<>();
            parachutePresets.add("None"); // Always include "None" option
            
            try {
                // Load from OpenRocket database
                List<ComponentPreset> presets = ParachuteHelper.getAllParachutePresets();
                for (ComponentPreset preset : presets) {
                    parachutePresets.add(ParachuteHelper.getPresetDisplayName(preset));
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, 
                    "Error loading parachute presets: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        // Create combo boxes with the presets
        JComboBox<String> stage1Combo = new JComboBox<>(parachutePresets.toArray(new String[0]));
        JComboBox<String> stage2Combo = new JComboBox<>(parachutePresets.toArray(new String[0]));
        
        // Set up cell editors for parachute rows
        if (stage1ParachuteRow >= 0) {
            resultsTable.getColumnModel().getColumn(2).setCellEditor(
                new DefaultCellEditor(stage1Combo) {
                    @Override
                    public boolean isCellEditable(EventObject anEvent) {
                        int row = resultsTable.getEditingRow();
                        return row == stage1ParachuteRow;
                    }
                });
        }
        
        if (stage2ParachuteRow >= 0) {
            // Use same column but only for stage 2 row
            resultsTable.getColumnModel().getColumn(2).setCellEditor(
                new DefaultCellEditor(stage2Combo) {
                    @Override
                    public boolean isCellEditable(EventObject anEvent) {
                        int row = resultsTable.getEditingRow();
                        return row == stage2ParachuteRow;
                    }
                });
        }
    }

    // Create a renderer that shows empty cells for parachutes in Min and Max columns
    private class ParachuteAwareCellRenderer extends DefaultTableCellRenderer {
        private final int columnIndex;
        
        public ParachuteAwareCellRenderer(int columnIndex) {
            this.columnIndex = columnIndex;
            setHorizontalAlignment(JLabel.CENTER);
        }
        
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                      boolean isSelected, boolean hasFocus,
                                                      int row, int column) {
            // Get the default renderer component
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            
            // Check if this is a parachute row
            String paramName = (String) table.getValueAt(row, 1);
            if ("Stage 1 Parachute".equals(paramName) || "Stage 2 Parachute".equals(paramName)) {
                // For parachute rows, leave them blank (no text)
                setText("");
                setForeground(Color.GRAY);
                setHorizontalAlignment(JLabel.CENTER);
                
                // Disable the cell by making it look disabled
                setBackground(isSelected ? table.getSelectionBackground() : new Color(240, 240, 240));
            } else {
                // For other rows with empty values, show "Unlimited"
                if (value == null || value.toString().isEmpty()) {
                    setText("Unlimited");
                    setForeground(Color.GRAY);
                    setHorizontalAlignment(JLabel.CENTER);
                } else {
                    setText(value.toString());
                    setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                    setHorizontalAlignment(JLabel.LEFT);
                }
                
                setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            }
            
            return c;
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