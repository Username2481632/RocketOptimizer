import net.sf.openrocket.aerodynamics.AerodynamicCalculator;
import net.sf.openrocket.aerodynamics.BarrowmanCalculator;
import net.sf.openrocket.aerodynamics.FlightConditions;
import net.sf.openrocket.document.OpenRocketDocument;
import net.sf.openrocket.document.StorageOptions;
import net.sf.openrocket.file.GeneralRocketLoader;
import net.sf.openrocket.file.RocketSaver;
import net.sf.openrocket.file.openrocket.OpenRocketSaver;
import net.sf.openrocket.logging.ErrorSet;
import net.sf.openrocket.logging.WarningSet;
import net.sf.openrocket.masscalc.MassCalculator;
import net.sf.openrocket.masscalc.RigidBody;
import net.sf.openrocket.preset.ComponentPreset;
import net.sf.openrocket.rocketcomponent.*;
import net.sf.openrocket.simulation.SimulationOptions;
import net.sf.openrocket.util.Coordinate;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Optimizer {
    private final List<FinParameter> parameters = new ArrayList<>();
    private final OptimizationConfig config = new OptimizationConfig();
    private final Map<String, Double> bestValues = new HashMap<>();
    private EllipticalFinSet fins;
    private BodyTube bodyTube;
    private NoseCone noseCone;
    private OpenRocketDocument document;
    private Rocket rocket;
    private SimulationOptions baseOptions;
    private double bestError = Double.MAX_VALUE;
    private LogListener logListener;
    private StatusListener statusListener;
    private ProgressListener progressListener;
    private final int phases = 5;
    private final double errorThreshold = 1.0;
    private Map<String, Double> originalValues = new HashMap<>();
    private volatile boolean cancelled = false;
    
    // Add overall progress tracking
    private int totalMajorSteps = 0;
    private int currentMajorStep = 0;
    
    // Parachute-related fields
    private Parachute stage1Parachute;
    private Parachute stage2Parachute;
    private ComponentPreset originalStage1Preset;
    private ComponentPreset originalStage2Preset;
    private String currentStage1Parachute = "None";
    private String currentStage2Parachute = "None";
    private String bestStage1Parachute = "None";
    private String bestStage2Parachute = "None";

    public Optimizer() {
        // Use derived minimums and Double.MAX_VALUE for maximums where no explicit limit exists
        // Fin parameters units seem to be cm in the FinParameter constructor, converted later. Keep consistent for now.
        parameters.add(new FinParameter("thickness", 0.0, Double.MAX_VALUE, 0.05, v -> v / 100)); // Min 0
        parameters.add(new FinParameter("rootChord", 0.0, Double.MAX_VALUE, 1.0, v -> v / 100));  // Min 0
        parameters.add(new FinParameter("height", 0.0, Double.MAX_VALUE, 1.0, v -> v / 100));     // Min 0
        parameters.add(new FinParameter("finCount", 1.0, 8.0, 1.0, v -> v));                       // Min 1, Max 8
        parameters.add(new FinParameter("noseLength", 0.0, Double.MAX_VALUE, 1.0, v -> v / 100)); // Min 0
        // Practical max is nose base radius, but no hard upper limit in code. Use MAX_VALUE.
        parameters.add(new FinParameter("noseWallThickness", 0.0, Double.MAX_VALUE, 0.05, v -> v / 100)); // Min 0
    }

    public static class OptimizationConfig {
        public String orkPath = "rocket.ork"; // Keep default path? Or make null? Let's keep it for now.
        public double[] altitudeRange = new double[2]; // Min and max altitude range
        public double[] stabilityRange = new double[2]; // Remove default
        public double[] durationRange = new double[2];  // Remove default
        public Map<String, Boolean> enabledParams = new HashMap<>();
    }

    // Make FinParameter public and static so it can be accessed from OptimizerGUI
    public static class FinParameter {
        final String name;
        double absoluteMin;
        double absoluteMax;
        final Function<Double, Double> converter;
        double currentMin;
        double currentMax;
        double step;
        double currentValue;
        boolean hasMaxLimit;

        FinParameter(String name, double absoluteMin, double absoluteMax, double initialStep, Function<Double, Double> converter) {
            this.name = name;
            this.absoluteMin = absoluteMin;
            this.absoluteMax = absoluteMax;
            this.currentMin = absoluteMin;
            this.currentMax = absoluteMax;
            this.step = initialStep;
            this.converter = converter;
            this.hasMaxLimit = (absoluteMax != Double.MAX_VALUE);
        }
    }

    public interface LogListener {
        void log(String message);
    }

    public interface StatusListener {
        void updateStatus(String status);
    }

    public interface ProgressListener {
        void updateProgress(int currentPhase, int currentEval, int totalEval, int totalPhases);
    }

    public OptimizationConfig getConfig() {
        return config;
    }

    public boolean hasBestValues() {
        return !bestValues.isEmpty();
    }

    public Map<String, Double> getBestValues() {
        return new HashMap<>(bestValues);
    }

    public void initialize() throws Exception {
        File rocketFile = new File(config.orkPath);
        GeneralRocketLoader loader = new GeneralRocketLoader(rocketFile);
        document = loader.load();
        rocket = document.getRocket();

        fins = findLastEllipticalFinSet(rocket);
        if (fins == null) {
            throw new RuntimeException("No elliptical fin set found");
        }
        bodyTube = (BodyTube) fins.getParent();
        noseCone = findNoseCone(rocket);
        if (noseCone == null) {
            throw new RuntimeException("No nose cone found");
        }
        baseOptions = document.getSimulations().get(0).getOptions();

        // --- Update dynamic bounds --- 
        // Get Nose Cone Base Radius (in meters from OpenRocket)
        double noseBaseRadiusMeters = noseCone.getBaseRadius();
        // Max thickness is the radius (half the diameter). Convert to cm for the parameter.
        double maxNoseThicknessCm = noseBaseRadiusMeters * 100; 
        
        // Find maximum fin dimensions based on body tube
        double bodyTubeLengthCm = bodyTube.getLength() * 100; // Convert to cm
        double bodyTubeRadiusCm = bodyTube.getOuterRadius() * 100; // Convert to cm
        
        // Update parameter bounds based on physical limitations
        for (FinParameter param : parameters) {
            if ("noseWallThickness".equals(param.name)) {
                // Set the max bound, ensuring it doesn't exceed the previously set MAX_VALUE
                param.absoluteMax = Math.min(param.absoluteMax, maxNoseThicknessCm);
                param.currentMax = param.absoluteMax; // Initialize currentMax to the new absoluteMax
                param.hasMaxLimit = (param.absoluteMax != Double.MAX_VALUE); // Update hasMaxLimit
                log("Updated noseWallThickness max bound to: " + maxNoseThicknessCm + " cm");
            } else if ("rootChord".equals(param.name)) {
                // Root chord shouldn't exceed body tube length
                param.absoluteMax = Math.min(param.absoluteMax, bodyTubeLengthCm);
                param.currentMax = param.absoluteMax;
                param.hasMaxLimit = (param.absoluteMax != Double.MAX_VALUE);
                log("Updated rootChord max bound to: " + bodyTubeLengthCm + " cm");
            } else if ("height".equals(param.name)) {
                // Fin height (span) should have a reasonable upper limit based on body tube radius
                // A practical maximum would be ~3x the body tube radius
                double maxHeightCm = bodyTubeRadiusCm * 3;
                param.absoluteMax = Math.min(param.absoluteMax, maxHeightCm);
                param.currentMax = param.absoluteMax;
                param.hasMaxLimit = (param.absoluteMax != Double.MAX_VALUE);
                log("Updated fin height max bound to: " + maxHeightCm + " cm");
            }
        }
        // --- End Update dynamic bounds --- 

        originalValues.put("thickness", fins.getThickness());
        originalValues.put("rootChord", fins.getLength());
        originalValues.put("height", fins.getHeight());
        originalValues.put("finCount", (double) fins.getFinCount());
        originalValues.put("noseLength", noseCone.getLength());
        originalValues.put("noseWallThickness", noseCone.getThickness());
        
        // Find parachutes in the rocket
        stage1Parachute = findFirstParachute(rocket);
        stage2Parachute = findSecondParachute(rocket);
        
        // Store original presets if parachutes are found
        if (stage1Parachute != null) {
            originalStage1Preset = stage1Parachute.getPresetComponent();
            currentStage1Parachute = originalStage1Preset != null ? 
                ParachuteHelper.getPresetDisplayName(originalStage1Preset) : "Default Parachute";
            bestStage1Parachute = currentStage1Parachute;
        } else {
            // No parachute found
            originalStage1Preset = null;
            currentStage1Parachute = "";
            bestStage1Parachute = "";
        }
        
        if (stage2Parachute != null) {
            originalStage2Preset = stage2Parachute.getPresetComponent();
            currentStage2Parachute = originalStage2Preset != null ? 
                ParachuteHelper.getPresetDisplayName(originalStage2Preset) : "Default Parachute";
            bestStage2Parachute = currentStage2Parachute;
        } else {
            // No parachute found
            originalStage2Preset = null;
            currentStage2Parachute = "";
            bestStage2Parachute = "";
        }
    }

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    private EllipticalFinSet findLastEllipticalFinSet(RocketComponent component) {
        List<EllipticalFinSet> finSets = new ArrayList<>();
        findFinSetsRecursive(component, finSets);
        return finSets.isEmpty() ? null : finSets.get(finSets.size() - 1);
    }

    private void findFinSetsRecursive(RocketComponent component, List<EllipticalFinSet> results) {
        if (component instanceof EllipticalFinSet) {
            results.add((EllipticalFinSet) component);
        }
        for (RocketComponent child : component.getChildren()) {
            findFinSetsRecursive(child, results);
        }
    }

    private void optimizeRecursive(int paramIndex) {
        if (cancelled) return;
        
        if (paramIndex >= parameters.size()) {
            evaluateConfiguration();
            return;
        }

        FinParameter param = parameters.get(paramIndex);
        // Use the internal param.name as the key for the enabled check
        boolean isEnabled = config.enabledParams.getOrDefault(param.name, true);
        
        if (!isEnabled) {
            param.currentValue = originalValues.get(param.name);
            optimizeRecursive(paramIndex + 1);
            return;
        }

        // Handle special cases
        if (param.currentMin == Double.NEGATIVE_INFINITY && param.currentMax == Double.MAX_VALUE) {
            // Special case for finCount - use only positive integers
            if ("finCount".equals(param.name)) {
                double[] valuesToTry = {1.0, 2.0, 3.0, 4.0, 6.0, 8.0};
                for (double value : valuesToTry) {
                    if (cancelled) return;
                    param.currentValue = value;
                    optimizeRecursive(paramIndex + 1);
                }
                return;
            }
            
            // Both min and max are unlimited, try a reasonable set of values
            // Use only non-negative values for dimension parameters
            double[] valuesToTry = {0.0, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0};
            for (double value : valuesToTry) {
                if (cancelled) return;
                param.currentValue = value;
                optimizeRecursive(paramIndex + 1);
            }
            return;
        } else if (param.currentMin == Double.NEGATIVE_INFINITY) {
            // Min is unlimited but max is not
            if ("finCount".equals(param.name)) {
                // For finCount, use integer values up to max
                int maxVal = (int)Math.min(8, param.currentMax);
                for (int i = 1; i <= maxVal && !cancelled; i++) {
                    param.currentValue = i;
                    optimizeRecursive(paramIndex + 1);
                }
                return;
            } else {
                // For non-finCount parameters, try a few values below max
                // and also include max itself, ensuring non-negative values
                double maxVal = param.currentMax;
                double[] valuesToTry = {0.0, maxVal/16, maxVal/8, maxVal/4, maxVal/2, maxVal};
                for (double value : valuesToTry) {
                    if (cancelled) return;
                    param.currentValue = value;
                    optimizeRecursive(paramIndex + 1);
                }
                return;
            }
        } else if (!param.hasMaxLimit) {
            // Max is unlimited but min is not, try a few values starting from min
            double minVal = Math.max(0, param.currentMin);
            double value = minVal;
            
            // Special handling for zero-based parameters
            if (minVal == 0 && param.step > 0) {
                value = param.step; // Start with the first step size
                for (int i = 0; i < 5 && !cancelled; i++) {
                    param.currentValue = value;
                    optimizeRecursive(paramIndex + 1);
                    value *= 2;  // Double the value each time
                }
            } else {
                for (int i = 0; i < 5 && !cancelled; i++) { 
                    param.currentValue = value;
                    optimizeRecursive(paramIndex + 1);
                    value *= 2;
                }
            }
            return;
        } else if (Math.abs(param.currentMax - param.currentMin) < 1e-6) {
            // Min equals max, just use that value
            param.currentValue = param.currentMin;
            optimizeRecursive(paramIndex + 1);
            return;
        }

        // Standard case: step from min to max
        if ("finCount".equals(param.name)) {
            // For finCount, use integer values only
            int minVal = (int)Math.max(1, Math.ceil(param.currentMin));
            int maxVal = (int)Math.min(8, Math.floor(param.currentMax));
            for (int value = minVal; value <= maxVal && !cancelled; value++) {
                param.currentValue = value;
                optimizeRecursive(paramIndex + 1);
            }
        } else {
            // For other parameters, use regular stepping
            double min = Math.max(0, param.currentMin); // Ensure non-negative values
            for (double value = min; value <= param.currentMax && !cancelled; value += param.step) {
                param.currentValue = value;
                optimizeRecursive(paramIndex + 1);
            }
        }
    }

    private String getDisplayName(String paramName) {
        switch (paramName) {
            case "thickness": return "Fin Thickness";
            case "rootChord": return "Root Chord";
            case "height": return "Fin Height";
            case "finCount": return "Number of Fins";
            case "noseLength": return "Nose Cone Length";
            case "noseWallThickness": return "Nose Cone Wall Thickness";
            default: return paramName;
        }
    }

    private void evaluateConfiguration() {
        // Update status listener if needed (consider if this level of detail is still desired)
        if (statusListener != null) {
            StringBuilder status = new StringBuilder();
            for (FinParameter param : parameters) {
                // Use internal param.name for key lookup
                if (config.enabledParams.getOrDefault(param.name, true)) {
                    status.append(String.format("%s=%.2f ", param.name, param.currentValue));
                }
            }
            // Add parachute status if needed
            if (config.enabledParams.getOrDefault("Stage 1 Parachute", true) && stage1Parachute != null) {
                 status.append(String.format("Stage1Parachute=%s ", currentStage1Parachute));
            }
             if (config.enabledParams.getOrDefault("Stage 2 Parachute", true) && stage2Parachute != null) {
                 status.append(String.format("Stage2Parachute=%s ", currentStage2Parachute));
            }
            statusListener.updateStatus(status.toString());
        }

        try {
            // Apply numeric parameter values only if enabled
            for (FinParameter param : parameters) {
                // Use internal param.name as key
                boolean isEnabled = config.enabledParams.getOrDefault(param.name, true);
                
                if (!isEnabled) {
                    // If parameter is disabled, ensure it's set to the original value
                    switch (param.name) {
                        case "thickness":
                            fins.setThickness(originalValues.get("thickness"));
                            break;
                        case "rootChord":
                            fins.setLength(originalValues.get("rootChord"));
                            break;
                        case "height":
                            fins.setHeight(originalValues.get("height"));
                            break;
                        case "finCount":
                            fins.setFinCount(originalValues.get("finCount").intValue());
                            break;
                        case "noseLength":
                            noseCone.setLength(originalValues.get("noseLength"));
                            break;
                        case "noseWallThickness":
                            noseCone.setThickness(originalValues.get("noseWallThickness"));
                            break;
                    }
                } else {
                    // If parameter is enabled, apply the current test value
                    switch (param.name) {
                        case "thickness":
                            fins.setThickness(param.converter.apply(param.currentValue));
                            break;
                        case "rootChord":
                            fins.setLength(param.converter.apply(param.currentValue));
                            break;
                        case "height":
                            fins.setHeight(param.converter.apply(param.currentValue));
                            break;
                        case "finCount":
                            fins.setFinCount((int)Math.round(param.currentValue));
                            break;
                        case "noseLength":
                            noseCone.setLength(param.converter.apply(param.currentValue));
                            break;
                        case "noseWallThickness":
                            noseCone.setThickness(param.converter.apply(param.currentValue));
                            break;
                    }
                }
            }
            
            // Apply parachute presets if parachutes are available and enabled
            if (stage1Parachute != null) {
                boolean isEnabled = config.enabledParams.getOrDefault("Stage 1 Parachute", true);
                if (isEnabled) {
                    ComponentPreset preset = ParachuteHelper.findPresetByDisplayName(currentStage1Parachute);
                    if (preset != null) {
                        ParachuteHelper.applyPreset(stage1Parachute, preset);
                    } else if ("None".equals(currentStage1Parachute)) {
                        // Restore original preset if "None" selected
                        ParachuteHelper.applyPreset(stage1Parachute, originalStage1Preset);
                    }
                }
            }
            
            if (stage2Parachute != null) {
                boolean isEnabled = config.enabledParams.getOrDefault("Stage 2 Parachute", true);
                if (isEnabled) {
                    ComponentPreset preset = ParachuteHelper.findPresetByDisplayName(currentStage2Parachute);
                    if (preset != null) {
                        ParachuteHelper.applyPreset(stage2Parachute, preset);
                    } else if ("None".equals(currentStage2Parachute)) {
                        // Restore original preset if "None" selected
                        ParachuteHelper.applyPreset(stage2Parachute, originalStage2Preset);
                    }
                }
            }

            rocket.enableEvents();
            FlightConfigurationId configId = document.getSelectedConfiguration().getId();
            rocket.fireComponentChangeEvent(ComponentChangeEvent.AERODYNAMIC_CHANGE, configId);

            double stability = calculateStability();
            if (stability < config.stabilityRange[0] || stability > config.stabilityRange[1]) {
                logCurrent("Skipping", stability, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Stability out of range");
                return;
            }

            SimulationStepper.SimulationResult result = new SimulationStepper(rocket, baseOptions).runSimulation();
            double apogee = result.altitude;
            double duration = result.duration;

            double altitudeScore = config.enabledParams.getOrDefault("Altitude Score", true) ?
                    calculateAltitudeScore(apogee, config.altitudeRange[0], config.altitudeRange[1]) : 0;
            double durationScore = config.enabledParams.getOrDefault("Duration Score", true) ?
                    calculateDurationScore(duration, config.durationRange[0], config.durationRange[1]) : 0;
            double totalError = altitudeScore + durationScore;

            if (logListener != null) {
                StringBuilder interimData = new StringBuilder();
                // Add numeric parameters directly from components (converted to cm for consistency if needed)
                interimData.append(String.format("thickness=%.2f|rootChord=%.2f|height=%.2f|finCount=%.0f|noseLength=%.2f|noseWallThickness=%.2f",
                        fins.getThickness() * 100, // Convert m to cm
                        fins.getLength() * 100,    // Convert m to cm
                        fins.getHeight() * 100,    // Convert m to cm
                        (double)fins.getFinCount(),
                        noseCone.getLength() * 100, // Convert m to cm
                        noseCone.getThickness() * 100 // Convert m to cm
                        ));
                
                // Add parachute values as string parameters (not parsed as doubles)
                interimData.append(String.format("|stage1Parachute=%s|stage2Parachute=%s", 
                        currentStage1Parachute, currentStage2Parachute));
                
                // Add simulation results
                interimData.append(String.format("|apogee=%.1f|duration=%.2f|altitudeScore=%.1f|durationScore=%.2f|totalScore=%.2f",
                        apogee, duration, altitudeScore, durationScore, altitudeScore + durationScore));
                
                logListener.log("INTERIM:" + interimData.toString());
            }

            if (totalError < bestError) {
                bestError = totalError;
                updateBestValues(apogee, duration, altitudeScore, durationScore);
                logCurrent("Best", stability, apogee, duration, altitudeScore, durationScore, null);
                rocket.fireComponentChangeEvent(ComponentChangeEvent.TREE_CHANGE);
            }
        } catch (Exception e) {
            logCurrent("Failed", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, e.getMessage());
        }
    }

    private double calculateAltitudeScore(double actualAltitude, double min, double max) {
        // If min is "unlimited" (negative infinity), only check upper bound
        if (min == Double.NEGATIVE_INFINITY) {
            return (actualAltitude <= max) ? 0 : (actualAltitude - max);
        }
        // If max is "unlimited" (MAX_VALUE), only check lower bound
        else if (max == Double.MAX_VALUE) {
            return (actualAltitude >= min) ? 0 : (min - actualAltitude);
        }
        // Both bounds are specified
        else if (actualAltitude >= min && actualAltitude <= max) {
            return 0;
        }
        // Outside of bounds
        else {
            double boundary = (actualAltitude < min) ? min : max;
            return Math.abs(actualAltitude - boundary);
        }
    }

    private double calculateDurationScore(double actualDuration, double min, double max) {
        // If min is "unlimited" (negative infinity), only check upper bound
        if (min == Double.NEGATIVE_INFINITY) {
            return (actualDuration <= max) ? 0 : (actualDuration - max) * 4;
        }
        // If max is "unlimited" (MAX_VALUE), only check lower bound
        else if (max == Double.MAX_VALUE) {
            return (actualDuration >= min) ? 0 : (min - actualDuration) * 4;
        }
        // Both bounds are specified
        else if (actualDuration >= min && actualDuration <= max) {
            return 0;
        }
        // Outside of bounds
        else {
            double boundary = (actualDuration < min) ? min : max;
            return Math.abs(actualDuration - boundary) * 4;
        }
    }

    public void optimizeFins() {
        // Reset cancel flag
        cancelled = false;
        currentMajorStep = 0; // Reset progress
        
        for (FinParameter param : parameters) {
            if (config.enabledParams.getOrDefault(param.name, true)) {
                if (param.absoluteMin > param.absoluteMax) {
                    throw new IllegalStateException("Invalid bounds for parameter: " + getDisplayName(param.name));
                }
            }
        }

        bestError = Double.MAX_VALUE;
        
        // Determine optimization type and calculate total steps
        boolean parachute1Enabled = config.enabledParams.getOrDefault("Stage 1 Parachute", true) && stage1Parachute != null;
        boolean parachute2Enabled = config.enabledParams.getOrDefault("Stage 2 Parachute", true) && stage2Parachute != null;
        boolean anyParachutesEnabled = parachute1Enabled || parachute2Enabled;
        boolean anyNumericParamsEnabled = false;
        for (FinParameter param : parameters) {
            if (config.enabledParams.getOrDefault(param.name, true)) {
                anyNumericParamsEnabled = true;
                break;
            }
        }
        boolean onlyParachutesEnabled = anyParachutesEnabled && !anyNumericParamsEnabled;

        // Calculate total major steps for the progress bar
        if (onlyParachutesEnabled) {
            List<ComponentPreset> presets = ParachuteHelper.getAllParachutePresets();
            int numPresets = (presets != null) ? presets.size() : 0;
            int stage1Opts = parachute1Enabled ? (numPresets + 1) : 1; // +1 for "None"
            int stage2Opts = parachute2Enabled ? (numPresets + 1) : 1; // +1 for "None"
            totalMajorSteps = stage1Opts * stage2Opts;
            optimizeParachutesOnly();
        } else if (anyParachutesEnabled && anyNumericParamsEnabled) {
            List<ComponentPreset> presets = ParachuteHelper.getAllParachutePresets();
            int numPresets = (presets != null) ? presets.size() : 0;
            int stage1Opts = parachute1Enabled ? (numPresets + 1) : 1;
            int stage2Opts = parachute2Enabled ? (numPresets + 1) : 1;
            totalMajorSteps = stage1Opts * stage2Opts * phases;
            optimizeAllParameters();
        } else if (anyNumericParamsEnabled) { // Only numeric
            totalMajorSteps = phases;
            optimizeAllParameters(); // This method handles the case where optimizeParachutes is false
        } else {
            // Nothing enabled?
            totalMajorSteps = 1;
            if (progressListener != null) {
                progressListener.updateProgress(0, 1, 1, 1);
            }
            logListener.log("No parameters enabled for optimization.");
        }
        
        // Ensure progress bar reaches 100% if not cancelled
        if (!cancelled && progressListener != null) {
             progressListener.updateProgress(0, totalMajorSteps, totalMajorSteps, 1);
        }

        logFinalResults();
    }

    // Method to optimize only parachutes, without running the recursive parameter optimization
    private void optimizeParachutesOnly() {
        if (logListener != null) {
            logListener.log("Optimizing parachutes only");
        }
        
        // Get parachute presets
        List<ComponentPreset> parachutePresets = ParachuteHelper.getAllParachutePresets();
        if (parachutePresets == null || parachutePresets.isEmpty()) {
            if (logListener != null) {
                logListener.log("No parachute presets found");
            }
            return;
        }
        
        // Check which parachutes are enabled
        boolean parachute1Enabled = config.enabledParams.getOrDefault("Stage 1 Parachute", true) && stage1Parachute != null;
        boolean parachute2Enabled = config.enabledParams.getOrDefault("Stage 2 Parachute", true) && stage2Parachute != null;
        
        // Create lists of parachute options to try
        List<String> stage1Options = new ArrayList<>();
        if (parachute1Enabled) {
            stage1Options.add("None"); // No parachute option
            for (ComponentPreset preset : parachutePresets) {
                stage1Options.add(ParachuteHelper.getPresetDisplayName(preset));
            }
        } else {
            // Just use the current parachute setting if not enabled for optimization
            stage1Options.add(currentStage1Parachute);
        }
        
        List<String> stage2Options = new ArrayList<>();
        if (parachute2Enabled) {
            stage2Options.add("None"); // No parachute option
            for (ComponentPreset preset : parachutePresets) {
                stage2Options.add(ParachuteHelper.getPresetDisplayName(preset));
            }
        } else {
            // Just use the current parachute setting if not enabled for optimization
            stage2Options.add(currentStage2Parachute);
        }
        
        // Calculate total evaluations for progress tracking
        int totalEvaluations = stage1Options.size() * stage2Options.size();
        int currentEvaluation = 0;
        
        // Try different parachute combinations
        for (String stage1Option : stage1Options) {
            for (String stage2Option : stage2Options) {
                if (cancelled) break; // Break inner loop (stage2Options)
                
                currentMajorStep++; // Increment overall progress
                if (progressListener != null) {
                    // Report progress based on overall steps
                    progressListener.updateProgress(0, currentMajorStep, totalMajorSteps, 1);
                }
                
                // Log which parachute settings we're trying with clear indication of what's changing
                if (logListener != null) {
                    StringBuilder message = new StringBuilder("Trying parachute combination: ");
                    if (parachute1Enabled) {
                        message.append("Stage 1 = ").append(stage1Option);
                    } else {
                        message.append("Stage 1 = unchanged (").append(currentStage1Parachute).append(")");
                    }
                    message.append(", ");
                    if (parachute2Enabled) {
                        message.append("Stage 2 = ").append(stage2Option);
                    } else {
                        message.append("Stage 2 = unchanged (").append(currentStage2Parachute).append(")");
                    }
                    logListener.log(message.toString());
                }
                
                // Only change parachutes that are enabled for optimization
                if (parachute1Enabled) {
                    currentStage1Parachute = stage1Option;
                }
                
                if (parachute2Enabled) {
                    currentStage2Parachute = stage2Option;
                }
                
                // Apply parachute presets
                if (stage1Parachute != null && parachute1Enabled) {
                    ComponentPreset preset = ParachuteHelper.findPresetByDisplayName(stage1Option);
                    if (preset != null) {
                        ParachuteHelper.applyPreset(stage1Parachute, preset);
                    } else if ("None".equals(stage1Option)) {
                        // Restore original preset if "None" selected
                        ParachuteHelper.applyPreset(stage1Parachute, originalStage1Preset);
                    }
                }
                
                if (stage2Parachute != null && parachute2Enabled) {
                    ComponentPreset preset = ParachuteHelper.findPresetByDisplayName(stage2Option);
                    if (preset != null) {
                        ParachuteHelper.applyPreset(stage2Parachute, preset);
                    } else if ("None".equals(stage2Option)) {
                        // Restore original preset if "None" selected
                        ParachuteHelper.applyPreset(stage2Parachute, originalStage2Preset);
                    }
                }
                
                rocket.enableEvents();
                FlightConfigurationId configId = document.getSelectedConfiguration().getId();
                rocket.fireComponentChangeEvent(ComponentChangeEvent.AERODYNAMIC_CHANGE, configId);
                
                // Run simulation and evaluate result
                try {
                    double stability = calculateStability();
                    if (stability < config.stabilityRange[0] || stability > config.stabilityRange[1]) {
                        logCurrent("Skipping", stability, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Stability out of range");
                        continue;
                    }
                    
                    SimulationStepper.SimulationResult result = new SimulationStepper(rocket, baseOptions).runSimulation();
                    double apogee = result.altitude;
                    double duration = result.duration;
                    
                    double altitudeScore = config.enabledParams.getOrDefault("Altitude Score", true) ?
                            calculateAltitudeScore(apogee, config.altitudeRange[0], config.altitudeRange[1]) : 0;
                    double durationScore = config.enabledParams.getOrDefault("Duration Score", true) ?
                            calculateDurationScore(duration, config.durationRange[0], config.durationRange[1]) : 0;
                    double totalError = altitudeScore + durationScore;
                    
                    // Update the interim values for UI display
                    if (logListener != null) {
                        StringBuilder interimData = new StringBuilder();
                        // Add numeric parameters directly from components (converted to cm for consistency if needed)
                        interimData.append(String.format("thickness=%.2f|rootChord=%.2f|height=%.2f|finCount=%.0f|noseLength=%.2f|noseWallThickness=%.2f",
                                fins.getThickness() * 100, // Convert m to cm
                                fins.getLength() * 100,    // Convert m to cm
                                fins.getHeight() * 100,    // Convert m to cm
                                (double)fins.getFinCount(),
                                noseCone.getLength() * 100, // Convert m to cm
                                noseCone.getThickness() * 100 // Convert m to cm
                                ));
                        
                        // Add parachute values as string parameters (not parsed as doubles)
                        interimData.append(String.format("|stage1Parachute=%s|stage2Parachute=%s", 
                                currentStage1Parachute, currentStage2Parachute));
                        
                        // Add simulation results
                        interimData.append(String.format("|apogee=%.1f|duration=%.2f|altitudeScore=%.1f|durationScore=%.2f|totalScore=%.2f",
                                apogee, duration, altitudeScore, durationScore, altitudeScore + durationScore));
                        
                        logListener.log("INTERIM:" + interimData.toString());
                    }
                    
                    if (totalError < bestError) {
                        bestError = totalError;
                        
                        // Update the best values - fetch current numeric values from components
                        Map<String, Double> currentValues = new HashMap<>();
                        currentValues.put("thickness", fins.getThickness() * 100);
                        currentValues.put("rootChord", fins.getLength() * 100);
                        currentValues.put("height", fins.getHeight() * 100);
                        currentValues.put("finCount", (double) fins.getFinCount());
                        currentValues.put("noseLength", noseCone.getLength() * 100);
                        currentValues.put("noseWallThickness", noseCone.getThickness() * 100);
                        // Add simulation results
                        currentValues.put("apogee", apogee);
                        currentValues.put("duration", duration);
                        currentValues.put("altitudeScore", altitudeScore);
                        currentValues.put("durationScore", durationScore);
                        currentValues.put("totalScore", altitudeScore + durationScore);
                        bestValues.clear();
                        bestValues.putAll(currentValues);
                        
                        // Save the current parachute selections with the best values
                        bestStage1Parachute = currentStage1Parachute;
                        bestStage2Parachute = currentStage2Parachute;
                        
                        logCurrent("Best", stability, apogee, duration, altitudeScore, durationScore, null);
                        rocket.fireComponentChangeEvent(ComponentChangeEvent.TREE_CHANGE);
                    }
                } catch (Exception e) {
                    logCurrent("Failed", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, e.getMessage());
                }
            } // End inner loop (stage2Options)
            // Check for cancellation *after* the inner loop finishes
            if (cancelled) break; // Break outer loop (stage1Options)
        }
    }

    // Method to optimize all parameters, including parachutes if enabled
    private void optimizeAllParameters() {
        // Check which parachutes are enabled
        boolean parachute1Enabled = config.enabledParams.getOrDefault("Stage 1 Parachute", true) && stage1Parachute != null;
        boolean parachute2Enabled = config.enabledParams.getOrDefault("Stage 2 Parachute", true) && stage2Parachute != null;
        
        // Get parachute presets if any parachute is enabled
        List<ComponentPreset> parachutePresets = null;
        if (parachute1Enabled || parachute2Enabled) {
            parachutePresets = ParachuteHelper.getAllParachutePresets();
        }
        
        // Whether to actually try different parachute combinations
        boolean optimizeParachutes = (parachutePresets != null && !parachutePresets.isEmpty());
        
        if (optimizeParachutes) {
            if (logListener != null) {
                logListener.log("Including parachute optimization");
            }
            
            // Create lists of parachute options to try
            List<String> stage1Options = createParachuteOptions(parachute1Enabled, parachutePresets, currentStage1Parachute);
            List<String> stage2Options = createParachuteOptions(parachute2Enabled, parachutePresets, currentStage2Parachute);
            
            // Try different parachute combinations
            for (String stage1Option : stage1Options) {
                for (String stage2Option : stage2Options) {
                    if (cancelled) break;
                    
                    if (logListener != null) {
                        logListener.log("Trying parachute combination: Stage 1 = " + 
                            (parachute1Enabled ? stage1Option : "unchanged") + 
                            ", Stage 2 = " + (parachute2Enabled ? stage2Option : "unchanged"));
                    }
                    
                    // Apply parachute presets only if enabled
                    applyParachutePreset(parachute1Enabled, stage1Parachute, stage1Option, originalStage1Preset);
                    applyParachutePreset(parachute2Enabled, stage2Parachute, stage2Option, originalStage2Preset);
                    
                    // Run optimization phases for this parachute combination
                    int currentPhase; // Local variable for phase within this combo
                    for (currentPhase = 0; currentPhase < phases; currentPhase++) {
                        currentMajorStep++; // Increment overall progress
                        if (progressListener != null) {
                            progressListener.updateProgress(0, currentMajorStep, totalMajorSteps, 1); // Use overall progress
                        }
                        if (bestError < errorThreshold || cancelled) break;
                        if (currentPhase > 0) adjustParametersForNextPhase();
                        optimizeRecursive(0);
                        if (cancelled) break;
                    }
                }
                if (cancelled) break; // Break outer loop if cancelled
            }
        } else {
            // Run regular optimization with no parachute options
            if (logListener != null) {
                logListener.log("Running optimization without parachutes");
            }
            
            int currentPhase; // Local variable for phase
            for (currentPhase = 0; currentPhase < phases; currentPhase++) {
                currentMajorStep++; // Increment overall progress
                if (progressListener != null) {
                    progressListener.updateProgress(0, currentMajorStep, totalMajorSteps, 1); // Use overall progress
                }
                if (bestError < errorThreshold || cancelled) break;
                if (currentPhase > 0) adjustParametersForNextPhase();
                optimizeRecursive(0);
                if (cancelled) break;
            }
        }
    }

    // Helper method to create parachute option list
    private List<String> createParachuteOptions(boolean enabled, List<ComponentPreset> presets, String currentSetting) {
        List<String> options = new ArrayList<>();
        if (enabled && presets != null) {
            options.add("None"); // No parachute option
            for (ComponentPreset preset : presets) {
                options.add(ParachuteHelper.getPresetDisplayName(preset));
            }
        } else {
            // Just use the current setting if not enabled or no presets
            options.add(currentSetting);
        }
        return options;
    }

    // Helper method to apply parachute preset
    private void applyParachutePreset(boolean enabled, Parachute parachute, String option, ComponentPreset originalPreset) {
        if (enabled && parachute != null) {
            // Determine if this is stage 1 or stage 2 based on the object instance
            if (parachute == stage1Parachute) {
                currentStage1Parachute = option;
            } else if (parachute == stage2Parachute) {
                currentStage2Parachute = option;
            }
            
            ComponentPreset preset = ParachuteHelper.findPresetByDisplayName(option);
            if (preset != null) {
                ParachuteHelper.applyPreset(parachute, preset);
            } else if ("None".equals(option)) {
                // Restore original preset if "None" selected
                ParachuteHelper.applyPreset(parachute, originalPreset);
            }
        }
    }

    private void adjustParametersForNextPhase() {
        for (FinParameter param : parameters) {
            // Use internal param.name as key
            boolean isEnabled = config.enabledParams.getOrDefault(param.name, true);
            
            if (!isEnabled) {
                // If disabled, keep its value fixed at original, min/max set to original, step 0
                param.currentValue = originalValues.get(param.name);
                param.currentMin = originalValues.get(param.name);
                param.currentMax = originalValues.get(param.name);
                param.step = 0;
                continue;
            }

            Double bestVal = bestValues.get(param.name);
            if (bestVal == null) continue;

            if ("finCount".equals(param.name)) {
                param.step = Math.max(param.step * 0.5, 1.0);
            } else {
                param.step = Math.max(param.step * 0.5, 0.01);
            }
            
            // Enforce absolute minimum first
            param.currentMin = Math.max(param.absoluteMin, bestVal - param.step * 3);
            
            // Then enforce absolute maximum
            if (param.hasMaxLimit) {
                param.currentMax = Math.min(param.absoluteMax, bestVal + param.step * 3);
            } else {
                param.currentMax = bestVal + param.step * 3;
            }

            // Final safeguard against invalid ranges
            param.currentMin = Math.min(param.currentMin, param.currentMax);
        }
    }

    private int calculateTotalEvaluations() {
        int total = 1;
        for (FinParameter param : parameters) {
            // Use internal param.name as key
            boolean isEnabled = config.enabledParams.getOrDefault(param.name, true);
            
            if (!isEnabled) {
                continue; // Skip disabled parameters
            }
            
            // If min equals max, there's only 1 value to evaluate
            if (Math.abs(param.currentMax - param.currentMin) < 1e-6) {
                continue; // Only one evaluation needed when min==max
            }
            
            int steps = (int) Math.max(1, ((param.currentMax - param.currentMin) / param.step) + 1);
            total *= steps;
        }
        return Math.max(1, total); // Ensure at least 1 evaluation
    }

    private double getConvertedValue(String paramName) {
        return parameters.stream().filter(p -> p.name.equals(paramName)).findFirst().map(p -> p.converter.apply(p.currentValue)).orElseThrow();
    }

    private double getRawValue(String paramName) {
        return parameters.stream().filter(p -> p.name.equals(paramName)).findFirst().map(p -> p.currentValue).orElseThrow();
    }

    private void updateBestValues(double apogee, double duration, double altitudeScore, double durationScore) {
        bestValues.clear();
        parameters.forEach(p -> bestValues.put(p.name, p.currentValue));
        bestValues.put("apogee", apogee);
        bestValues.put("duration", duration);
        bestValues.put("altitudeScore", altitudeScore);
        bestValues.put("durationScore", durationScore);
        bestValues.put("totalScore", altitudeScore + durationScore);
        
        // Save the current parachute selections with the best values
        bestStage1Parachute = currentStage1Parachute;
        bestStage2Parachute = currentStage2Parachute;
    }

    private void logCurrent(String status, double stability, double apogee, double duration,
                            double altScore, double durScore, String reason) {
        StringBuilder log = new StringBuilder(status + ":");
        for (FinParameter param : parameters) {
            log.append(String.format(" %s=%.2f", param.name, param.currentValue));
        }

        log.append(String.format(" | Apogee=%.1fm (Δ=%.1f)", apogee, altScore));
        log.append(String.format(" | Duration=%.2fs (Δ=%.2f)", duration, durScore));
        log.append(String.format(" | Total Score=%.2f", altScore + durScore));
        log.append(String.format(" | Stability=%.2f cal", stability));

        if (reason != null) {
            log.append(" | Reason=").append(reason);
        }

        String message = log.toString();
        if (logListener != null) {
            logListener.log(message);
        }
    }

    private void logFinalResults() {
        String results = "\n=== Optimization Complete ===\n";
        results += String.format("Altitude Score: %.1f%n", bestValues.get("altitudeScore"));
        results += String.format("Duration Score: %.2f%n", bestValues.get("durationScore"));
        results += String.format("Total Score: %.2f%n", bestValues.get("totalScore"));
        for (Map.Entry<String, Double> entry : bestValues.entrySet()) {
            if (!entry.getKey().endsWith("Score")) {
                results += String.format("%s: %.2f%n", entry.getKey(), entry.getValue());
            }
        }
        if (logListener != null) {
            logListener.log(results);
        }
    }

    private double calculateStability() {
        try {
            FlightConfiguration config = rocket.getSelectedConfiguration();
            RigidBody launchData = MassCalculator.calculateLaunch(config);
            Coordinate cg = launchData.getCM();
            if (cg.weight <= 1e-9) {
                throw new RuntimeException("Invalid CG");
            }

            AerodynamicCalculator aeroCalc = new BarrowmanCalculator();
            FlightConditions conditions = new FlightConditions(config);
            conditions.setMach(0.3);
            conditions.setAOA(0);
            conditions.setRollRate(0);

            Coordinate cp = aeroCalc.getWorstCP(config, conditions, new WarningSet());
            if (cp.weight <= 1e-9) {
                throw new RuntimeException("Invalid CP");
            }

            double absoluteStability = cp.x - cg.x;
            double caliber = 0;
            for (RocketComponent c : config.getAllComponents()) {
                if (c instanceof SymmetricComponent) {
                    SymmetricComponent sym = (SymmetricComponent) c;
                    caliber = Math.max(caliber, Math.max(sym.getForeRadius(), sym.getAftRadius()) * 2);
                }
            }
            if (caliber <= 0) {
                throw new RuntimeException("Invalid caliber");
            }

            return absoluteStability / caliber;
        } catch (Exception e) {
            throw new RuntimeException("Stability calculation failed: " + e.getMessage(), e);
        }
    }

    private NoseCone findNoseCone(RocketComponent component) {
        if (component instanceof NoseCone) {
            return (NoseCone) component;
        }
        for (RocketComponent child : component.getChildren()) {
            NoseCone found = findNoseCone(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public void saveOptimizedDesign(File file) throws Exception {
        for (FinParameter param : parameters) {
            String name = param.name;
            double rawValue = bestValues.get(name);
            double convertedValue = param.converter.apply(rawValue);
            switch (name) {
                case "thickness":
                    fins.setThickness(convertedValue);
                    break;
                case "rootChord":
                    fins.setLength(convertedValue);
                    break;
                case "height":
                    fins.setHeight(convertedValue);
                    break;
                case "finCount":
                    fins.setFinCount((int) Math.round(rawValue));
                    break;
                case "noseLength":
                    noseCone.setLength(convertedValue);
                    break;
                case "noseWallThickness":
                    noseCone.setThickness(convertedValue);
                    break;
                default:
                    throw new IllegalStateException("Unexpected parameter: " + name);
            }
        }

        rocket.enableEvents();
        rocket.fireComponentChangeEvent(ComponentChangeEvent.TREE_CHANGE | ComponentChangeEvent.AERODYNAMIC_CHANGE);
        rocket.update();

        try (FileOutputStream fos = new FileOutputStream(file)) {
            RocketSaver saver = new OpenRocketSaver();
            StorageOptions options = document.getDefaultStorageOptions();
            saver.save(fos, document, options, new WarningSet(), new ErrorSet());
        }
    }

    public void updateParameterBounds(String paramName, double min, double max) {
        if (min > max && max != Double.MAX_VALUE && min != Double.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException("Minimum value must be less than maximum value");
        }
        String key = getParamKey(paramName);
        for (FinParameter param : parameters) {
            if (param.name.equals(key)) {
                // Determine the effective bounds by clamping user input against absolute limits
                double effectiveMin, effectiveMax;

                // Clamp minimum: Take the less restrictive of 0 or absoluteMin, then the more restrictive of that and user min.
                double paramAbsoluteMin = "finCount".equals(param.name) ? 1.0 : 0.0; // Parameter-specific absolute minimums
                effectiveMin = Math.max(paramAbsoluteMin, param.absoluteMin); // Start with physical/practical min
                effectiveMin = Math.max(effectiveMin, min); // Apply user's min if it's more restrictive

                // Clamp maximum: Take the more restrictive of user max and absoluteMax
                effectiveMax = max == Double.MAX_VALUE ? param.absoluteMax : Math.min(param.absoluteMax, max); // Apply user's max or absolute max

                // Ensure effectiveMin is not greater than effectiveMax after clamping
                if (effectiveMin > effectiveMax) {
                     // This case might happen if user min > absolute max. Reset min to absolute max.
                     effectiveMin = effectiveMax; 
                }

                param.currentMin = effectiveMin;
                param.currentMax = effectiveMax;
                param.hasMaxLimit = (effectiveMax != Double.MAX_VALUE && effectiveMax < Double.POSITIVE_INFINITY); // Check if there's a finite upper bound

                // Adjust step based on the *effective* range
                if (effectiveMin == Double.NEGATIVE_INFINITY && effectiveMax == Double.POSITIVE_INFINITY) {
                    param.step = 1.0; // Both unlimited
                } else if (effectiveMin == Double.NEGATIVE_INFINITY) {
                    param.step = Math.max(Math.abs(effectiveMax) * 0.1, 0.01); // Min unlimited
                } else if (!param.hasMaxLimit || effectiveMax == Double.POSITIVE_INFINITY) {
                    param.step = Math.max(Math.abs(effectiveMin) * 0.1, 0.01); // Max unlimited
                } else if (Math.abs(effectiveMax - effectiveMin) < 1e-6) {
                    param.step = 0; // No range to step over
                } else {
                    param.step = Math.max((effectiveMax - effectiveMin) / 10.0, 0.01); // Finite range
                }
                
                // Special step handling for finCount
                if ("finCount".equals(param.name)) {
                    param.step = Math.max(1.0, param.step); // Fin count step must be at least 1
                    param.currentMin = Math.ceil(param.currentMin); // Ensure integer min
                    param.currentMax = Math.floor(param.currentMax); // Ensure integer max
                    // Recalculate effectiveMin/Max after ceiling/flooring for finCount
                    effectiveMin = param.currentMin; 
                    effectiveMax = param.currentMax;
                    if (effectiveMin > effectiveMax) { // Handle case where floor/ceil makes range invalid
                        effectiveMin = effectiveMax;
                        param.currentMin = effectiveMin;
                    }
                }
                
                log(String.format("Updated bounds for %s: Effective Min=%.2f, Effective Max=%.2f, Step=%.2f", 
                                  paramName, effectiveMin, effectiveMax == Double.MAX_VALUE ? Double.POSITIVE_INFINITY : effectiveMax, param.step));

                break;
            }
        }
    }

    private String getParamKey(String displayName) {
        // Map GUI display names (which might include units) to internal parameter keys
        if (displayName.startsWith("Fin Thickness")) return "thickness";
        if (displayName.startsWith("Root Chord")) return "rootChord";
        if (displayName.startsWith("Fin Height")) return "height";
        if (displayName.startsWith("Number of Fins")) return "finCount";
        if (displayName.startsWith("Nose Cone Length")) return "noseLength";
        if (displayName.startsWith("Nose Cone Wall Thickness")) return "noseWallThickness";
        // Add fallbacks for other potential names or direct keys
        switch (displayName) {
            case "Fin Thickness (cm)": return "thickness";
            case "Root Chord (cm)": return "rootChord";
            case "Fin Height (cm)": return "height";
            // Number of Fins is already handled
            case "Nose Cone Length (cm)": return "noseLength";
            case "Nose Cone Wall Thickness (cm)": return "noseWallThickness";
            default: return displayName.toLowerCase().replace(" ", ""); // Default conversion
        }
    }
    
    /**
     * Returns whether the optimization was cancelled
     * @return true if the optimization was cancelled, false otherwise
     */
    public boolean isCancelled() {
        return cancelled;
    }
    
    /**
     * Cancels the current optimization process
     */
    public void cancel() {
        this.cancelled = true;
        log("Optimization cancelled by user");
        revertToOriginalValues();
    }
    
    /**
     * Reverts all parameters to their original values
     */
    public void revertToOriginalValues() {
        for (FinParameter param : parameters) {
            param.currentValue = originalValues.get(param.name);
            param.currentMin = originalValues.get(param.name);
            param.currentMax = originalValues.get(param.name);
            param.step = 0;
        }
        
        // Revert parachutes to original settings
        if (stage1Parachute != null) {
            currentStage1Parachute = bestStage1Parachute;
            ParachuteHelper.applyPreset(stage1Parachute, originalStage1Preset);
        }
        
        if (stage2Parachute != null) {
            currentStage2Parachute = bestStage2Parachute;
            ParachuteHelper.applyPreset(stage2Parachute, originalStage2Preset);
        }
        
        // Clear best values since we're reverting
        bestValues.clear();
        bestError = Double.MAX_VALUE;
    }
    
    private void log(String message) {
        if (logListener != null) {
            logListener.log(message);
        }
    }

    // Add parachute getter and setter methods
    public void setStage1Parachute(String name) {
        this.currentStage1Parachute = name;
        if (hasBestValues()) {
            this.bestStage1Parachute = name;
        }
    }

    public void setStage2Parachute(String name) {
        this.currentStage2Parachute = name;
        if (hasBestValues()) {
            this.bestStage2Parachute = name;
        }
    }

    public String getBestStage1Parachute() {
        return bestStage1Parachute;
    }

    public String getBestStage2Parachute() {
        return bestStage2Parachute;
    }

    public Map<String, Double> getInitialValues() {
        Map<String, Double> values = new HashMap<>();
        values.put("thickness", originalValues.get("thickness"));
        values.put("rootChord", originalValues.get("rootChord"));
        values.put("height", originalValues.get("height"));
        values.put("finCount", originalValues.get("finCount"));
        values.put("noseLength", originalValues.get("noseLength"));
        values.put("noseWallThickness", originalValues.get("noseWallThickness"));
        // Return 0.0/1.0 for parachute presence, but don't store in 'bestValues' format
        // values.put("stage1Parachute", currentStage1Parachute.equals("None") ? 0.0 : 1.0); // No longer needed here
        // values.put("stage2Parachute", currentStage2Parachute.equals("None") ? 0.0 : 1.0); // No longer needed here
        
        // --- REMOVED --- Do NOT run simulation here for initial values ---
        /*
        try {
            SimulationStepper.SimulationResult result = new SimulationStepper(rocket, baseOptions).runSimulation();
            values.put("apogee", result.altitude);
            values.put("duration", result.duration);
            values.put("altitudeScore", calculateAltitudeScore(result.altitude, config.altitudeRange[0], config.altitudeRange[1]));
            values.put("durationScore", calculateDurationScore(result.duration, config.durationRange[0], config.durationRange[1]));
            values.put("totalScore", values.get("altitudeScore") + values.get("durationScore"));
        } catch (Exception e) {
            // If simulation fails, set default values
            values.put("apogee", 0.0);
            values.put("duration", 0.0);
            values.put("altitudeScore", 0.0);
            values.put("durationScore", 0.0);
            values.put("totalScore", 0.0);
        }
        */
        // --- END REMOVED ---
        
        return values;
    }

    // Find the first parachute in the rocket
    private Parachute findFirstParachute(RocketComponent component) {
        return findParachuteRecursive(component, null);
    }
    
    // Find the second parachute in the rocket
    private Parachute findSecondParachute(RocketComponent component) {
        Parachute first = findFirstParachute(component);
        return first != null ? findParachuteRecursive(component, first) : null;
    }
    
    // Helper method to find parachutes recursively
    private Parachute findParachuteRecursive(RocketComponent component, Parachute skipParachute) {
        if (component instanceof Parachute) {
            if (skipParachute == null || component != skipParachute) {
                return (Parachute) component;
            }
        }
        
        for (RocketComponent child : component.getChildren()) {
            Parachute found = findParachuteRecursive(child, skipParachute);
            if (found != null) {
                return found;
            }
        }
        
        return null;
    }

    /**
     * Check if the rocket has a stage 1 parachute
     * @return true if a stage 1 parachute was found
     */
    public boolean hasStage1Parachute() {
        return stage1Parachute != null;
    }
    
    /**
     * Check if the rocket has a stage 2 parachute
     * @return true if a stage 2 parachute was found
     */
    public boolean hasStage2Parachute() {
        return stage2Parachute != null;
    }

    /**
     * Retrieves the FinParameter object based on its display name.
     * 
     * @param displayName The display name used in the GUI (e.g., "Fin Thickness (cm)").
     * @return The corresponding FinParameter, or null if not found.
     */
    public FinParameter getFinParameter(String displayName) {
        String key = getParamKey(displayName);
        return parameters.stream()
                .filter(p -> p.name.equals(key))
                .findFirst()
                .orElse(null);
    }
}
