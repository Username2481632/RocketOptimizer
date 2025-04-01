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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.io.IOException;
import net.sf.openrocket.simulation.exception.SimulationException;

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

    // Nelder-Mead parameters
    private static final double NM_ALPHA = 1.0; // Reflection factor
    private static final double NM_GAMMA = 2.0; // Expansion factor
    private static final double NM_RHO = 0.5;   // Contraction factor
    private static final double NM_SIGMA = 0.5; // Shrink factor
    private static final int NM_MAX_ITERATIONS = 100; // Max iterations per run
    private static final double NM_TOLERANCE = 1e-4; // Termination tolerance

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

        // Store original RAW values (cm / count)
        originalValues.put("thickness", fins.getThickness() * 100.0); // m to cm
        originalValues.put("rootChord", fins.getLength() * 100.0); // m to cm
        originalValues.put("height", fins.getHeight() * 100.0); // m to cm
        originalValues.put("finCount", (double) fins.getFinCount()); // already count
        originalValues.put("noseLength", noseCone.getLength() * 100.0); // m to cm
        originalValues.put("noseWallThickness", noseCone.getThickness() * 100.0); // m to cm
        
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

    // Interface for the objective function used by Nelder-Mead
    public interface ObjectiveFunction {
        double evaluate(double[] point);
    }

    // Nested class for Nelder-Mead Optimizer
    private static class NelderMeadOptimizer {
        private final ObjectiveFunction function;
        private final int dimensions;
        private final double tolerance;
        private final int maxIterations;
        private ProgressListener progressListener; // Optional progress listener
        private int baseProgressStep = 0;
        private int totalEstimatedProgressSteps = 1; // Avoid division by zero

        public NelderMeadOptimizer(ObjectiveFunction function, int dimensions, double tolerance, int maxIterations) {
            this.function = function;
            this.dimensions = dimensions;
            this.tolerance = tolerance;
            this.maxIterations = maxIterations;
        }

        public void setProgressListener(ProgressListener listener, int baseStep, int totalSteps) {
             this.progressListener = listener;
             this.baseProgressStep = baseStep;
             this.totalEstimatedProgressSteps = Math.max(1, totalSteps); // Ensure at least 1
        }

        public double[] optimize(double[] initialGuess) {
            // Initialize simplex
            double[][] simplex = new double[dimensions + 1][dimensions];
            double[] fSimplex = new double[dimensions + 1];

            // Initial point
            simplex[0] = Arrays.copyOf(initialGuess, dimensions);
            fSimplex[0] = function.evaluate(simplex[0]);
            int evaluations = 1;

            // Create other points by perturbing the initial guess slightly along each axis
            double perturbation = 0.05; // 5% perturbation
             for (int i = 0; i < dimensions; i++) {
                 simplex[i + 1] = Arrays.copyOf(initialGuess, dimensions);
                 // Perturb only if the initial guess for this dimension is not zero,
                 // otherwise, use a small fixed perturbation.
                 if (Math.abs(simplex[i + 1][i]) > 1e-9) {
                    simplex[i + 1][i] *= (1.0 + perturbation);
                 } else {
                     simplex[i + 1][i] = perturbation;
                 }
                 fSimplex[i + 1] = function.evaluate(simplex[i + 1]);
                 evaluations++;
            }

            for (int iteration = 0; iteration < maxIterations; iteration++) {
                 // --- Report Progress ---
                 if (progressListener != null) {
                    // Calculate current step relative to the overall estimated total
                    int currentOverallStep = baseProgressStep + iteration;
                    // Report progress: Phase 0, current overall step, total estimated steps, 1 phase total
                    progressListener.updateProgress(0, currentOverallStep, totalEstimatedProgressSteps, 1);
                 }

                // Order simplex points by function value (worst to best)
                Integer[] order = new Integer[dimensions + 1];
                for (int i = 0; i <= dimensions; i++) order[i] = i;
                Arrays.sort(order, Comparator.comparingDouble(i -> fSimplex[i]));

                int bestIdx = order[0];
                int secondWorstIdx = order[dimensions - 1];
                int worstIdx = order[dimensions];

                // Check for convergence (simplex size)
                 double maxDiff = 0;
                 for (int i = 1; i <= dimensions; i++) {
                     maxDiff = Math.max(maxDiff, Math.abs(fSimplex[order[i]] - fSimplex[bestIdx]));
                 }
                 if (maxDiff < tolerance) {
                     //System.out.println("Converged after " + iteration + " iterations.");
                     return simplex[bestIdx];
                 }

                // Calculate centroid (excluding the worst point)
                double[] centroid = new double[dimensions];
                for (int i = 0; i < dimensions; i++) { // Loop through dimensions
                    for (int j = 0; j <= dimensions; j++) { // Loop through simplex points
                        if (j != worstIdx) {
                            centroid[i] += simplex[j][i];
                        }
                    }
                    centroid[i] /= dimensions;
                }

                // Reflection
                double[] reflected = new double[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    reflected[i] = centroid[i] + NM_ALPHA * (centroid[i] - simplex[worstIdx][i]);
                }
                double fReflected = function.evaluate(reflected);
                evaluations++;

                if (fReflected >= fSimplex[bestIdx] && fReflected < fSimplex[secondWorstIdx]) {
                    // Accept reflected point
                    System.arraycopy(reflected, 0, simplex[worstIdx], 0, dimensions);
                    fSimplex[worstIdx] = fReflected;
                    continue;
                }

                // Expansion
                if (fReflected < fSimplex[bestIdx]) {
                    double[] expanded = new double[dimensions];
                    for (int i = 0; i < dimensions; i++) {
                        expanded[i] = centroid[i] + NM_GAMMA * (reflected[i] - centroid[i]);
                    }
                    double fExpanded = function.evaluate(expanded);
                    evaluations++;

                    if (fExpanded < fReflected) {
                        // Accept expanded point
                        System.arraycopy(expanded, 0, simplex[worstIdx], 0, dimensions);
                        fSimplex[worstIdx] = fExpanded;
            } else {
                        // Accept reflected point
                        System.arraycopy(reflected, 0, simplex[worstIdx], 0, dimensions);
                        fSimplex[worstIdx] = fReflected;
                    }
                    continue;
                }

                // Contraction
                double[] contracted = new double[dimensions];
                if (fReflected < fSimplex[worstIdx]) {
                    // Outside contraction
                    for (int i = 0; i < dimensions; i++) {
                        contracted[i] = centroid[i] + NM_RHO * (reflected[i] - centroid[i]);
                }
            } else {
                    // Inside contraction
                    for (int i = 0; i < dimensions; i++) {
                        contracted[i] = centroid[i] - NM_RHO * (centroid[i] - simplex[worstIdx][i]);
                    }
                }
                double fContracted = function.evaluate(contracted);
                evaluations++;

                if (fContracted < fSimplex[worstIdx]) {
                    // Accept contracted point
                    System.arraycopy(contracted, 0, simplex[worstIdx], 0, dimensions);
                    fSimplex[worstIdx] = fContracted;
                    continue;
                }

                // Shrink
                for (int i = 1; i <= dimensions; i++) { // Shrink towards the best point
                    int currentIdx = order[i];
                    for (int j = 0; j < dimensions; j++) {
                        simplex[currentIdx][j] = simplex[bestIdx][j] + NM_SIGMA * (simplex[currentIdx][j] - simplex[bestIdx][j]);
                    }
                    fSimplex[currentIdx] = function.evaluate(simplex[currentIdx]);
                    evaluations++;
                }
            }

            // Return the best point found after max iterations
            Integer[] finalOrder = new Integer[dimensions + 1];
            for(int i=0; i<=dimensions; i++) finalOrder[i] = i;
            Arrays.sort(finalOrder, Comparator.comparingDouble(i -> fSimplex[i]));
            //System.out.println("Reached max iterations (" + maxIterations + "). Evaluations: " + evaluations);
            return simplex[finalOrder[0]];
        }
    }

    // Restore the helper method to get display names for logging
    private String getDisplayName(String paramName) {
        switch (paramName) {
            case "thickness": return "Fin Thickness";
            case "rootChord": return "Root Chord";
            case "height": return "Fin Height";
            case "finCount": return "Number of Fins";
            case "noseLength": return "Nose Cone Length";
            case "noseWallThickness": return "Nose Cone Wall Thickness";
            // Add other potential internal names if needed
            default: return paramName; // Fallback to internal name
        }
    }

    // Modified evaluateConfiguration to return error score directly
    // and accept parameters as input array
    private double evaluateConfigurationWithError(double[] currentParamValues, List<FinParameter> enabledParams) {
        if (cancelled) return Double.MAX_VALUE; // Return high error if cancelled

        // Map the input array values to the correct FinParameter objects
        for (int i = 0; i < enabledParams.size(); i++) {
            FinParameter param = enabledParams.get(i);
            double rawValue = currentParamValues[i];

            // --- CLAMPING ---
            // Clamp the value to the parameter's current min/max bounds before applying
            rawValue = Math.max(param.currentMin, Math.min(param.currentMax, rawValue));
        if ("finCount".equals(param.name)) {
                rawValue = Math.round(rawValue); // Ensure fin count is integer after clamping
            }
            // Store the (potentially clamped) raw value for this evaluation
             param.currentValue = rawValue;
             // ----------------

            double convertedValue = param.converter.apply(rawValue);

            // Apply the clamped and converted value
            try {
                 switch (param.name) {
                     case "thickness": fins.setThickness(convertedValue); break;
                     case "rootChord": fins.setLength(convertedValue); break;
                     case "height": fins.setHeight(convertedValue); break;
                     case "finCount": fins.setFinCount((int) rawValue); break; // Use raw clamped value
                     case "noseLength": noseCone.setLength(convertedValue); break;
                     case "noseWallThickness": noseCone.setThickness(convertedValue); break;
                 }
            } catch (Exception e) {
                 // Handle potential exceptions during component updates (e.g., invalid values)
                 log("Error setting parameter " + param.name + " to " + rawValue + ": " + e.getMessage());
                 return Double.MAX_VALUE; // Return high error if setting fails
            }
        }

        // Apply current parachute presets (managed outside this function by the main loop)
        applyCurrentParachuteSettings();

        try {
            rocket.enableEvents();
            FlightConfigurationId configId = document.getSelectedConfiguration().getId();
            // Fire fewer events if possible, maybe just TREE_CHANGE? Test needed.
            rocket.fireComponentChangeEvent(ComponentChangeEvent.AERODYNAMIC_CHANGE | ComponentChangeEvent.MASS_CHANGE | ComponentChangeEvent.MOTOR_CHANGE);

            // --- Stability Check ---
            double stability = calculateStability();
            // Check if stability calculation failed (returned NaN)
            if (Double.isNaN(stability)) {
                 logCurrent("Skipping (NM)", stability, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Invalid CP/Stability");
                 return Double.MAX_VALUE; // Return high error for invalid stability
            }
            // Check if stability is outside the user-defined range
            if (stability < config.stabilityRange[0] || stability > config.stabilityRange[1]) {
                logCurrent("Skipping (NM)", stability, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Stability out of range");
                // Return a penalty proportional to how far out of bounds stability is
                double penalty = Math.max(config.stabilityRange[0] - stability, stability - config.stabilityRange[1]);
                 return 1000.0 + penalty * 100; // Large base penalty + proportional penalty
            }

            // --- Simulation ---
            SimulationStepper.SimulationResult result;
             try {
                result = new SimulationStepper(rocket, baseOptions).runSimulation();
             } catch (SimulationException e) {
                 logCurrent("Failed (NM Sim)", stability, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Sim Error: " + e.getMessage());
                 return Double.MAX_VALUE; // Return high error on simulation failure
             }
             
            double apogee = result.altitude;
            double duration = result.duration;

            // --- Calculate Scores ---
            double altitudeScore = config.enabledParams.getOrDefault("Altitude Score", true) ?
                    calculateAltitudeScore(apogee, config.altitudeRange[0], config.altitudeRange[1]) : 0.0;
            double durationScore = config.enabledParams.getOrDefault("Duration Score", true) ?
                    calculateDurationScore(duration, config.durationRange[0], config.durationRange[1]) : 0.0;
            double totalError = altitudeScore + durationScore;

            // --- Update Best Values if this is better ---
             // Store current parameter raw values for potential update
             Map<String, Double> currentRawValues = new HashMap<>();
             for(FinParameter p : enabledParams) {
                 currentRawValues.put(p.name, p.currentValue); // Use the potentially clamped value
             }
             // Include disabled params with their original values
             for(FinParameter p : parameters) {
                 if (!enabledParams.contains(p)) {
                     currentRawValues.put(p.name, originalValues.get(p.name));
                 }
             }

            if (totalError < bestError) {
                bestError = totalError;
                // Update bestValues map with the current parameter values and results
                bestValues.clear();
                bestValues.putAll(currentRawValues); // Store raw values (cm/count)
                bestValues.put("apogee", apogee);
                bestValues.put("duration", duration);
                bestValues.put("altitudeScore", altitudeScore);
                bestValues.put("durationScore", durationScore);
                bestValues.put("totalScore", totalError);
                // Save the current parachute selections associated with this best result
                bestStage1Parachute = currentStage1Parachute;
                bestStage2Parachute = currentStage2Parachute;

                logCurrent("Best (NM)", stability, apogee, duration, altitudeScore, durationScore, null);
                 // Fire tree change event *only* when a new best is found to potentially update UI previews?
                 // Might be too frequent. Consider updating UI only at the end.
                 // rocket.fireComponentChangeEvent(ComponentChangeEvent.TREE_CHANGE);
            }

            // --- Log Interim Results (Optional - can be verbose) ---
            if (logListener != null) {
                 // Log less frequently or provide summary status?
                 // Example: Log every 10 evaluations
                 // if (evaluationCount % 10 == 0) { ... log interim ... }
                 // For now, logging every evaluation for debugging:
                 logInterim(currentRawValues, apogee, duration, altitudeScore, durationScore, totalError);
            }
            
            // Update status listener (optional)
        if (statusListener != null) {
                 // Provide a status update, maybe less frequently
            StringBuilder status = new StringBuilder();
                 for (FinParameter p : enabledParams) {
                     status.append(String.format("%s=%.2f ", p.name, p.currentValue));
                 }
                 // Add parachute status
            if (config.enabledParams.getOrDefault("Stage 1 Parachute", true) && stage1Parachute != null) {
                      status.append(String.format("S1P=%s ", currentStage1Parachute.substring(0, Math.min(5, currentStage1Parachute.length())))); // Abbreviate
            }
             if (config.enabledParams.getOrDefault("Stage 2 Parachute", true) && stage2Parachute != null) {
                      status.append(String.format("S2P=%s ", currentStage2Parachute.substring(0, Math.min(5, currentStage2Parachute.length())))); // Abbreviate
            }
                  status.append(String.format("Err=%.2f", totalError));
            statusListener.updateStatus(status.toString());
        }

            return totalError; // Return the calculated error for Nelder-Mead

        } catch (Exception e) {
            // Catch any other unexpected errors during evaluation
            logCurrent("Failed (NM Eval)", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Eval Error: " + e.getMessage());
            e.printStackTrace(); // Log stack trace for debugging
            return Double.MAX_VALUE; // Return high error
        }
    }

    // Helper to apply current parachute settings
    private void applyCurrentParachuteSettings() {
            if (stage1Parachute != null) {
                boolean isEnabled = config.enabledParams.getOrDefault("Stage 1 Parachute", true);
             ComponentPreset preset = null;
                if (isEnabled) {
                 preset = ParachuteHelper.findPresetByDisplayName(currentStage1Parachute);
             }
             // If enabled and preset found, apply it. Otherwise, apply original (or leave if !isEnabled).
             if (isEnabled) {
                    if (preset != null) {
                        ParachuteHelper.applyPreset(stage1Parachute, preset);
                    } else if ("None".equals(currentStage1Parachute)) {
                      ParachuteHelper.applyPreset(stage1Parachute, originalStage1Preset); // Restore original if 'None'
                    }
             } else {
                  ParachuteHelper.applyPreset(stage1Parachute, originalStage1Preset); // Ensure original if disabled
                }
            }
            if (stage2Parachute != null) {
                boolean isEnabled = config.enabledParams.getOrDefault("Stage 2 Parachute", true);
             ComponentPreset preset = null;
                if (isEnabled) {
                 preset = ParachuteHelper.findPresetByDisplayName(currentStage2Parachute);
             }
              if (isEnabled) {
                    if (preset != null) {
                        ParachuteHelper.applyPreset(stage2Parachute, preset);
                    } else if ("None".equals(currentStage2Parachute)) {
                      ParachuteHelper.applyPreset(stage2Parachute, originalStage2Preset); // Restore original if 'None'
                  }
              } else {
                   ParachuteHelper.applyPreset(stage2Parachute, originalStage2Preset); // Ensure original if disabled
              }
         }
    }

    // Helper to log interim results
    private void logInterim(Map<String, Double> currentRawValues, double apogee, double duration, double altScore, double durScore, double totalError) {
         StringBuilder interimData = new StringBuilder("INTERIM:");
         for (Map.Entry<String, Double> entry : currentRawValues.entrySet()) {
             interimData.append(String.format("%s=%.2f|", entry.getKey(), entry.getValue()));
         }
         interimData.append(String.format("stage1Parachute=%s|stage2Parachute=%s|",
                        currentStage1Parachute, currentStage2Parachute));
         interimData.append(String.format("apogee=%.1f|duration=%.2f|altitudeScore=%.1f|durationScore=%.2f|totalScore=%.2f",
                 apogee, duration, altScore, durScore, totalError));
         if (logListener != null) {
            logListener.log(interimData.toString());
        }
    }

    private double calculateAltitudeScore(double actualAltitude, double min, double max) {
        // If min is "unlimited" (negative infinity), only check upper bound
        if (min == Double.NEGATIVE_INFINITY) {
            // Check against POSITIVE_INFINITY max as well
            return (max == Double.POSITIVE_INFINITY || actualAltitude <= max) ? 0 : (actualAltitude - max);
        }
        // If max is "unlimited" (POSITIVE_INFINITY), only check lower bound
        else if (max == Double.POSITIVE_INFINITY) {
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
             // Check against POSITIVE_INFINITY max as well
            return (max == Double.POSITIVE_INFINITY || actualDuration <= max) ? 0 : (actualDuration - max) * 4;
        }
        // If max is "unlimited" (POSITIVE_INFINITY), only check lower bound
        else if (max == Double.POSITIVE_INFINITY) {
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

    // Rewritten main optimization method using Nelder-Mead
    public void optimizeFins() {
        cancelled = false;
        bestError = Double.MAX_VALUE; // Reset best error
        bestValues.clear(); // Clear previous best values
        currentMajorStep = 0; // Reset progress step counter

        // 1. Identify enabled numeric parameters for optimization
        List<FinParameter> enabledNumericParams = parameters.stream()
                .filter(p -> config.enabledParams.getOrDefault(p.name, true))
                .collect(Collectors.toList());

        int dimensions = enabledNumericParams.size();

        // 2. Check if any numeric parameters are enabled
        boolean anyNumericParamsEnabled = dimensions > 0;
        // Check if any parachutes are enabled for optimization
        boolean parachute1Enabled = config.enabledParams.getOrDefault("Stage 1 Parachute", true) && stage1Parachute != null;
        boolean parachute2Enabled = config.enabledParams.getOrDefault("Stage 2 Parachute", true) && stage2Parachute != null;
        boolean anyParachutesEnabled = parachute1Enabled || parachute2Enabled;

        if (!anyNumericParamsEnabled && !anyParachutesEnabled) {
            log("No parameters enabled for optimization.");
            if (progressListener != null) progressListener.updateProgress(0, 1, 1, 1); // Show completion
            return;
        }
        
        // 3. Prepare parachute options
        List<ComponentPreset> parachutePresets = null;
        if (parachute1Enabled || parachute2Enabled) {
            parachutePresets = ParachuteHelper.getAllParachutePresets();
        }
        List<String> stage1Options = createParachuteOptions(parachute1Enabled, parachutePresets, originalStage1Preset != null ? ParachuteHelper.getPresetDisplayName(originalStage1Preset) : "None");
        List<String> stage2Options = createParachuteOptions(parachute2Enabled, parachutePresets, originalStage2Preset != null ? ParachuteHelper.getPresetDisplayName(originalStage2Preset) : "None");
        
        // Calculate total major steps (parachute combinations)
        int totalParachuteCombinations = Math.max(1, stage1Options.size() * stage2Options.size());
        
        // Calculate total *estimated* steps for progress reporting
        // Multiply parachute combos by max NM iterations if numeric params are enabled
        int totalEstimatedSteps = totalParachuteCombinations * (anyNumericParamsEnabled ? NM_MAX_ITERATIONS : 1);
        if (totalEstimatedSteps == 0) totalEstimatedSteps = 1; // Ensure at least 1

        // 4. Define the objective function
        ObjectiveFunction objectiveFunction = point -> evaluateConfigurationWithError(point, enabledNumericParams);

        // 5. Iterate through parachute combinations and run Nelder-Mead
        int parachuteComboIndex = 0; // Track which parachute combination we are on
        for (String s1Option : stage1Options) {
            for (String s2Option : stage2Options) {
                if (cancelled) break;
                parachuteComboIndex++;
                currentMajorStep = parachuteComboIndex; // Update major step counter for logging/status

                // Set current parachute combination
                currentStage1Parachute = s1Option;
                currentStage2Parachute = s2Option;
                applyCurrentParachuteSettings(); // Apply them to the rocket model

                 log(String.format("--- Starting Optimization for Parachutes: S1=%s, S2=%s ---",
                                   currentStage1Parachute, currentStage2Parachute));

                if (anyNumericParamsEnabled) {
                    // Prepare initial guess for numeric parameters
                     double[] initialGuess = new double[dimensions];
                     for (int i = 0; i < dimensions; i++) {
                         // Start guess from the middle of the allowed range or original value?
                         // Using original value might be better if available and valid.
                         FinParameter p = enabledNumericParams.get(i);
                         initialGuess[i] = originalValues.getOrDefault(p.name, (p.currentMin + p.currentMax) / 2.0);
                         // Ensure initial guess is within bounds
                         initialGuess[i] = Math.max(p.currentMin, Math.min(p.currentMax, initialGuess[i]));
                         if ("finCount".equals(p.name)) {
                             initialGuess[i] = Math.round(initialGuess[i]);
                         }
                     }

                    // Create and run Nelder-Mead optimizer
                    NelderMeadOptimizer nmOptimizer = new NelderMeadOptimizer(objectiveFunction, dimensions, NM_TOLERANCE, NM_MAX_ITERATIONS);

                    // --- Progress Listener Setup for NM ---
                    if (progressListener != null) {
                        // Calculate the base progress step for this parachute combination
                        int baseProgressStep = (parachuteComboIndex - 1) * NM_MAX_ITERATIONS;
                        // Pass the listener, base step, and total estimated steps to the optimizer
                        nmOptimizer.setProgressListener(progressListener, baseProgressStep, totalEstimatedSteps);
                    }
                    // --------------------------------------

                    double[] bestPoint = nmOptimizer.optimize(initialGuess);

                    // The best result (parameters + score) is updated internally
                    // by evaluateConfigurationWithError whenever a new global minimum is found.
                    // We don't need to explicitly update bestValues here unless NM guarantees
                    // the returned bestPoint corresponds *exactly* to the last recorded bestError.
                    // It's safer to rely on the internal update within evaluateConfigurationWithError.

                } else {
                    // No numeric parameters, just evaluate this parachute combination directly
                    evaluateConfigurationWithError(new double[0], enabledNumericParams); // Pass empty array
                    if (progressListener != null) {
                        // When only evaluating parachutes, report progress based on the combo index
                        // Total steps here is just the number of parachute combinations
                        progressListener.updateProgress(0, parachuteComboIndex, totalParachuteCombinations, 1);
                    }
                }

                 log(String.format("--- Finished Optimization for Parachutes: S1=%s, S2=%s. Current Best Error: %.2f ---",
                                   currentStage1Parachute, currentStage2Parachute, bestError));


            } // End inner loop (stage 2 options)
                    if (cancelled) break;
        } // End outer loop (stage 1 options)


        // 6. Finalization
        if (!cancelled && progressListener != null) {
            // Ensure progress bar reaches 100% using the total estimated steps
            progressListener.updateProgress(0, totalEstimatedSteps, totalEstimatedSteps, 1); 
        }
        logFinalResults();

        // Optionally, apply the absolute best found parameters back to the rocket model
        if (!bestValues.isEmpty()) {
            applyBestValuesToModel();
        } else {
            // If no better solution was found (or optimization failed early),
            // revert to original values? Or leave as is? Reverting seems safer.
            revertToOriginalValues(); // Revert components to initial state
            applyCurrentParachuteSettings(); // Re-apply original parachute settings
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
            // Ensure the current setting is included if it's a valid preset or "None"
            boolean currentFound = false;
            for(String opt : options) {
                if (opt.equals(currentSetting)) {
                    currentFound = true;
                    break;
                }
            }
             // Add the original/current setting if it wasn't already in the list
             // This handles custom parachutes or cases where the original wasn't a standard preset
             if (!currentFound && currentSetting != null && !currentSetting.isEmpty()) {
                 // Check if it's a known preset display name first
                 boolean isKnownPreset = false;
                 if (presets != null) {
                    for(ComponentPreset p : presets) {
                        if(ParachuteHelper.getPresetDisplayName(p).equals(currentSetting)) {
                            isKnownPreset = true;
                            break;
                        }
                    }
                 }
                 // Only add if it's not "None" and not a known preset already added
                 if (!"None".equals(currentSetting) && !isKnownPreset) {
                    options.add(currentSetting);
                 } else if ("None".equals(currentSetting) && !options.contains("None")) {
                     options.add("None"); // Make sure None is present if it was the original
                 }
             }

        } else {
            // Just use the current setting if not enabled or no presets
            options.add(currentSetting);
        }
        // Remove duplicates just in case
        return options.stream().distinct().collect(Collectors.toList());
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
            // Or try to apply the original preset? Applying original seems safer if the name is unrecognized.
            else if (!"None".equals(option) && preset == null) {
                 log(String.format("Warning: Could not find preset for %s. Applying original preset instead.", option)); 
                 ParachuteHelper.applyPreset(parachute, originalPreset);
                 // Update the currentStage parachute name back to the original if we reverted
                 if (parachute == stage1Parachute && originalPreset != null) currentStage1Parachute = ParachuteHelper.getPresetDisplayName(originalPreset);
                 if (parachute == stage2Parachute && originalPreset != null) currentStage2Parachute = ParachuteHelper.getPresetDisplayName(originalPreset);
            }
        } else if (!enabled && parachute != null) {
             // If optimization for this parachute is disabled, ensure it's set to its original state
             ParachuteHelper.applyPreset(parachute, originalPreset);
        }
    }

    private double getConvertedValue(String paramName) {
        // Find the parameter by its internal name
        FinParameter param = parameters.stream()
            .filter(p -> p.name.equals(paramName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown parameter name: " + paramName));

        // Get the *best* raw value found, not the transient currentValue
        double bestRawValue = bestValues.getOrDefault(paramName, originalValues.get(paramName));

        return param.converter.apply(bestRawValue);
    }

    private double getRawValue(String paramName) {
        // Find the parameter by its internal name
         FinParameter param = parameters.stream()
            .filter(p -> p.name.equals(paramName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown parameter name: " + paramName));

        // Get the *best* raw value found
        return bestValues.getOrDefault(paramName, originalValues.get(paramName));
    }

    private void logCurrent(String status, double stability, double apogee, double duration,
                            double altScore, double durScore, String reason) {
        StringBuilder log = new StringBuilder(status + ":");
        // Log current values being *evaluated*
        for (FinParameter param : parameters) {
             // Only log enabled parameters for brevity? Or all? Log all for now.
             log.append(String.format(" %s=%.2f", param.name, param.currentValue)); // Log the value being tested
        }
         // Log current parachutes being tested
         log.append(String.format(" S1P=%s S2P=%s", currentStage1Parachute, currentStage2Parachute));

        // Log results if available
        if (!Double.isNaN(apogee)) log.append(String.format(" | Apogee=%.1fm (Δ=%.1f)", apogee, altScore));
        if (!Double.isNaN(duration)) log.append(String.format(" | Duration=%.2fs (Δ=%.2f)", duration, durScore));
        if (!Double.isNaN(altScore) && !Double.isNaN(durScore)) log.append(String.format(" | Total Score=%.2f", altScore + durScore));
        if (!Double.isNaN(stability)) log.append(String.format(" | Stability=%.2f cal", stability));

        if (reason != null) {
            log.append(" | Reason=").append(reason);
        }

        String message = log.toString();
        if (logListener != null) {
            logListener.log(message);
        }
    }

    private void logFinalResults() {
        if (bestValues.isEmpty()) {
             log("=== Optimization Complete (No improvement found or cancelled early) ===");
             return;
        }

        String results = "=== Optimization Complete ===";
        results += String.format("Best Total Score: %.2f%n", bestValues.get("totalScore"));
        results += String.format("  Altitude Score: %.1f%n", bestValues.get("altitudeScore"));
        results += String.format("  Duration Score: %.2f%n", bestValues.get("durationScore"));
        results += String.format("Simulation Results:%n");
        results += String.format("  Apogee: %.1f m%n", bestValues.get("apogee"));
        results += String.format("  Duration: %.2f s%n", bestValues.get("duration"));
        results += String.format("Parameters:%n");
        // Log numeric parameters
        for (FinParameter param : parameters) {
            if (bestValues.containsKey(param.name)) {
                 // Display raw value (cm/count) and potentially converted value (m) if different
                 double rawVal = bestValues.get(param.name);
                 double convertedVal = param.converter.apply(rawVal);
                 String unit = param.name.equals("finCount") ? "" : " cm"; // Assuming raw values are cm or count
                 results += String.format("  %s: %.2f%s%n", getDisplayName(param.name), rawVal, unit);
            }
        }
         // Log best parachutes found
         results += String.format("  Stage 1 Parachute: %s%n", bestStage1Parachute);
         results += String.format("  Stage 2 Parachute: %s%n", bestStage2Parachute);

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
            // Return NaN if CP calculation failed
            if (cp.weight <= 1e-9) {
                // log("Warning: Invalid CP calculated."); // Optional logging
                return Double.NaN; 
            }

            double absoluteStability = cp.x - cg.x;
            double caliber = 0;
            for (RocketComponent c : config.getAllComponents()) {
                if (c instanceof SymmetricComponent) {
                    SymmetricComponent sym = (SymmetricComponent) c;
                    caliber = Math.max(caliber, Math.max(sym.getForeRadius(), sym.getAftRadius()) * 2);
                }
            }
            // Return NaN if caliber calculation fails
            if (caliber <= 1e-9) { // Use small epsilon instead of 0
                 // log("Warning: Invalid caliber calculated."); // Optional logging
                 return Double.NaN;
            }

            return absoluteStability / caliber;
        } catch (Exception e) {
            // Log the exception and return NaN for any other stability calculation failure
            log("Error during stability calculation: " + e.getMessage());
            // e.printStackTrace(); // Optionally print stack trace for debugging
            return Double.NaN; 
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

    // Modified saveOptimizedDesign to use bestValues map
    public void saveOptimizedDesign(File file) throws Exception {
        if (bestValues.isEmpty()) {
            throw new IllegalStateException("No optimized results available to save.");
        }

        applyBestValuesToModel(); // Apply the best found values to the components

        rocket.enableEvents();
        // Fire necessary events to update calculations
        rocket.fireComponentChangeEvent(ComponentChangeEvent.TREE_CHANGE | ComponentChangeEvent.AERODYNAMIC_CHANGE | ComponentChangeEvent.MASS_CHANGE | ComponentChangeEvent.MOTOR_CHANGE);
        rocket.update(); // Ensure rocket state is fully updated

        try (FileOutputStream fos = new FileOutputStream(file)) {
            RocketSaver saver = new OpenRocketSaver();
            StorageOptions options = document.getDefaultStorageOptions();
            // Clear warnings/errors before saving?
             WarningSet warnings = new WarningSet();
             ErrorSet errors = new ErrorSet();
            saver.save(fos, document, options, warnings, errors);
             if (!errors.isEmpty()) {
                 throw new IOException("Errors encountered during save: " + errors.toString());
             }
             if (!warnings.isEmpty()) {
                 log("Warnings during save: " + warnings.toString());
             }
        }
    }

     // Helper method to apply the best found parameters to the model components
     private void applyBestValuesToModel() {
         if (bestValues.isEmpty()) return;

         // Apply best numeric parameters
         for (FinParameter param : parameters) {
             if (bestValues.containsKey(param.name)) {
                 double rawValue = bestValues.get(param.name);
                 double convertedValue = param.converter.apply(rawValue);
                 try {
                     switch (param.name) {
                         case "thickness": fins.setThickness(convertedValue); break;
                         case "rootChord": fins.setLength(convertedValue); break;
                         case "height": fins.setHeight(convertedValue); break;
                         case "finCount": fins.setFinCount((int) Math.round(rawValue)); break;
                         case "noseLength": noseCone.setLength(convertedValue); break;
                         case "noseWallThickness": noseCone.setThickness(convertedValue); break;
                     }
                 } catch (Exception e) {
                     log("Error applying best value for " + param.name + ": " + e.getMessage());
                 }
             } else {
                 // If a parameter wasn't optimized/in bestValues, ensure it's set to original
                  if (originalValues.containsKey(param.name)) {
                     double rawValue = originalValues.get(param.name);
                     double convertedValue = param.converter.apply(rawValue);
                     try {
                         switch (param.name) {
                             case "thickness": fins.setThickness(convertedValue); break;
                             case "rootChord": fins.setLength(convertedValue); break;
                             case "height": fins.setHeight(convertedValue); break;
                             case "finCount": fins.setFinCount((int) Math.round(rawValue)); break;
                             case "noseLength": noseCone.setLength(convertedValue); break;
                             case "noseWallThickness": noseCone.setThickness(convertedValue); break;
                         }
                      } catch (Exception e) {
                         log("Error applying original value for " + param.name + ": " + e.getMessage());
                      }
                  }
             }
         }

         // Apply best parachute settings
         currentStage1Parachute = bestStage1Parachute; // Update current setting to best
         currentStage2Parachute = bestStage2Parachute;
         applyCurrentParachuteSettings(); // Apply the best presets
     }

    // updateParameterBounds needs to update currentMin/currentMax which are used for clamping
    public void updateParameterBounds(String paramName, double min, double max) {
        // Input validation (allow min == max, but not min > max unless max is infinity)
        if (min > max && max != Double.POSITIVE_INFINITY && min != Double.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException("Minimum value must be less than or equal to maximum value");
        }

        String key = getParamKey(paramName); // Map display name to internal key
        for (FinParameter param : parameters) {
            if (param.name.equals(key)) {
                // Determine effective bounds based on absolute limits and user input
                double effectiveMin, effectiveMax;

                // Start with absolute physical/practical limits
                double paramAbsoluteMin = "finCount".equals(param.name) ? 1.0 : 0.0; // Default practical min
                effectiveMin = Math.max(paramAbsoluteMin, param.absoluteMin);
                effectiveMax = param.absoluteMax;

                // Apply user's min, respecting absolute min
                if (min != Double.NEGATIVE_INFINITY) {
                    effectiveMin = Math.max(effectiveMin, min);
                }

                // Apply user's max, respecting absolute max
                if (max != Double.POSITIVE_INFINITY) {
                    effectiveMax = Math.min(effectiveMax, max);
                }

                // Final check: ensure min is not greater than max after applying all constraints
                if (effectiveMin > effectiveMax) {
                    // This can happen if user's min > absolute max, or user's max < absolute min.
                    // A reasonable approach is to set both to the midpoint or one of the limits.
                    // Setting both to the absolute limit that was violated seems logical.
                    if (min > param.absoluteMax) { // User min violated absolute max
                         effectiveMin = effectiveMax = param.absoluteMax;
                    } else if (max < param.absoluteMin) { // User max violated absolute min
                         effectiveMin = effectiveMax = param.absoluteMin;
                    } else { // Default fallback: clamp min to max
                     effectiveMin = effectiveMax; 
                    }
                    log(String.format("Warning: Bounds conflict for %s. Clamped range to [%.2f, %.2f]",
                                       paramName, effectiveMin, effectiveMax));
                }

                // Update the parameter's current bounds used for clamping in objective function
                param.currentMin = effectiveMin;
                param.currentMax = effectiveMax;
                // hasMaxLimit is less critical now as clamping handles it, but keep updated
                param.hasMaxLimit = (param.currentMax != Double.POSITIVE_INFINITY && param.currentMax != Double.MAX_VALUE);

                // Recalculate step size based on the *effective* range (used for initial simplex generation? Less critical now)
                 if (Math.abs(effectiveMax - effectiveMin) < 1e-9) {
                     param.step = 0;
                 } else if (effectiveMin == Double.NEGATIVE_INFINITY || effectiveMax == Double.POSITIVE_INFINITY || !param.hasMaxLimit) {
                     // Use a default step or percentage of the single bound if one exists
                     param.step = Math.max(Math.abs(effectiveMin != Double.NEGATIVE_INFINITY ? effectiveMin : effectiveMax) * 0.1, 0.1);
                 }
                 else {
                     param.step = Math.max((effectiveMax - effectiveMin) / 10.0, 0.01); // 10 steps across range
                 }


                // Special handling for finCount (ensure integer bounds and step)
                if ("finCount".equals(param.name)) {
                    param.currentMin = Math.ceil(param.currentMin);
                    param.currentMax = Math.floor(param.currentMax);
                     // Ensure range is still valid after floor/ceil
                     if (param.currentMin > param.currentMax) {
                         param.currentMin = param.currentMax; // Set min to max if range inverted
                     }
                     param.step = Math.max(1.0, Math.round(param.step)); // Ensure integer step >= 1
                     // Update effectiveMin/Max after ceiling/flooring
                    effectiveMin = param.currentMin; 
                    effectiveMax = param.currentMax;
                }

                log(String.format("Updated bounds for %s: Effective Range=[%.2f, %.2f], Step=%.2f",
                                  paramName, effectiveMin,
                                  (effectiveMax == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : effectiveMax), // Avoid printing MAX_VALUE
                                  param.step));
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
        // Revert components to their state *before* optimization started
        revertToOriginalValues();
        // Apply original parachute settings as well
        applyCurrentParachuteSettings(); // Since currentStageXParachute was reset in revert
         // Clear any potentially incomplete best values
         bestValues.clear();
         bestError = Double.MAX_VALUE;
    }
    
    /**
     * Reverts all parameters to their original values
     */
    public void revertToOriginalValues() {
        log("Reverting parameters to original values...");
        // Revert numeric parameters in the model
        for (FinParameter param : parameters) {
            if (originalValues.containsKey(param.name)) {
                double rawValue = originalValues.get(param.name);
                 param.currentValue = rawValue; // Reset transient value
                 // Also reset currentMin/Max used for clamping back to original derived bounds?
                 // Or keep the user-set bounds? Keeping user bounds seems less surprising.

                double convertedValue = param.converter.apply(rawValue);
                try {
                    switch (param.name) {
                        case "thickness": fins.setThickness(convertedValue); break;
                        case "rootChord": fins.setLength(convertedValue); break;
                        case "height": fins.setHeight(convertedValue); break;
                        case "finCount": fins.setFinCount((int) Math.round(rawValue)); break;
                        case "noseLength": noseCone.setLength(convertedValue); break;
                        case "noseWallThickness": noseCone.setThickness(convertedValue); break;
                    }
                } catch (Exception e) {
                     log("Error reverting " + param.name + ": " + e.getMessage());
                 }
            }
        }

        // Reset current parachute selections to originals
        currentStage1Parachute = (originalStage1Preset != null) ? ParachuteHelper.getPresetDisplayName(originalStage1Preset) : "None";
        currentStage2Parachute = (originalStage2Preset != null) ? ParachuteHelper.getPresetDisplayName(originalStage2Preset) : "None";
        // Note: applyCurrentParachuteSettings() needs to be called *after* this by the caller (e.g., cancel())

        // Don't clear bestValues here, cancel() handles that.
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

    // Now returns original values in CM / count
    public Map<String, Double> getInitialValues() {
        // Return a copy of the originalValues map which now stores raw values (cm/count)
        return new HashMap<>(originalValues);
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
