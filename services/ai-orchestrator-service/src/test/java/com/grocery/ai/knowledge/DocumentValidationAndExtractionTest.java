package com.grocery.ai.knowledge;

import com.grocery.ai.knowledge.document.DocumentFormat;
import com.grocery.ai.knowledge.ingest.DocumentValidator;
import com.grocery.ai.knowledge.ingest.TextCleaner;
import com.grocery.ai.knowledge.ingest.TextExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DocumentValidator (type by content, size, macros) and TextExtractor (PDF, DOCX, HTML, TXT, Markdown). */
class DocumentValidationAndExtractionTest {

    private final DocumentValidator validator = new DocumentValidator(properties(Map.of(
            "grocery.ai.knowledge.upload.max-file-size", "64KB")));
    private final TextExtractor extractor = new TextExtractor();

    static KnowledgeProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("grocery.ai.knowledge", KnowledgeProperties.class);
    }

    // ---- validation -----------------------------------------------------------------------------------------------

    @Test
    void acceptedTypesAreRecognisedByExtensionAndContent() throws IOException {
        assertThat(validator.validate("Refunds.PDF", KnowledgeTestFiles.pdf("Refund policy"))).isEqualTo(DocumentFormat.PDF);
        assertThat(validator.validate("faq.docx", KnowledgeTestFiles.docx("Question", "Answer"))).isEqualTo(DocumentFormat.DOCX);
        assertThat(validator.validate("notes.txt", utf8("Hello"))).isEqualTo(DocumentFormat.TXT);
        assertThat(validator.validate("guide.md", utf8("# Guide"))).isEqualTo(DocumentFormat.MARKDOWN);
        assertThat(validator.validate("page.htm", utf8("<p>Hi</p>"))).isEqualTo(DocumentFormat.HTML);
    }

    @Test
    void otherTypesAreRefused() {
        assertThatThrownBy(() -> validator.validate("setup.exe", utf8("MZ")))
                .hasFieldOrPropertyWithValue("errorCode", "UNSUPPORTED_DOCUMENT_TYPE");
        assertThatThrownBy(() -> validator.validate("old.doc", utf8("x")))
                .hasFieldOrPropertyWithValue("errorCode", "UNSUPPORTED_DOCUMENT_TYPE");
        assertThatThrownBy(() -> validator.validate("no-extension", utf8("x")))
                .hasFieldOrPropertyWithValue("errorCode", "UNSUPPORTED_DOCUMENT_TYPE");
    }

    @Test
    void aFileThatIsNotWhatItsNameSaysIsRefused() {
        assertThatThrownBy(() -> validator.validate("policy.pdf", utf8("just text")))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_DOCUMENT");
        assertThatThrownBy(() -> validator.validate("policy.docx", utf8("just text")))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_DOCUMENT");
        byte[] binary = {'a', 0, 'b'};
        assertThatThrownBy(() -> validator.validate("notes.txt", binary))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_DOCUMENT");
        byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> validator.validate("notes.txt", latin1))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_DOCUMENT");
    }

    @Test
    void wordFilesWithMacrosAreRefused() throws IOException {
        byte[] withMacros = KnowledgeTestFiles.zip(Map.of("word/document.xml", "<w:document/>", "word/vbaProject.bin", "macro"));

        assertThatThrownBy(() -> validator.validate("faq.docx", withMacros))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_DOCUMENT")
                .hasMessageContaining("macros");
    }

    @Test
    void emptyAndTooLargeFilesAreRefused() {
        assertThatThrownBy(() -> validator.validate("a.txt", new byte[0]))
                .hasFieldOrPropertyWithValue("errorCode", "EMPTY_DOCUMENT");
        assertThatThrownBy(() -> validator.validate("a.txt", new byte[65 * 1024]))
                .hasFieldOrPropertyWithValue("errorCode", "DOCUMENT_TOO_LARGE");
    }

    // ---- extraction -----------------------------------------------------------------------------------------------

    @Test
    void pdfTextIsExtracted() throws IOException {
        String text = TextCleaner.clean(extractor.extract(DocumentFormat.PDF, KnowledgeTestFiles.pdf("Refunds reach your bank in days")));

        assertThat(text).contains("Refunds reach your bank in days");
    }

    @Test
    void docxParagraphsBecomeParagraphs() throws IOException {
        String text = TextCleaner.clean(extractor.extract(DocumentFormat.DOCX,
                KnowledgeTestFiles.docx("How do I cancel?", "Open the order and tap Cancel.")));

        assertThat(text).isEqualTo("How do I cancel?\n\nOpen the order and tap Cancel.");
    }

    @Test
    void htmlKeepsTheTextOfBlocksAndDropsScripts() {
        String html = """
                <html><head><title>FAQ</title><script>alert('x')</script><style>p{}</style></head>
                <body><h1>Delivery</h1><p>We deliver from 7 am.</p><ul><li>Groceries</li><li>Food</li></ul>
                <script>steal()</script></body></html>""";

        String text = TextCleaner.clean(extractor.extract(DocumentFormat.HTML, utf8(html)));

        assertThat(text).isEqualTo("Delivery\n\nWe deliver from 7 am.\n\nGroceries\n\nFood");
    }

    @Test
    void htmlWithTextOnlyInDivsFallsBackToTheWholeBody() {
        String text = extractor.extract(DocumentFormat.HTML, utf8("<body><div>Only a div</div></body>"));

        assertThat(text.strip()).isEqualTo("Only a div");
    }

    @Test
    void textAndMarkdownAreReadAsUtf8WithoutTheByteOrderMark() {
        assertThat(extractor.extract(DocumentFormat.TXT, utf8("﻿चावल ₹50"))).isEqualTo("चावल ₹50");
        assertThat(extractor.extract(DocumentFormat.MARKDOWN, utf8("# Title\n\nBody"))).isEqualTo("# Title\n\nBody");
    }

    // ---- test files -----------------------------------------------------------------------------------------------

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
