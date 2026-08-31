package com.goev.graph;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
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

    private static final String SEED_RESOURCE = "classpath:seed/table1_lines.csv";

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
        Resource resource = new PathMatchingResourcePatternResolver().getResource(SEED_RESOURCE);
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
                    busRepository.save(new Bus(seBus, null));
                }
                if (seenBuses.add(reBus)) {
                    busRepository.save(new Bus(reBus, null));
                }

                lineRepository.save(new Line(lineId, seBus, reBus, resistanceOhm, reactanceOhm,
                        maxLoadingMva, peakLoadMva, estLengthKm));
            }
        }
    }
}
