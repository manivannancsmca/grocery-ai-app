package com.grocery.ai.knowledge.index;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.knowledge.KnowledgeProperties;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel;
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns text into vectors with the configured embedding model (Spring AI {@link EmbeddingModel}). The same model must
 * embed the chunks (ingestion) and the questions (search), each with its own prefix (nomic-embed-text is trained with
 * "search_document: " and "search_query: ").
 *
 * <p>Only built when the knowledge base is enabled. Providers:
 * <ul>
 *   <li>{@code ollama}: local development, the same Ollama as the chat model, with its own timeouts;</li>
 *   <li>{@code bedrock}: AWS, Amazon Titan Text Embeddings V2 through Bedrock with the task role's credentials (the
 *       production chat model, Claude, has no embeddings).</li>
 * </ul>
 * Every vector is checked against the configured dimensions, so a different model never writes vectors the index
 * cannot hold.
 */
@Component
public class KnowledgeEmbeddings {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddings.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final KnowledgeProperties.Embedding settings;
    private final EmbeddingModel model;

    public KnowledgeEmbeddings(KnowledgeProperties properties, AiProperties ai,
                               ObjectProvider<RestClient.Builder> restClientBuilder,
                               ObjectProvider<ObservationRegistry> observationRegistry, JsonMapper jsonMapper) {
        this.settings = properties.embedding();
        if (!properties.enabled()) {
            this.model = null;
            return;
        }
        ObservationRegistry observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        this.model = switch (settings.provider().toLowerCase(Locale.ROOT)) {
            case "ollama" -> ollama(settings, ai, restClientBuilder, observations);
            case "bedrock" -> bedrock(settings, observations, jsonMapper);
            default -> throw new IllegalStateException("Unknown embedding provider '" + settings.provider()
                    + "' (grocery.ai.knowledge.embedding.provider): use ollama or bedrock");
        };
    }

    private static EmbeddingModel bedrock(KnowledgeProperties.Embedding settings, ObservationRegistry observations,
                                          JsonMapper jsonMapper) {
        Region region = settings.region().isBlank() ? new DefaultAwsRegionProviderChain().getRegion()
                : Region.of(settings.region());
        log.info("Knowledge base embeddings: BEDROCK ({}, model {}, {} dimensions)", region, settings.model(),
                settings.dimensions());
        TitanEmbeddingBedrockApi api = new TitanEmbeddingBedrockApi(settings.model(),
                DefaultCredentialsProvider.builder().build(), region, jsonMapper, settings.timeout());
        return new BedrockTitanEmbeddingModel(api, observations);
    }

    private static EmbeddingModel ollama(KnowledgeProperties.Embedding settings, AiProperties ai,
                                         ObjectProvider<RestClient.Builder> restClientBuilder,
                                         ObservationRegistry observationRegistry) {
        HttpClient jdkClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(settings.timeout());
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ai.ollama().baseUrl())
                .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder).clone()
                        .requestFactory(requestFactory))
                .build();
        log.info("Knowledge base embeddings: OLLAMA ({}, model {}, {} dimensions)", ai.ollama().baseUrl(),
                settings.model(), settings.dimensions());
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(OllamaEmbeddingOptions.builder().model(settings.model()).build())
                .observationRegistry(observationRegistry)
                .build();
    }

    /** The embedding model's name, stored with every indexed document. */
    public String modelName() {
        return settings.model();
    }

    /** The vector of a customer's question. */
    public float[] embedQuestion(String question) {
        return checked(model().embed(settings.queryPrefix() + question));
    }

    /**
     * The vectors of a document's chunks, in the same order, embedded {@code batchSize} at a time. {@code before}
     * runs before each batch (ingestion uses it to give way to chats).
     */
    public List<float[]> embedChunks(String title, List<String> chunks, Runnable before) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        int batchSize = Math.max(1, settings.batchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            before.run();
            List<String> batch = chunks.subList(start, Math.min(chunks.size(), start + batchSize)).stream()
                    // The title gives a short chunk its context ("Refund policy" + "within 7 days…").
                    .map(chunk -> settings.documentPrefix() + title + "\n\n" + chunk)
                    .toList();
            List<float[]> embedded = model().embed(batch);
            if (embedded.size() != batch.size()) {
                // e.g. Bedrock skips an input it returned no vector for: never index chunks against the wrong vectors
                throw new IllegalStateException("The embedding model returned " + embedded.size() + " vectors for "
                        + batch.size() + " chunks.");
            }
            embedded.forEach(vector -> vectors.add(checked(vector)));
        }
        return vectors;
    }

    private EmbeddingModel model() {
        if (model == null) {
            throw new IllegalStateException("The knowledge base is disabled");
        }
        return model;
    }

    private float[] checked(float[] vector) {
        if (vector.length != settings.dimensions()) {
            throw new IllegalStateException("The embedding model " + settings.model() + " returns vectors of "
                    + vector.length + " numbers, but the index expects " + settings.dimensions()
                    + " (grocery.ai.knowledge.embedding.dimensions). A different model needs a new index version.");
        }
        return vector;
    }
}
