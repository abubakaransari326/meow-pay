package com.meowpay;

import java.util.function.Supplier;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prefer Testcontainers. If the Java Docker client cannot start a container,
 * fall back to the Docker CLI. Same assertions either way.
 */
final class TestPostgres implements AutoCloseable {

    private final Supplier<String> jdbcUrl;
    private final Supplier<String> username;
    private final Supplier<String> password;
    private final AutoCloseable shutdown;

    private TestPostgres(
            Supplier<String> jdbcUrl,
            Supplier<String> username,
            Supplier<String> password,
            AutoCloseable shutdown
    ) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.shutdown = shutdown;
    }

    static TestPostgres start() {
        try {
            @SuppressWarnings("resource")
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("meowpay")
                    .withUsername("meowpay")
                    .withPassword("meowpay");
            container.start();
            return new TestPostgres(
                    container::getJdbcUrl,
                    container::getUsername,
                    container::getPassword,
                    container
            );
        } catch (RuntimeException ex) {
            CliPostgres cli = CliPostgres.start();
            return new TestPostgres(cli::jdbcUrl, () -> "meowpay", () -> "meowpay", cli);
        }
    }

    String jdbcUrl() {
        return jdbcUrl.get();
    }

    String username() {
        return username.get();
    }

    String password() {
        return password.get();
    }

    @Override
    public void close() {
        try {
            shutdown.close();
        } catch (Exception ignored) {
            // already gone
        }
    }
}
