package com.grocery.events.schemas;

import com.grocery.events.schemas.CompatibilityGate.Baseline;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backward-compatibility gate: every current schema must read data written with EVERY released version
 * ({@code BACKWARD_TRANSITIVE}, Confluent's checker = the registry's rules). The released versions live in
 * {@code src/test/resources/compatibility-baseline/<topic>/<baseName>/v<k>.avsc}.
 */
class BaselineCompatibilityTest {

    static List<EventSchemaFile> schemas() {
        return EventSchemaFile.all();
    }

    @Test
    void theGateUsesTheRegistrysLevel() {
        assertThat(CompatibilityGate.LEVEL).isEqualTo(CompatibilityLevel.BACKWARD_TRANSITIVE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void currentSchemaIsBackwardTransitiveCompatibleWithEveryReleasedVersion(EventSchemaFile file) {
        List<Baseline> released = CompatibilityGate.baselines(file);
        assertThat(released)
                .as("%s has no released baseline: a new event adds compatibility-baseline/%s/v1.avsc (a copy of "
                        + "the schema) in the same change", file, file.id())
                .isNotEmpty();
        List<String> errors = CompatibilityGate.check(file.parse(), CompatibilityGate.schemas(released));
        assertThat(errors)
                .as("%s is not %s-compatible with the released versions %s; make the change compatible or "
                                + "publish it as a new event version (%s.v<n>.avsc)", file, CompatibilityGate.LEVEL,
                        released.stream().map(b -> "v" + b.version()).toList(), file.eventType())
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void baselineVersionsAreContiguousAndKeepTheRecordName(EventSchemaFile file) {
        List<Baseline> released = CompatibilityGate.baselines(file);
        assertThat(released.stream().map(Baseline::version).toList())
                .as("baseline versions of %s must be v1..vN without gaps", file)
                .isEqualTo(IntStream.rangeClosed(1, released.size()).boxed().toList());
        for (Baseline baseline : released) {
            assertThat(baseline.schema().rawSchema().getFullName()).as("%s v%d", file, baseline.version())
                    .isEqualTo(file.fullName());
        }
    }

    /**
     * Avro's compatibility rules ignore logical-type parameters, but a different decimal scale changes the meaning
     * of the same bytes (4999 = 49.99 at scale 2, 4.999 at scale 3) and a smaller precision rejects values that
     * were valid before. So a decimal keeps its scale and never loses precision.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void decimalsKeepTheirScaleAndNeverLosePrecision(EventSchemaFile file) {
        Map<String, LogicalTypes.Decimal> current = decimals(file.parse().rawSchema());
        List<String> bad = new ArrayList<>();
        for (Baseline baseline : CompatibilityGate.baselines(file)) {
            decimals(baseline.schema().rawSchema()).forEach((path, old) -> {
                LogicalTypes.Decimal now = current.get(path);
                if (now != null && (now.getScale() != old.getScale() || now.getPrecision() < old.getPrecision())) {
                    bad.add(path + ": v" + baseline.version() + " decimal(" + old.getPrecision() + ","
                            + old.getScale() + ") -> decimal(" + now.getPrecision() + "," + now.getScale() + ")");
                }
            });
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void everyBaselineFolderBelongsToAShippedSchema() throws IOException {
        Set<String> shipped = EventSchemaFile.all().stream().map(EventSchemaFile::id).collect(Collectors.toSet());
        Path root = CompatibilityGate.baselineRoot();
        List<String> folders;
        try (Stream<Path> paths = Files.walk(root, 2)) {
            folders = paths.filter(Files::isDirectory)
                    .map(root::relativize)
                    .filter(p -> p.getNameCount() == 2)
                    .map(p -> p.getName(0) + "/" + p.getName(1))
                    .toList();
        }
        assertThat(folders).isNotEmpty();
        assertThat(shipped).containsAll(folders);
    }

    /** Decimal logical types by field path, e.g. {@code items[].unitPrice}. */
    static Map<String, LogicalTypes.Decimal> decimals(Schema schema) {
        Map<String, LogicalTypes.Decimal> result = new LinkedHashMap<>();
        collectDecimals(schema, "", result, new IdentityHashMap<>());
        return result;
    }

    private static void collectDecimals(Schema schema, String path, Map<String, LogicalTypes.Decimal> out,
                                        IdentityHashMap<Schema, Boolean> onPath) {
        switch (schema.getType()) {
            case RECORD -> {
                if (onPath.put(schema, Boolean.TRUE) != null) {
                    return;
                }
                for (Schema.Field field : schema.getFields()) {
                    collectDecimals(field.schema(), path.isEmpty() ? field.name() : path + "." + field.name(), out,
                            onPath);
                }
                onPath.remove(schema);
            }
            case UNION -> schema.getTypes().forEach(branch -> collectDecimals(branch, path, out, onPath));
            case ARRAY -> collectDecimals(schema.getElementType(), path + "[]", out, onPath);
            case MAP -> collectDecimals(schema.getValueType(), path + "{}", out, onPath);
            default -> {
                if (schema.getLogicalType() instanceof LogicalTypes.Decimal decimal) {
                    out.put(path, decimal);
                }
            }
        }
    }
}
