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
    private int currentPhase = 0;
    private int evaluationsInPhase = 0;
    private int totalEvaluationsInPhase = 0;
    private final double errorThreshold = 1.0;
    private Map<String, Double> originalValues = new HashMap<>();
    private volatile boolean cancelled = false;

    public Optimizer() {
        parameters.add(new FinParameter("thickness", 0.1, 0.5, 0.05, v -> v / 100));
        parameters.add(new FinParameter("rootChord", 7.0, 15.0, 1.0, v -> v / 100));
        parameters.add(new FinParameter("height", 3.0, 10.0, 1.0, v -> v / 100));
        parameters.add(new FinParameter("finCount", 3, 8, 1.0, v -> v));
        parameters.add(new FinParameter("noseLength", 5.0, 15.0, 1.0, v -> v / 100));
    }

    public static class OptimizationConfig {
        public String orkPath = "rocket.ork";
        public double targetApogee = 241.0;
        public double[] stabilityRange = {1.0, 2.0};
        public double[] durationRange = {41.0, 44.0};
        public Map<String, Boolean> enabledParams = new HashMap<>();
    }

    private static class FinParameter {
        final String name;
        double absoluteMin;
        double absoluteMax;
        final Function<Double, Double> converter;
        double currentMin;
        double currentMax;
        double step;
        double currentValue;

        FinParameter(String name, double absoluteMin, double absoluteMax, double initialStep, Function<Double, Double> converter) {
            this.name = name;
            this.absoluteMin = absoluteMin;
            this.absoluteMax = absoluteMax;
            this.currentMin = absoluteMin;
            this.currentMax = absoluteMax;
            this.step = initialStep;
            this.converter = converter;
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

        originalValues.put("thickness", fins.getThickness());
        originalValues.put("rootChord", fins.getLength());
        originalValues.put("height", fins.getHeight());
        originalValues.put("finCount", (double) fins.getFinCount());
        originalValues.put("noseLength", noseCone.getLength());
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
        if (!config.enabledParams.getOrDefault(getDisplayName(param.name), true)) {
            optimizeRecursive(paramIndex + 1);
            return;
        }

        // Special case: if min and max are equal, just use that single value and continue
        if (Math.abs(param.currentMax - param.currentMin) < 1e-6) {
            param.currentValue = param.currentMin; // Use the min value (which equals max)
            optimizeRecursive(paramIndex + 1);
            return;
        }

        for (double value = param.currentMin; value <= param.currentMax; value += param.step) {
            if (cancelled) return;
            param.currentValue = value;
            optimizeRecursive(paramIndex + 1);
        }
    }

    private String getDisplayName(String paramName) {
        switch (paramName) {
            case "thickness": return "Fin Thickness";
            case "rootChord": return "Root Chord";
            case "height": return "Fin Height";
            case "finCount": return "Number of Fins";
            case "noseLength": return "Nose Cone Length";
            default: return paramName;
        }
    }

    private void evaluateConfiguration() {
        evaluationsInPhase++;
        if (progressListener != null) {
            progressListener.updateProgress(currentPhase, evaluationsInPhase, totalEvaluationsInPhase, phases);
        }
        if (statusListener != null) {
            StringBuilder status = new StringBuilder();
            for (FinParameter param : parameters) {
                status.append(String.format("%s=%.2f ", param.name, param.currentValue));
            }
            statusListener.updateStatus(status.toString());
        }

        try {
            if (config.enabledParams.getOrDefault("Fin Thickness", true)) {
                fins.setThickness(getConvertedValue("thickness"));
            } else {
                fins.setThickness(originalValues.get("thickness"));
            }

            if (config.enabledParams.getOrDefault("Root Chord", true)) {
                fins.setLength(getConvertedValue("rootChord"));
            } else {
                fins.setLength(originalValues.get("rootChord"));
            }

            if (config.enabledParams.getOrDefault("Fin Height", true)) {
                fins.setHeight(getConvertedValue("height"));
            } else {
                fins.setHeight(originalValues.get("height"));
            }

            if (config.enabledParams.getOrDefault("Number of Fins", true)) {
                fins.setFinCount((int) Math.round(getRawValue("finCount")));
            } else {
                fins.setFinCount(originalValues.get("finCount").intValue());
            }

            if (config.enabledParams.getOrDefault("Nose Cone Length", true)) {
                noseCone.setLength(getConvertedValue("noseLength"));
            } else {
                noseCone.setLength(originalValues.get("noseLength"));
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
                    Math.abs(apogee - config.targetApogee) : 0;
            double durationScore = config.enabledParams.getOrDefault("Duration Score", true) ?
                    calculateDurationScore(duration, config.durationRange[0], config.durationRange[1]) : 0;
            double totalError = altitudeScore + durationScore;

            if (logListener != null) {
                String interimData = String.format("thickness=%.2f|rootChord=%.2f|height=%.2f|finCount=%.0f|noseLength=%.2f|apogee=%.1f|duration=%.2f|altitudeScore=%.1f|durationScore=%.2f|totalScore=%.2f",
                        getRawValue("thickness"), getRawValue("rootChord"),
                        getRawValue("height"), getRawValue("finCount"),
                        getRawValue("noseLength"), apogee, duration,
                        altitudeScore, durationScore, altitudeScore + durationScore);
                logListener.log("INTERIM:" + interimData);
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

    private double calculateDurationScore(double actualDuration, double min, double max) {
        if (actualDuration >= min && actualDuration <= max) return 0;
        double boundary = (actualDuration < min) ? min : max;
        return Math.abs(actualDuration - boundary) * 4;
    }

    public void optimizeFins() {
        // Reset cancel flag
        cancelled = false;
        
        for (FinParameter param : parameters) {
            if (config.enabledParams.getOrDefault(getDisplayName(param.name), true)) {
                if (param.absoluteMin > param.absoluteMax) {
                    throw new IllegalStateException("Invalid bounds for parameter: " + getDisplayName(param.name));
                }
            }
        }

        bestError = Double.MAX_VALUE;
        for (currentPhase = 0; currentPhase < phases; currentPhase++) {
            if (bestError < errorThreshold || cancelled) break;
            if (currentPhase > 0) adjustParametersForNextPhase();
            totalEvaluationsInPhase = calculateTotalEvaluations();
            evaluationsInPhase = 0;
            optimizeRecursive(0);
            if (cancelled) break;
        }
        logFinalResults();
    }

    private void adjustParametersForNextPhase() {
        for (FinParameter param : parameters) {
            Double bestVal = bestValues.get(param.name);
            if (bestVal == null) continue;

            if ("finCount".equals(param.name)) {
                param.step = Math.max(param.step * 0.5, 1.0);
            } else {
                param.step = Math.max(param.step * 0.5, 0.01);
            }
            param.currentMin = Math.max(param.absoluteMin, bestVal - param.step * 3);
            param.currentMax = Math.min(param.absoluteMax, bestVal + param.step * 3);
        }
    }

    private int calculateTotalEvaluations() {
        int total = 1;
        for (FinParameter param : parameters) {
            if (!config.enabledParams.getOrDefault(getDisplayName(param.name), true)) {
                continue;
            }
            // If min equals max, there's only 1 value to evaluate
            if (Math.abs(param.currentMax - param.currentMin) < 1e-6) {
                // Only one evaluation needed when min==max
                continue;
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
        if (min > max) {
            throw new IllegalArgumentException("Minimum value must be less than maximum value");
        }
        String key = getParamKey(paramName);
        for (FinParameter param : parameters) {
            if (param.name.equals(key)) {
                param.absoluteMin = min;
                param.absoluteMax = max;
                param.currentMin = min;
                param.currentMax = max;
                // Handle min==max case by setting a non-zero step
                param.step = Math.abs(max - min) < 1e-6 ? 1.0 : Math.max((max - min) / 10, 0.01);
                break;
            }
        }
    }

    private String getParamKey(String displayName) {
        switch (displayName) {
            case "Fin Thickness": return "thickness";
            case "Root Chord": return "rootChord";
            case "Fin Height": return "height";
            case "Number of Fins": return "finCount";
            case "Nose Cone Length": return "noseLength";
            default: return displayName.toLowerCase().replace(" ", "");
        }
    }
    
    /**
     * Cancels the current optimization process
     */
    public void cancel() {
        this.cancelled = true;
        log("Optimization cancelled by user");
    }
    
    private void log(String message) {
        if (logListener != null) {
            logListener.log(message);
        }
    }
}
