package com.grocery.dbtests;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The services with Flyway migrations, discovered by scanning {@code services/*}/src/main/resources/db/migration in the
 * repository (so a new service is covered without touching this module), and the previous release's last migration
 * version of each ({@code baseline.properties}).
 */
final class ServiceMigrations {

    static final String MIGRATIONS = "src/main/resources/db/migration";
    /** Flyway's default versioned-migration naming: {@code V<version>__<description>.sql}, version parts by . or _. */
    private static final Pattern VERSIONED = Pattern.compile("V([0-9]+(?:[._][0-9]+)*)__(.+)\\.sql");

    /** One versioned migration file. */
    record Migration(Version version, String description, Path file) {

        String fileName() {
            return file.getFileName().toString();
        }

        @Override
        public String toString() {
            return fileName();
        }
    }

    /** A Flyway version, compared part by part numerically ({@code 1.10 > 1.9}). */
    record Version(List<BigInteger> parts) implements Comparable<Version> {

        static Version parse(String text) {
            List<BigInteger> parts = new ArrayList<>();
            for (String part : text.trim().split("[._]")) {
                parts.add(new BigInteger(part));
            }
            return new Version(List.copyOf(parts));
        }

        @Override
        public int compareTo(Version other) {
            for (int i = 0; i < Math.max(parts.size(), other.parts.size()); i++) {
                BigInteger a = i < parts.size() ? parts.get(i) : BigInteger.ZERO;
                BigInteger b = i < other.parts.size() ? other.parts.get(i) : BigInteger.ZERO;
                int compared = a.compareTo(b);
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        }

        @Override
        public String toString() {
            return String.join(".", parts.stream().map(BigInteger::toString).toList());
        }
    }

    /**
     * A service with migrations.
     *
     * @param name           the folder under {@code services/}, e.g. {@code order-service}
     * @param migrations     its versioned migrations, oldest first
     * @param consumesEvents whether its main code has a {@code @KafkaListener} (then it needs {@code processed_event})
     */
    record Service(String name, Path migrationDirectory, List<Migration> migrations, boolean consumesEvents) {

        /** The test database: the service name with underscores, e.g. {@code order_service}. */
        String database() {
            return name.replace('-', '_');
        }

        /** Flyway location of the migrations, read straight from the source tree. */
        String location() {
            return "filesystem:" + migrationDirectory.toAbsolutePath();
        }

        List<Migration> upTo(Version version) {
            return migrations.stream().filter(m -> m.version().compareTo(version) <= 0).toList();
        }

        List<Migration> after(Version version) {
            return migrations.stream().filter(m -> m.version().compareTo(version) > 0).toList();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private ServiceMigrations() {
    }

    /** The repository root: {@code -Drepo.root}, else the first directory upwards that has a {@code services} folder. */
    static Path repositoryRoot() {
        String configured = System.getProperty("repo.root");
        if (configured != null && !configured.isBlank()) {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(root.resolve("services"))) {
                return root;
            }
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("services")) && Files.isDirectory(dir.resolve("tests"))) {
                return dir;
            }
        }
        throw new IllegalStateException("Repository root not found (no services/ folder above "
                + Path.of("").toAbsolutePath() + "); pass -Drepo.root=<path>");
    }

    /** Every service with at least one versioned migration, by name. */
    static List<Service> discover() {
        Path services = repositoryRoot().resolve("services");
        List<Service> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(services)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path migrations = dir.resolve(MIGRATIONS);
                if (!Files.isDirectory(migrations)) {
                    continue;
                }
                List<Migration> list = migrations(migrations);
                if (!list.isEmpty()) {
                    found.add(new Service(dir.getFileName().toString(), migrations, list,
                            hasKafkaListener(dir.resolve("src/main/java"))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("No service with migrations under " + services);
        }
        return found;
    }

    static List<Migration> migrations(Path directory) throws IOException {
        List<Migration> migrations = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher name = VERSIONED.matcher(file.getFileName().toString());
                if (name.matches()) {
                    migrations.add(new Migration(Version.parse(name.group(1)), name.group(2), file));
                }
            }
        }
        migrations.sort(Comparator.comparing(Migration::version));
        return List.copyOf(migrations);
    }

    private static boolean hasKafkaListener(Path sources) throws IOException {
        if (!Files.isDirectory(sources)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(file -> file.toString().endsWith(".java")).anyMatch(file -> {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8).contains("@KafkaListener");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** {@code baseline.properties}: service → the last migration version of the previous release. */
    static Map<String, Version> baselines() {
        Properties properties = new Properties();
        try (InputStream in = ServiceMigrations.class.getResourceAsStream("/baseline.properties")) {
            if (in == null) {
                throw new IllegalStateException("baseline.properties not on the test classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Version> baselines = new TreeMap<>();
        properties.forEach((key, value) -> baselines.put(key.toString().trim(), Version.parse(value.toString())));
        return baselines;
    }

    /** The baseline of a discovered service; fails with instructions when the file has none. */
    static Version baseline(Service service) {
        Version version = baselines().get(service.name());
        if (version == null) {
            throw new IllegalStateException(service.name() + " is not in tests/db-tests/src/test/resources/"
                    + "baseline.properties: add '" + service.name() + "=<last migration version of the previous release>'"
                    + " (0 for a service that is new in this release)");
        }
        return version;
    }
}
