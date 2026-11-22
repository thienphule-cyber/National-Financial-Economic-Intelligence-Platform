package com.fintech.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Entry point of the National Financial & Economic Intelligence Platform.
 * Starts the embedded web server, which exposes the REST API and
 * serves the static frontend.
 */
@SpringBootApplication
public class PlatformApplication {

    private static final Logger log = LoggerFactory.getLogger(PlatformApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }

    /**
     * Makes sure the local working folders exist before the platform is used.
     * Folder locations are configured in application.properties.
     */
    @Bean
    CommandLineRunner initWorkingDirectories(
            @Value("${platform.cache.dir}") String cacheDir,
            @Value("${platform.results.dir}") String resultsDir) {

        return args -> {
            List<Path> directories = List.of(
                    Path.of(cacheDir),
                    Path.of(resultsDir, "analysis"),
                    Path.of(resultsDir, "forecasting"),
                    Path.of(resultsDir, "simulation"));

            for (Path directory : directories) {
                try {
                    Files.createDirectories(directory);
                    log.info("Working directory ready: {}", directory.toAbsolutePath().normalize());
                } catch (IOException e) {
                    log.warn("Could not create directory {}: {}", directory, e.getMessage());
                }
            }
        };
    }
}