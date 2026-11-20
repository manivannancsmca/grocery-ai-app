package com.grocery.ai;

import com.grocery.ai.approval.ApprovalSigningKeyAccess;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.model.ModelErrors;
import com.grocery.ai.tool.ArgSchema;
import com.grocery.ai.tool.InvalidArgumentsException;
import com.grocery.ai.tool.ToolArgs;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests without Spring or a database. */
class UnitTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Nested
    class Arguments {

        private final ArgSchema schema = ArgSchema.builder()
                .string("query", true, 1, 100, "q")
                .integer("quantity", false, 1, 20, "n")
                .decimal("amount", false, "0.01", "100000.00", "a")
                .uuid("productId", false, "p")
                .bool("inStockOnly", "s")
                .date("date", "d")
                .enumeration("status", Set.of("OPEN", "CANCELLED"), "st")
                .orderNumber("orderNumber", "o")
                .build();

        private ToolArgs validate(String json) {
            return schema.validate(JSON.readTree(json));
        }

        @Test
        void validValuesAreTypedAndLenientAboutRepresentation() {
            UUID id = UUID.randomUUID();
            ToolArgs args = validate("{\"query\":\" rice \",\"quantity\":\"2\",\"amount\":150.5,\"productId\":\"" + id
                    + "\",\"inStockOnly\":\"true\",\"date\":\"2026-11-13\",\"status\":\"open\",\"orderNumber\":"
                    + "\"ga-7k3f9q2m\",\"unknown\":1}");
            assertThat(args.string("query")).isEqualTo("rice");
            assertThat(args.integer("quantity")).isEqualTo(2);
            assertThat(args.decimal("amount")).isEqualTo(new BigDecimal("150.50"));
            assertThat(args.uuid("productId")).isEqualTo(id);
            assertThat(args.isTrue("inStockOnly")).isTrue();
            assertThat(args.date("date")).isEqualTo(LocalDate.of(2026, 11, 13));
            assertThat(args.string("status")).isEqualTo("OPEN");
            assertThat(args.string("orderNumber")).isEqualTo("GA-7K3F9Q2M");
            assertThat(args.has("unknown")).isFalse();
        }

        @Test
        void rangesLengthsAndTypesAreEnforced() {
            assertThatThrownBy(() -> validate("{\"orderNumber\":\"GA-1\"}"))
                    .isInstanceOf(InvalidArgumentsException.class).hasMessageContaining("query is required");
            assertThatThrownBy(() -> validate("{\"query\":\"" + "x".repeat(101) + "\",\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("at most 100 characters");
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"quantity\":21,\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("quantity must be between 1 and 20");
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"quantity\":1.5,\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("whole number");
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"amount\":0,\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("amount must be between 0.01 and 100000.00");
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"amount\":1.234,\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("at most 2 decimals");
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"status\":\"PAID\",\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("status must be one of");
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"orderNumber\":\"#1; DROP\"}"))
                    .hasMessageContaining("order number");
            assertThatThrownBy(() -> validate("{\"query\":[\"a\"],\"orderNumber\":\"GA-1\"}"))
                    .hasMessageContaining("single value");
            assertThatThrownBy(() -> schema.validate(JSON.readTree("[1]")))
                    .hasMessageContaining("JSON object");
        }

        @Test
        void aPlaceholderWordInAnOptionalIdMeansNotGivenButJunkIsStillRefused() {
            assertThat(validate("{\"query\":\"a\",\"orderNumber\":\"GA-1\",\"productId\":\"default\"}").has("productId"))
                    .isFalse();
            assertThat(validate("{\"query\":\"a\",\"orderNumber\":\"GA-1\",\"productId\":\" None \"}").has("productId"))
                    .isFalse();
            assertThatThrownBy(() -> validate("{\"query\":\"a\",\"orderNumber\":\"GA-1\",\"productId\":\"1794565752\"}"))
                    .hasMessageContaining("productId must be an id (UUID)");
            ArgSchema requiredId = ArgSchema.builder().uuid("productId", true, "p").build();
            assertThatThrownBy(() -> requiredId.validate(JSON.readTree("{\"productId\":\"default\"}")))
                    .hasMessageContaining("productId must be an id (UUID)");
        }

        private final ArgSchema withLines = ArgSchema.builder()
                .reference("purchaseOrderNumber", "a purchase order number such as PO-7K3M9QX2", "po")
                .list("lines", true, 2, ArgSchema.builder()
                        .uuid("productId", false, "p")
                        .string("name", false, 1, 20, "n")
                        .integer("quantity", true, 1, 100, "q")
                        .check(values -> {
                            if (!values.containsKey("productId") && !values.containsKey("name")) {
                                throw new InvalidArgumentsException("give a name or productId");
                            }
                        })
                        .build(), "l")
                .build();

        @Test
        void listsOfObjectsAreValidatedEntryByEntry() {
            UUID id = UUID.randomUUID();
            ToolArgs args = withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"po-1abc\",\"lines\":["
                    + "{\"productId\":\"" + id + "\",\"quantity\":\"2\"},{\"name\":\" rice \",\"quantity\":1}]}"));
            assertThat(args.string("purchaseOrderNumber")).isEqualTo("PO-1ABC");
            assertThat(args.list("lines")).hasSize(2);
            assertThat(args.list("lines").getFirst().uuid("productId")).isEqualTo(id);
            assertThat(args.list("lines").getFirst().integer("quantity")).isEqualTo(2);
            assertThat(args.list("lines").getLast().string("name")).isEqualTo("rice");
            assertThat(args.list("missing")).isEmpty();

            // lenient: one object for a list of one, a list sent as a JSON string
            assertThat(withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\",\"lines\":{\"name\":"
                    + "\"dal\",\"quantity\":3}}")).list("lines").getFirst().integer("quantity")).isEqualTo(3);
            assertThat(withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\",\"lines\":\"[{\\\"name\\\":"
                    + "\\\"dal\\\",\\\"quantity\\\":4}]\"}")).list("lines").getFirst().integer("quantity")).isEqualTo(4);
        }

        @Test
        void listProblemsNameTheEntry() {
            assertThatThrownBy(() -> withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\","
                    + "\"lines\":[{\"productId\":\"x\",\"quantity\":0},{\"quantity\":1}]}")))
                    .hasMessage("Invalid arguments: lines[0].productId must be an id (UUID); lines[0].quantity must "
                            + "be between 1 and 100; lines[1]: give a name or productId.");
            assertThatThrownBy(() -> withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\","
                    + "\"lines\":[]}"))).hasMessage("Invalid arguments: lines is required.");
            assertThatThrownBy(() -> withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\","
                    + "\"lines\":[{\"name\":\"a\",\"quantity\":1},{\"name\":\"b\",\"quantity\":1},{\"name\":\"c\","
                    + "\"quantity\":1}]}"))).hasMessage("Invalid arguments: lines must have between 1 and 2 entries.");
            assertThatThrownBy(() -> withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\","
                    + "\"lines\":[1]}"))).hasMessage("Invalid arguments: lines[0] must be an object.");
            assertThatThrownBy(() -> withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"PO-1\","
                    + "\"lines\":\"not json\"}"))).hasMessage("Invalid arguments: lines must be a list.");
            assertThatThrownBy(() -> withLines.validate(JSON.readTree("{\"purchaseOrderNumber\":\"#1\","
                    + "\"lines\":[{\"name\":\"a\",\"quantity\":1}]}")))
                    .hasMessage("Invalid arguments: purchaseOrderNumber must be a purchase order number such as "
                            + "PO-7K3M9QX2.");
            var json = withLines.jsonSchema().get("properties").get("lines");
            assertThat(json.get("type").asString()).isEqualTo("array");
            assertThat(json.get("maxItems").asInt()).isEqualTo(2);
            assertThat(json.get("items").get("required").get(0).asString()).isEqualTo("quantity");
        }

        @Test
        void theJsonSchemaDescribesTheParameters() {
            var json = schema.jsonSchema();
            assertThat(json.get("type").asString()).isEqualTo("object");
            assertThat(json.get("required").valueStream().map(n -> n.asString()).toList())
                    .containsExactly("query", "orderNumber");
            assertThat(json.get("properties").get("quantity").get("maximum").asInt()).isEqualTo(20);
            assertThat(json.get("properties").get("query").get("maxLength").asInt()).isEqualTo(100);
            assertThat(json.get("properties").get("status").get("enum").size()).isEqualTo(2);
        }
    }

    @Nested
    class SigningKey {

        @Test
        void aPkcs8PemIsLoadedWithItsThumbprintAsKeyId() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                    + "\n-----END PRIVATE KEY-----\n";

            RSAKey key = ApprovalSigningKeyAccess.load(pem);
            assertThat(key.isPrivate()).isTrue();
            assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
            // the same key from an env var with literal \n gives the same kid
            assertThat(ApprovalSigningKeyAccess.load(pem.replace("\n", "\\n")).getKeyID()).isEqualTo(key.getKeyID());
        }

        @Test
        void anInvalidKeyRefusesToStart() {
            assertThatThrownBy(() -> ApprovalSigningKeyAccess.create("-----BEGIN PRIVATE KEY-----\nnot-a-key\n"
                    + "-----END PRIVATE KEY-----")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AI_APPROVAL_SIGNING_KEY");
        }
    }

    @Nested
    class Errors {

        @Test
        void modelFailuresAreClassified() {
            assertThat(ModelErrors.classify(new ResourceAccessException("x", new ConnectException("refused"))))
                    .isEqualTo("AI_UNAVAILABLE");
            assertThat(ModelErrors.classify(new RuntimeException(new TimeoutException("slow")))).isEqualTo("AI_TIMEOUT");
            assertThat(ModelErrors.classify(new ResourceAccessException("x", new HttpTimeoutException("slow"))))
                    .isEqualTo("AI_TIMEOUT");
            assertThat(ModelErrors.classify(WebClientResponseException.create(404, "Not Found", null, null, null)))
                    .isEqualTo("AI_UNAVAILABLE");
            assertThat(ModelErrors.classify(WebClientResponseException.create(400, "Bad Request", null, null, null)))
                    .isEqualTo("AI_ERROR");
            assertThat(ModelErrors.classify(new IllegalStateException("bug"))).isEqualTo("AI_ERROR");
        }
    }

    @Nested
    class ScriptedProvider {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class))
                .withUserConfiguration(com.grocery.ai.config.ModelProviderConfig.class)
                .withBean(AiProperties.class, () -> new org.springframework.boot.context.properties.bind.Binder(
                        java.util.List.of()).bindOrCreate("grocery.ai", AiProperties.class))
                .withPropertyValues("grocery.ai.provider=scripted");

        @Test
        void theScriptedModelIsRefusedOutsideLocalAndDocker() {
            runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("only allowed with the "
                        + "'docker' or 'local' profile");
            });
            runner.withPropertyValues("spring.profiles.active=docker").run(context ->
                    assertThat(context).hasNotFailed().hasSingleBean(com.grocery.ai.model.ScriptedChatModel.class));
        }
    }
}
