package com.grocery.ai;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.document.KnowledgeSeeder;
import com.grocery.ai.knowledge.index.KnowledgeIndex;
import com.grocery.ai.knowledge.ingest.IngestionWorker;
import com.grocery.ai.knowledge.search.KnowledgeQuestionDetector;
import com.grocery.ai.knowledge.search.KnowledgeSearch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge base's evaluation (docs/rag/RAG-TESTING-GUIDE.md, "Evaluation"): the starter articles indexed with
 * the REAL embedding model, 44 messages (answerable questions, live store rules, questions the help center cannot
 * answer, and ordinary shopping messages that must not get articles), retrieval and routing measured for each, and —
 * unless {@code -Drag.eval.answers=false} — each question (not the shopping messages) asked through the real chat
 * endpoint with the REAL chat model. Writes {@code target/rag-eval/report.md}.
 *
 * <p>Not part of the normal build (tag {@code rag-eval}, excluded in pom.xml): it needs Ollama with the chat and
 * embedding models and takes 10–30 minutes on a CPU. Run it with
 * {@code mvn test -Dtest=KnowledgeEvaluationTest -DexcludedGroups=} (docs/rag/RAG-TESTING-GUIDE.md). Options:
 * {@code -Drag.eval.ollama=http://host:port}, {@code -Drag.eval.chunkTokens}, {@code -Drag.eval.overlapTokens},
 * {@code -Drag.eval.topK}, {@code -Drag.eval.threshold}, {@code -Drag.eval.unrecognisedThreshold},
 * {@code -Drag.eval.only=Q03,Q08} (only these questions).
 */
@Tag("rag-eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grocery.ai.provider=ollama",
        "grocery.ai.model-timeout=300s",
        "grocery.ai.ollama.warm-up=false",
        "grocery.ai.messages-per-hour=10000",
        "grocery.ai.mcp.enabled=false",
        "grocery.ai.approvals.pollers-enabled=false",
        "grocery.ai.knowledge.enabled=true",
        "grocery.ai.knowledge.index-alias=eval-knowledge",
        "grocery.ai.knowledge.ingestion.poller-enabled=false",
        "spring.datasource.hikari.connection-timeout=30000",
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
class KnowledgeEvaluationTest {

    static final String OLLAMA_URL = System.getProperty("rag.eval.ollama", "http://host.docker.internal:11435");
    static final boolean ANSWERS = Boolean.parseBoolean(System.getProperty("rag.eval.answers", "true"));
    /** Words of an answer that declines to state a policy it could not verify. */
    static final List<String> REFUSALS = List.of("couldn't find", "could not find", "no verified", "not verified",
            "can't verify", "cannot verify", "can't confirm", "cannot confirm", "don't have information",
            "do not have information", "contact customer support", "contact support", "raise a complaint",
            "cannot check", "can't check", "not able to find", "no information");
    static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    static final String FEES = "{\"groceryBaseFee\":25.00,\"groceryIncludedKm\":3,\"groceryPerExtraKm\":8.00,"
            + "\"groceryFreeAbove\":499.00,\"groceryMinimumOrder\":150.00,\"restaurantBaseFee\":30.00,"
            + "\"restaurantIncludedKm\":3,\"restaurantPerExtraKm\":10.00}";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.MYSQL::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.MYSQL::getPassword);
        registry.add("grocery.ai.gateway-url", AbstractIntegrationTest.GATEWAY::baseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", AbstractIntegrationTest.TOKENS::jwksUri);
        registry.add("spring.elasticsearch.uris", () -> "http://" + AbstractKnowledgeIntegrationTest.ELASTICSEARCH.getHost()
                + ":" + AbstractKnowledgeIntegrationTest.ELASTICSEARCH.getMappedPort(9200));
        registry.add("grocery.ai.ollama.base-url", () -> OLLAMA_URL);
        option(registry, "rag.eval.chunkTokens", "grocery.ai.knowledge.chunking.chunk-tokens");
        option(registry, "rag.eval.overlapTokens", "grocery.ai.knowledge.chunking.overlap-tokens");
        option(registry, "rag.eval.topK", "grocery.ai.knowledge.retrieval.top-k");
        option(registry, "rag.eval.threshold", "grocery.ai.knowledge.retrieval.similarity-threshold");
        option(registry, "rag.eval.unrecognisedThreshold", "grocery.ai.knowledge.retrieval.unrecognised-threshold");
    }

    private static void option(DynamicPropertyRegistry registry, String systemProperty, String property) {
        String value = System.getProperty(systemProperty);
        if (value != null && !value.isBlank()) {
            registry.add(property, () -> value);
        }
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    KnowledgeSeeder seeder;
    @Autowired
    IngestionWorker worker;
    @Autowired
    KnowledgeSearch search;
    @Autowired
    KnowledgeIndex index;
    @Autowired
    KnowledgeProperties properties;
    @Autowired
    ElasticsearchClient elasticsearch;
    @Autowired
    JsonMapper json;
    @Autowired
    DataSource dataSource;

    record Question(String id, String kind, String role, String agent, String question, List<String> expectedTitles,
                    List<String> mustContainAny, List<String> mustNotContainAny) {
    }

    /**
     * @param route       what the rules made of the message (KnowledgeQuestionDetector)
     * @param beforeModel the chat gives the articles to the model before it answers: a recognised question with a
     *                    match, or another message (of three words or more, as in KnowledgeAssist) whose best match
     *                    reaches the unrecognised threshold
     */
    record Row(Question question, String route, boolean beforeModel, String retrievalStatus, List<String> titles,
               String snippets, double topSimilarity, Boolean hit, double precision, double recall, long retrievalMs, String answer, List<String> sources,
               Boolean correct, Boolean refused, boolean faithful, long answerMs) {
    }

    @Test
    void evaluateTheKnowledgeBase() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM knowledge_chunk").update();
        jdbc.sql("DELETE FROM knowledge_document_text").update();
        jdbc.sql("DELETE FROM knowledge_document").update();
        index.ensure();
        elasticsearch.deleteByQuery(d -> d.index(properties.indexAlias()).query(q -> q.matchAll(m -> m))
                .refresh(true).conflicts(Conflicts.Proceed));

        int articles = seeder.seed();
        long indexingStarted = System.nanoTime();
        assertThat(worker.drain()).as("starter articles indexed with the real embedding model").isEqualTo(articles);
        long indexingMs = (System.nanoTime() - indexingStarted) / 1_000_000;
        long chunks = jdbc.sql("SELECT COUNT(*) FROM knowledge_chunk").query(Long.class).single();

        AbstractIntegrationTest.GATEWAY.reset();
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/cart", 200, Fixtures.json(Fixtures.defaultCart()));
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/cart/preview", 200, Fixtures.preview(true));
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/pricing/delivery-fees", 200, FEES);

        List<Row> rows = new ArrayList<>();
        for (Question question : questions()) {
            rows.add(evaluate(question));
        }
        String report = report(rows, articles, chunks, indexingMs);
        Path file = Path.of("target", "rag-eval", "report.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private Row evaluate(Question question) {
        long started = System.nanoTime();
        KnowledgeSearch.Result result = search.forReader(question.question(), Set.of(question.role()));
        long retrievalMs = (System.nanoTime() - started) / 1_000_000;
        List<String> titles = result.snippets().stream().map(KnowledgeSearch.Snippet::title).toList();
        String snippets = String.join("\n", result.snippets().stream().map(KnowledgeSearch.Snippet::text).toList());
        double topSimilarity = result.snippets().stream().mapToDouble(KnowledgeSearch.Snippet::similarity).max()
                .orElse(0);
        KnowledgeQuestionDetector.Route route = KnowledgeQuestionDetector.route(question.question());
        boolean beforeModel = route != KnowledgeQuestionDetector.Route.NONE ? result.found()
                : question.question().strip().split("\\s+").length >= 3
                && topSimilarity >= properties.retrieval().unrecognisedThreshold();
        Boolean hit = null;
        double precision = 0;
        double recall = 0;
        if (!question.expectedTitles().isEmpty()) {
            hit = titles.stream().anyMatch(question.expectedTitles()::contains);
            precision = titles.isEmpty() ? 0
                    : (double) titles.stream().filter(question.expectedTitles()::contains).count() / titles.size();
            recall = (double) question.expectedTitles().stream().filter(titles::contains).count()
                    / question.expectedTitles().size();
        }
        String answer = null;
        List<String> sources = List.of();
        Boolean correct = null;
        Boolean refused = null;
        boolean faithful = true;
        long answerMs = 0;
        // SHOPPING: live-data questions, retrieval only (they should find no article; their tools are not stubbed)
        if (ANSWERS && !"SHOPPING".equals(question.kind())) {
            long asked = System.nanoTime();
            SseClient.Result chat = SseClient.post(port, "/api/ai/agents/" + question.agent() + "/chat",
                    AbstractIntegrationTest.TOKENS.token(UUID.randomUUID(), question.role()),
                    json.writeValueAsString(java.util.Map.of("message", question.question())), "text/event-stream");
            answerMs = (System.nanoTime() - asked) / 1_000_000;
            answer = chat.status() != 200 ? "[HTTP " + chat.status() + "]"
                    : !chat.named("error").isEmpty() ? "[" + chat.first("error").text("code") + "]" : chat.answer();
            sources = chat.named("sources").stream().flatMap(e -> e.data().get("sources").valueStream())
                    .map(s -> s.get("title").asString()).toList();
            String lower = answer.toLowerCase(Locale.ROOT);
            refused = REFUSALS.stream().anyMatch(lower::contains);
            if (!"UNANSWERABLE".equals(question.kind())) {
                correct = containsAny(lower, question.mustContainAny()) && !containsAny(lower, question.mustNotContainAny());
            }
            faithful = !containsAny(lower, question.mustNotContainAny())
                    && ungrounded(answer, question.question() + "\n" + snippets + "\n" + FEES).isEmpty();
        }
        return new Row(question, route.name(), beforeModel, result.status().name(), titles, snippets, topSimilarity, hit, precision, recall,
                retrievalMs, answer, sources, correct, refused, faithful, answerMs);
    }

    private static boolean containsAny(String lowerText, List<String> phrases) {
        return phrases.stream().anyMatch(phrase -> lowerText.contains(phrase.toLowerCase(Locale.ROOT)));
    }

    /** Numbers of two or more digits in the answer that appear nowhere in what the model was given. */
    static List<String> ungrounded(String answer, String sources) {
        Set<String> known = new HashSet<>();
        Matcher inSources = NUMBER.matcher(sources);
        while (inSources.find()) {
            known.add(normalize(inSources.group()));
        }
        List<String> missing = new ArrayList<>();
        Matcher inAnswer = NUMBER.matcher(answer);
        while (inAnswer.find()) {
            String value = normalize(inAnswer.group());
            if (value.replace(".", "").length() >= 2 && !known.contains(value)) {
                missing.add(inAnswer.group());
            }
        }
        return missing;
    }

    private static String normalize(String number) {
        String plain = number.replace(",", "").replaceAll("\\.$", "");
        try {
            return new java.math.BigDecimal(plain).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return plain;
        }
    }

    /** The questions, or only those listed in {@code -Drag.eval.only=Q03,Q08} (re-checking a few after a change). */
    private List<Question> questions() throws Exception {
        Set<String> only = Set.of(System.getProperty("rag.eval.only", "").split("\\s*,\\s*")).stream()
                .filter(id -> !id.isBlank()).collect(java.util.stream.Collectors.toSet());
        try (InputStream in = new ClassPathResource("knowledge-eval/questions.json").getInputStream()) {
            List<Question> questions = new ArrayList<>();
            for (JsonNode node : json.readTree(in)) {
                if (!only.isEmpty() && !only.contains(node.get("id").asString())) {
                    continue;
                }
                questions.add(new Question(node.get("id").asString(), node.get("kind").asString(),
                        node.get("role").asString(), node.get("agent").asString(), node.get("question").asString(),
                        strings(node.get("expectedTitles")), strings(node.get("answerMustContainAny")),
                        strings(node.get("answerMustNotContainAny"))));
            }
            return questions;
        }
    }

    private static List<String> strings(JsonNode array) {
        return array == null ? List.of() : array.valueStream().map(JsonNode::asString).toList();
    }

    // ---- the report -----------------------------------------------------------------------------------------------

    private String report(List<Row> rows, int articles, long chunks, long indexingMs) {
        List<Row> answerable = rows.stream().filter(r -> Set.of("ANSWERABLE", "LIVE").contains(r.question().kind()))
                .toList();
        List<Row> unanswerable = rows.stream().filter(r -> "UNANSWERABLE".equals(r.question().kind())).toList();
        List<Row> shopping = rows.stream().filter(r -> "SHOPPING".equals(r.question().kind())).toList();
        StringBuilder out = new StringBuilder();
        out.append("# Knowledge base evaluation\n\n")
                .append("Run ").append(Instant.now()).append(". Chat model: Ollama at ").append(OLLAMA_URL)
                .append("; embeddings: ").append(properties.embedding().model()).append(".\n\n")
                .append("Settings: chunk ").append(properties.chunking().chunkTokens()).append(" tokens, overlap ")
                .append(properties.chunking().overlapTokens()).append(", top-K ")
                .append(properties.retrieval().topK()).append(", similarity threshold ")
                .append(properties.retrieval().similarityThreshold()).append(", unrecognised threshold ")
                .append(properties.retrieval().unrecognisedThreshold()).append(".\n\n")
                .append("Knowledge base: ").append(articles).append(" starter articles, ").append(chunks)
                .append(" chunks, indexed in ").append(indexingMs / 1000.0).append(" s.\n\n");
        out.append("## Summary\n\n| Metric | Value |\n|---|---|\n");
        metric(out, "Questions", rows.size() + " (" + answerable.size() + " answerable or live, "
                + unanswerable.size() + " not in the knowledge base, " + shopping.size()
                + " shopping / live-data, retrieval only)");
        metric(out, "Retrieval relevance (hit@K, answerable)", percent(answerable.stream()
                .filter(r -> r.hit() != null && r.hit()).count(), answerable.size()));
        metric(out, "Context precision (answerable)", String.format(Locale.ROOT, "%.2f",
                answerable.stream().mapToDouble(Row::precision).average().orElse(0)));
        metric(out, "Context recall (answerable)", String.format(Locale.ROOT, "%.2f",
                answerable.stream().mapToDouble(Row::recall).average().orElse(0)));
        metric(out, "No-context handling (retrieval NO_MATCH, unanswerable)", percent(unanswerable.stream()
                .filter(r -> "NO_MATCH".equals(r.retrievalStatus())).count(), unanswerable.size()));
        metric(out, "Answerable questions given the articles before the model", percent(answerable.stream()
                .filter(Row::beforeModel).count(), answerable.size()));
        metric(out, "Shopping messages given articles before the model (should be none)", percent(shopping.stream()
                .filter(Row::beforeModel).count(), shopping.size()));
        metric(out, "Best similarity: answerable lowest / unanswerable highest / shopping highest", String.format(
                Locale.ROOT, "%.3f / %.3f / %.3f",
                answerable.stream().mapToDouble(Row::topSimilarity).min().orElse(0),
                unanswerable.stream().mapToDouble(Row::topSimilarity).max().orElse(0),
                shopping.stream().mapToDouble(Row::topSimilarity).max().orElse(0)));
        metric(out, "Retrieval latency p50 / p95", percentile(rows, 50, true) + " / " + percentile(rows, 95, true)
                + " ms");
        if (ANSWERS) {
            metric(out, "Answer correctness (answerable and live)", percent(answerable.stream()
                    .filter(r -> Boolean.TRUE.equals(r.correct())).count(), answerable.size()));
            metric(out, "Refusals (unanswerable: did not invent an answer)", percent(unanswerable.stream()
                    .filter(r -> Boolean.TRUE.equals(r.refused())).count(), unanswerable.size()));
            List<Row> answered = rows.stream().filter(r -> r.answer() != null).toList();
            metric(out, "Faithfulness (no figures from nowhere, no forbidden claims)", percent(answered.stream()
                    .filter(Row::faithful).count(), answered.size()));
            metric(out, "Answers showing a help-center source (answerable)", percent(answerable.stream()
                    .filter(r -> !r.sources().isEmpty()).count(), answerable.size()));
            metric(out, "Answer latency p50 / p95", percentile(rows, 50, false) / 1000.0 + " / "
                    + percentile(rows, 95, false) / 1000.0 + " s");
        }
        out.append("\n## Questions\n\nRoute: what the rules made of the message; Before: the articles were given to the "
                + "model before it answered.\n\n| Id | Kind | Question | Route | Before | Retrieval | Best sim. | "
                + "Top titles | Hit | Answer | Correct | Refused | Sources | Time |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            out.append("| ").append(row.question().id()).append(" | ").append(row.question().kind()).append(" | ")
                    .append(cell(row.question().question())).append(" | ").append(row.route()).append(" | ")
                    .append(row.beforeModel() ? "yes" : "no").append(" | ").append(row.retrievalStatus())
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", row.topSimilarity()))
                    .append(" | ").append(cell(String.join("; ", row.titles().stream().distinct().limit(3).toList())))
                    .append(" | ").append(flag(row.hit())).append(" | ").append(cell(shorten(row.answer())))
                    .append(" | ").append(flag(row.correct())).append(" | ").append(flag(row.refused()))
                    .append(" | ").append(cell(String.join("; ", row.sources()))).append(" | ")
                    .append(row.answerMs() / 1000.0).append(" s |\n");
        }
        return out.toString();
    }

    private static void metric(StringBuilder out, String name, String value) {
        out.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private static String percent(long count, int total) {
        return total == 0 ? "–" : count + " / " + total + " (" + Math.round(100.0 * count / total) + " %)";
    }

    private static long percentile(List<Row> rows, int p, boolean retrieval) {
        List<Long> values = rows.stream().map(r -> retrieval ? r.retrievalMs() : r.answerMs()).sorted().toList();
        return values.isEmpty() ? 0 : values.get(Math.min(values.size() - 1, (int) Math.ceil(p / 100.0 * values.size()) - 1));
    }

    private static String flag(Boolean value) {
        return value == null ? "–" : value ? "yes" : "**no**";
    }

    private static String shorten(String text) {
        if (text == null) {
            return "–";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 160 ? flat.substring(0, 157) + "…" : flat;
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }
}
