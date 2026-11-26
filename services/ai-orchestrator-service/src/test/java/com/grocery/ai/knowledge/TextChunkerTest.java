package com.grocery.ai.knowledge;

import com.grocery.ai.knowledge.ingest.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TextChunker: chunks of about chunk-tokens × 4 characters, cut at paragraphs, then sentences, then words. */
class TextChunkerTest {

    /** 20 tokens = 80 characters per chunk, 5 tokens = 20 characters of overlap. */
    private final TextChunker chunker = new TextChunker(20, 5);

    @Test
    void shortTextIsOneChunk() {
        assertThat(chunker.split("Refunds take five days.")).containsExactly("Refunds take five days.");
    }

    @Test
    void paragraphsArePackedTogetherUntilTheChunkIsFull() {
        String text = "First short paragraph.\n\nSecond short one.\n\nA third paragraph that is long enough to need "
                + "its own chunk.";

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst()).isEqualTo("First short paragraph.\n\nSecond short one.");
        assertThat(chunks.get(1)).endsWith("A third paragraph that is long enough to need its own chunk.");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(80));
    }

    @Test
    void theNextChunkStartsWithTheLastSentenceOfThePreviousOne() {
        String text = "Orders below the minimum are refused. Delivery is free above a limit.\n\n"
                + "Cancellations are possible until the store accepts the order.";

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(2);
        // the overlap (20 characters) holds no whole sentence, so the last words are repeated
        assertThat(chunks.get(1)).startsWith("above a limit.");
        assertThat(chunks.get(1)).contains("Cancellations are possible");
    }

    @Test
    void aLongParagraphIsCutBetweenSentencesAndALongSentenceBetweenWords() {
        TextChunker small = new TextChunker(10, 0);            // 40 characters, no overlap
        String paragraph = "One two three four five six seven eight nine ten eleven twelve. Short end.";

        List<String> chunks = small.split(paragraph);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(40));
        assertThat(String.join(" ", chunks).replace("\n\n", " ")).isEqualTo(paragraph);
    }

    @Test
    void nothingIsLostWithoutOverlap() {
        TextChunker noOverlap = new TextChunker(15, 0);
        String text = "Alpha beta gamma.\n\nDelta epsilon zeta eta theta.\n\nIota kappa lambda mu nu xi omicron pi.";

        List<String> chunks = noOverlap.split(text);

        assertThat(String.join("\n\n", chunks)).isEqualTo(text);
    }

    @Test
    void emptyTextHasNoChunks() {
        assertThat(chunker.split("   \n\n  ")).isEmpty();
    }
}
