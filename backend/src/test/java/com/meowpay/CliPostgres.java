package com.meowpay;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

/**
 * Starts Postgres via the Docker CLI. Used when Testcontainers' Java client
 * cannot talk to the local Docker engine.
 */
final class CliPostgres implements AutoCloseable {

    private final String containerId;
    private final int port;

    private CliPostgres(String containerId, int port) {
        this.containerId = containerId;
        this.port = port;
    }

    static CliPostgres start() {
        int port = freePort();
        String id = exec(
                "docker", "run", "-d", "--rm",
                "-e", "POSTGRES_DB=meowpay",
                "-e", "POSTGRES_USER=meowpay",
                "-e", "POSTGRES_PASSWORD=meowpay",
                "-p", "127.0.0.1:" + port + ":5432",
                "postgres:16-alpine"
        ).strip();
        CliPostgres postgres = new CliPostgres(id, port);
        postgres.waitUntilReady();
        return postgres;
    }

    String jdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:" + port + "/meowpay";
    }

    @Override
    public void close() {
        try {
            exec("docker", "rm", "-f", containerId);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private void waitUntilReady() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try (var ignored = DriverManager.getConnection(jdbcUrl(), "meowpay", "meowpay")) {
                return;
            } catch (Exception ex) {
                last = ex;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for Postgres", ie);
                }
            }
        }
        throw new IllegalStateException("Postgres did not become ready", last);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not allocate a port", ex);
        }
    }

    private static String exec(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not run: " + String.join(" ", command), ex);
        }
    }
}
