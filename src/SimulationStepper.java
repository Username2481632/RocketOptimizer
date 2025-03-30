import net.sf.openrocket.document.Simulation;
import net.sf.openrocket.rocketcomponent.Rocket;
import net.sf.openrocket.simulation.SimulationOptions;
import net.sf.openrocket.simulation.exception.SimulationException;

import java.util.concurrent.*;

public class SimulationStepper {
    public static class SimulationResult {
        public final double altitude;
        public final double duration;

        public SimulationResult(double altitude, double duration) {
            this.altitude = altitude;
            this.duration = duration;
        }
    }

    private final Rocket rocket;
    private final SimulationOptions options;
    private static final long SIMULATION_TIMEOUT_MS = 2000;

    public SimulationStepper(Rocket rocket, SimulationOptions options) {
        this.rocket = rocket;
        this.options = options;
    }

    public SimulationResult runSimulation() throws SimulationException {
        final int MAX_RETRIES = 2;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Simulation simulation = new Simulation(rocket);
                simulation.getOptions().copyConditionsFrom(options);

                Future<?> future = executor.submit(() -> {
                    try {
                        simulation.simulate();
                    } catch (SimulationException e) {
                        throw new RuntimeException(e);
                    }
                });

                try {
                    future.get(SIMULATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new SimulationException("Simulation timed out");
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof SimulationException) {
                        throw (SimulationException) e.getCause();
                    } else {
                        throw new SimulationException("Simulation failed", e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SimulationException("Simulation interrupted");
                }

                return new SimulationResult(
                        simulation.getSimulatedData().getMaxAltitude(),
                        simulation.getSimulatedData().getFlightTime()
                );
            } catch (SimulationException e) {
                if (attempt == MAX_RETRIES - 1) throw e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            } finally {
                executor.shutdownNow();
            }
        }
        throw new SimulationException("Max retries exceeded");
    }
}