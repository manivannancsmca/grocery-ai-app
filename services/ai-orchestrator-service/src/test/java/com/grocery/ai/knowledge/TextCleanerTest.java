package com.grocery.ai.knowledge;

import com.grocery.ai.knowledge.ingest.TextCleaner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerTest {

    @Test
    void spacesAndLineEndsAreTidiedAndParagraphsKept() {
        String raw = "  Refund   policy \r\n\r\n\r\n\tRefunds take  5 days.  \r\nThen   done.\n\n\n\n";

        assertThat(TextCleaner.clean(raw)).isEqualTo("Refund policy\n\nRefunds take 5 days.\nThen done.");
    }

    @Test
    void invisibleCharactersAreRemoved() {
        assertThat(TextCleaner.clean("Deli​very\u0007 fee﻿")).isEqualTo("Delivery fee");
    }

    @Test
    void aWordHyphenatedAcrossLinesIsJoined() {
        assertThat(TextCleaner.clean("free deli-\nvery above the limit")).isEqualTo("free delivery above the limit");
        // a real hyphen before a capital stays
        assertThat(TextCleaner.clean("Cash-\nOn Delivery")).isEqualTo("Cash-\nOn Delivery");
    }

    @Test
    void nullIsEmpty() {
        assertThat(TextCleaner.clean(null)).isEmpty();
    }
}
