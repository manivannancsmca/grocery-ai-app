package com.grocery.ai.knowledge;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.knowledge.index.KnowledgeEmbeddings;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The embedding provider is chosen by configuration; building one never calls the model (or AWS). */
class KnowledgeEmbeddingsTest {

    private static KnowledgeEmbeddings embeddings(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        KnowledgeProperties knowledge = binder.bindOrCreate("grocery.ai.knowledge", KnowledgeProperties.class);
        AiProperties ai = binder.bindOrCreate("grocery.ai", AiProperties.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        return new KnowledgeEmbeddings(knowledge, ai, beans.getBeanProvider(RestClient.Builder.class),
                beans.getBeanProvider(ObservationRegistry.class), JsonMapper.builder().build());
    }

    @Test
    void ollamaIsTheDefault() {
        KnowledgeEmbeddings ollama = embeddings(Map.of("grocery.ai.knowledge.enabled", "true"));

        assertThat(ollama.modelName()).isEqualTo("nomic-embed-text");
    }

    @Test
    void bedrockTitanIsBuiltForAwsWithoutCallingIt() {
        KnowledgeEmbeddings bedrock = embeddings(Map.of(
                "grocery.ai.knowledge.enabled", "true",
                "grocery.ai.knowledge.embedding.provider", "bedrock",
                "grocery.ai.knowledge.embedding.model", "amazon.titan-embed-text-v2:0",
                "grocery.ai.knowledge.embedding.dimensions", "1024",
                "grocery.ai.knowledge.embedding.region", "ap-south-1"));

        assertThat(bedrock.modelName()).isEqualTo("amazon.titan-embed-text-v2:0");
    }

    @Test
    void anUnknownProviderStopsTheStartWithAClearMessage() {
        assertThatThrownBy(() -> embeddings(Map.of("grocery.ai.knowledge.enabled", "true",
                "grocery.ai.knowledge.embedding.provider", "openai")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("use ollama or bedrock");
    }

    @Test
    void nothingIsBuiltWhenTheKnowledgeBaseIsOff() {
        KnowledgeEmbeddings off = embeddings(Map.of("grocery.ai.knowledge.embedding.provider", "openai"));

        assertThatThrownBy(() -> off.embedQuestion("hello")).hasMessageContaining("disabled");
    }
}
