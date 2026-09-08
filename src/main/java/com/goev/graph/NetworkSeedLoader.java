package com.goev.graph;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads Table 1's bus/line data from the CSV seed file on startup, skipping
 * the load if buses are already present so re-running the app doesn't
 * duplicate rows. Kept out of Java source so bus/line data can be adjusted
 * without a rebuild.
 */
@Component
public class NetworkSeedLoader implements CommandLineRunner {

    private static final String LINES_RESOURCE = "classpath:seed/table1_lines.csv";
    private static final String BUS_COORDINATES_RESOURCE = "classpath:seed/table1_bus_coordinates.csv";

    private final BusRepository busRepository;
    private final LineRepository lineRepository;

    public NetworkSeedLoader(BusRepository busRepository, LineRepository lineRepository) {
        this.busRepository = busRepository;
        this.lineRepository = lineRepository;
    }

    @Override
    public void run(String... args) throws IOException {
        if (busRepository.count() > 0) {
            return;
        }
        loadSeed();
    }

    void loadSeed() throws IOException {
        Map<Integer, double[]> coordinatesByBus = loadBusCoordinates();

        Resource resource = new PathMatchingResourcePatternResolver().getResource(LINES_RESOURCE);
        Set<Integer> seenBuses = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",");
                int lineId = Integer.parseInt(cols[0].trim());
                int seBus = Integer.parseInt(cols[1].trim());
                int reBus = Integer.parseInt(cols[2].trim());
                double resistanceOhm = Double.parseDouble(cols[3].trim());
                double reactanceOhm = Double.parseDouble(cols[4].trim());
                double maxLoadingMva = Double.parseDouble(cols[5].trim());
                double peakLoadMva = Double.parseDouble(cols[6].trim());
                double estLengthKm = Double.parseDouble(cols[7].trim());

                if (seenBuses.add(seBus)) {
                    busRepository.save(toBus(seBus, coordinatesByBus));
                }
                if (seenBuses.add(reBus)) {
                    busRepository.save(toBus(reBus, coordinatesByBus));
                }

                lineRepository.save(new Line(lineId, seBus, reBus, resistanceOhm, reactanceOhm,
                        maxLoadingMva, peakLoadMva, estLengthKm));
            }
        }
    }

    private Bus toBus(int busId, Map<Integer, double[]> coordinatesByBus) {
        double[] coords = coordinatesByBus.get(busId);
        if (coords == null) {
            return new Bus(busId, null);
        }
        return new Bus(busId, null, coords[0], coords[1]);
    }

    private Map<Integer, double[]> loadBusCoordinates() throws IOException {
        Map<Integer, double[]> coordinates = new HashMap<>();
        Resource resource = new PathMatchingResourcePatternResolver().getResource(BUS_COORDINATES_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                return coordinates;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",");
                int busId = Integer.parseInt(cols[0].trim());
                double latitude = Double.parseDouble(cols[1].trim());
                double longitude = Double.parseDouble(cols[2].trim());
                coordinates.put(busId, new double[]{latitude, longitude});
            }
        }
        return coordinates;
    }
}
