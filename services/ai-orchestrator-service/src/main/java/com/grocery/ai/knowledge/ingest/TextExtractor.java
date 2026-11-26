package com.grocery.ai.knowledge.ingest;

import com.grocery.ai.knowledge.KnowledgeException;
import com.grocery.ai.knowledge.document.DocumentFormat;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The plain text of a validated file ({@link DocumentValidator}), paragraphs separated by a blank line:
 * <ul>
 *   <li>PDF: Spring AI's {@link PagePdfDocumentReader} (PDFBox), page by page. A scanned PDF has no text (OCR is
 *       not done here) and is refused as empty later;</li>
 *   <li>DOCX: the paragraphs of {@code word/document.xml}, read with a safe XML parser (no DTDs, no external
 *       entities), so no Office library is needed;</li>
 *   <li>HTML: Spring AI's {@link JsoupDocumentReader}: headings, paragraphs, list items and table cells, never
 *       scripts or styles;</li>
 *   <li>TXT and Markdown: the text as it is (Markdown headings help the chunks; its symbols are harmless).</li>
 * </ul>
 */
@Component
public class TextExtractor {

    private static final String WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** Largest uncompressed word/document.xml read (a ZIP bomb stops here). */
    private static final int MAX_DOCX_XML_BYTES = 30 * 1024 * 1024;
    private static final String HTML_BLOCKS = "h1, h2, h3, h4, h5, h6, p, li, pre, blockquote, td, th, dt, dd";

    public String extract(DocumentFormat format, byte[] content) {
        return switch (format) {
            case PDF -> pdf(content);
            case DOCX -> docx(content);
            case HTML -> html(content);
            case TXT, MARKDOWN -> utf8(content);
        };
    }

    private static String pdf(byte[] content) {
        try {
            List<Document> pages = new PagePdfDocumentReader(new ByteArrayResource(content)).get();
            return pages.stream().map(Document::getText).filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n\n"));
        } catch (RuntimeException e) {
            // Damaged, or protected with a password that is needed to open it.
            throw KnowledgeException.invalid("The PDF could not be read (damaged or password-protected).");
        }
    }

    private static String html(byte[] content) {
        ByteArrayResource resource = new ByteArrayResource(content);
        String text = read(new JsoupDocumentReader(resource, JsoupDocumentReaderConfig.builder()
                .selector(HTML_BLOCKS).separator("\n\n").build()));
        if (text.isBlank()) {
            // Text only in <div>s and <span>s: take the whole body.
            text = read(new JsoupDocumentReader(resource, JsoupDocumentReaderConfig.builder()
                    .allElements(true).build()));
        }
        return text;
    }

    private static String read(JsoupDocumentReader reader) {
        return reader.get().stream().map(Document::getText).filter(text -> text != null)
                .collect(Collectors.joining("\n\n"));
    }

    private static String utf8(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // ---- DOCX ---------------------------------------------------------------------------------------------------

    static String docx(byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return wordParagraphs(readLimited(zip));
                }
            }
        } catch (IOException | XMLStreamException e) {
            throw KnowledgeException.invalid("The Word document is damaged.");
        }
        throw KnowledgeException.invalid("The file is not a Word (.docx) document.");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_DOCX_XML_BYTES) {
                throw KnowledgeException.invalid("The Word document is too large once unpacked.");
            }
        }
        return out.toByteArray();
    }

    /** The text of {@code <w:t>} elements, a blank line after each {@code <w:p>} paragraph. */
    private static String wordParagraphs(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && WORD_NAMESPACE.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = true;
                        case "tab" -> text.append('\t');
                        case "br", "cr" -> text.append('\n');
                        default -> {
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && inText) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && WORD_NAMESPACE.equals(reader.getNamespaceURI())) {
                    if ("t".equals(reader.getLocalName())) {
                        inText = false;
                    } else if ("p".equals(reader.getLocalName())) {
                        text.append("\n\n");
                    }
                }
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }
}
