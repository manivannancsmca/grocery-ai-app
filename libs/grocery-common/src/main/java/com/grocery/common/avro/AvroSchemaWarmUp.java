package com.grocery.common.avro;

import com.grocery.common.kafka.TransientFailures;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.io.IOException;
import java.util.List;

/**
 * When the application is ready, resolves the Schema Registry id of every event schema on the classpath
 * ({@code classpath*:avro/**}{@code /*.avsc}) in the background, so the first publish of each event needs no registry
 * round trip inside its business transaction. It warms the exact cache entry the serializer uses
 * ({@code getIdWithResponse(subject, schema, normalize=false)}).
 *
 * <p>Startup never depends on the registry: failures are only logged. A schema that is not registered (404) is
 * reported and skipped; the first outage (unreachable, timeout, 5xx/408/429) stops the warm-up, and ids are then
 * resolved lazily on first use, which retries every time until the registry is back.
 */
public class AvroSchemaWarmUp implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(AvroSchemaWarmUp.class);

    private final SchemaRegistryClient client;
    private final AvroEventSchemas schemas;
    private final boolean autoRegister;

    public AvroSchemaWarmUp(SchemaRegistryClient client, AvroEventSchemas schemas, boolean autoRegister) {
        this.client = client;
        this.schemas = schemas;
        this.autoRegister = autoRegister;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Thread.ofVirtual().name("grocery-avro-warm-up").start(this::warmUp);
    }

    /** Resolves (or, with auto-register, registers) every schema; never throws. */
    public Result warmUp() {
        List<AvroEventSchemas.EventSchema> all;
        try {
            all = schemas.all();
        } catch (RuntimeException e) {
            log.warn("Avro warm-up skipped: cannot list the event schemas: {}", e.toString());
            return new Result(0, 0, 0, true);
        }
        int resolved = 0;
        int missing = 0;
        for (AvroEventSchemas.EventSchema schema : all) {
            AvroSchema avroSchema = new AvroSchema(schema.schema());
            try {
                int id = autoRegister
                        ? client.registerWithResponse(schema.subject(), avroSchema, false, false).getId()
                        : client.getIdWithResponse(schema.subject(), avroSchema, false).getId();
                resolved++;
                log.debug("Avro schema {} -> subject {} id {}", schema.location(), schema.subject(), id);
            } catch (RestClientException e) {
                if (TransientFailures.isTransientRegistryStatus(e.getStatus())) {
                    return aborted(all.size(), resolved, missing, e);
                }
                missing++;
                log.warn("Avro schema {} is not registered under subject {} (HTTP {}, error {}): publishing {} {} v{}"
                                + " fails until the delivery pipeline registers it", schema.location(), schema.subject(),
                        e.getStatus(), e.getErrorCode(), schema.topic(), schema.eventType(), schema.eventVersion());
            } catch (IOException | RuntimeException e) {
                return aborted(all.size(), resolved, missing, e);
            }
        }
        log.info("Avro warm-up: {} of {} event schemas resolved in the Schema Registry{}", resolved, all.size(),
                missing > 0 ? " (" + missing + " not registered)" : "");
        return new Result(all.size(), resolved, missing, false);
    }

    private static Result aborted(int schemas, int resolved, int missing, Exception e) {
        log.warn("Avro warm-up stopped after {} of {} schemas: Schema Registry unavailable ({}). Schema ids are"
                + " resolved on first use instead.", resolved, schemas, e.toString());
        return new Result(schemas, resolved, missing, true);
    }

    /**
     * @param schemas  event schemas found on the classpath
     * @param resolved schemas whose registry id is now cached
     * @param missing  schemas the registry does not know (404/422)
     * @param aborted  the registry was unavailable, the rest is resolved lazily
     */
    public record Result(int schemas, int resolved, int missing, boolean aborted) {
    }
}
