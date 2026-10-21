package vn.yourhrm.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One throw-away PostgreSQL 17 per test JVM with the DDL baseline (database/postgresql/ddl/000..007)
 * loaded by psql INSIDE the container (the files contain psql meta-commands), then the dev seed script.
 * The application connects as the real runtime role yhrm_app_core (a test-only password is set here),
 * so RLS is exercised exactly as in production. The developer database (container YHRMDB) is never used.
 */
public final class PostgresTestDatabase {

    public static final String APP_ROLE = "yhrm_app_core";
    public static final String APP_PASSWORD = "test-only-app-core";

    private static final PostgreSQLContainer CONTAINER = start();

    private PostgresTestDatabase() {
    }

    public static PostgreSQLContainer container() {
        return CONTAINER;
    }

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    /** Superuser access for fixtures and assertions (bypasses RLS). Never used by the application. */
    public static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(),
                CONTAINER.getPassword()));
    }

    @SuppressWarnings("resource")
    private static PostgreSQLContainer start() {
        Path ddlDir = Paths.get(System.getProperty("yhrm.ddl.dir", "../../../database/postgresql/ddl"));
        Path seed = Paths.get(System.getProperty("yhrm.seed.file", "src/main/resources/dev/seed-dev-tenant.sql"));
        PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17.11")
                .withDatabaseName("yhrm")
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements",
                        "-c", "wal_level=logical", "-c", "timezone=UTC", "-c", "max_connections=50")
                .withEnv("TZ", "UTC")
                .withCopyFileToContainer(MountableFile.forHostPath(ddlDir), "/yhrm-ddl/")
                .withCopyFileToContainer(MountableFile.forHostPath(seed), "/yhrm-seed/seed-dev-tenant.sql");
        pg.start();
        try {
            List<String> files;
            try (Stream<Path> s = Files.list(ddlDir)) {
                files = s.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d{3}_.*\\.sql")).sorted().toList();
            }
            if (files.size() < 8) {
                throw new IllegalStateException("Expected DDL files 000..007 in " + ddlDir.toAbsolutePath() + ", got " + files);
            }
            for (String f : files) {
                psql(pg, "-f", "/yhrm-ddl/" + f);
            }
            psql(pg, "-c", "ALTER ROLE " + APP_ROLE + " PASSWORD '" + APP_PASSWORD + "'");
            psql(pg, "-f", "/yhrm-seed/seed-dev-tenant.sql");
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not load the DDL baseline", e);
        }
        return pg;
    }

    private static void psql(PostgreSQLContainer pg, String... args) throws IOException, InterruptedException {
        String[] cmd = Stream.concat(Stream.of("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", pg.getUsername(), "-d",
                pg.getDatabaseName()), Stream.of(args)).toArray(String[]::new);
        ExecResult r = pg.execInContainer(cmd);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql " + String.join(" ", args) + " failed (" + r.getExitCode() + "):\n"
                    + r.getStderr() + "\n" + r.getStdout());
        }
    }
}
