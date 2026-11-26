package com.grocery.ai.knowledge.ingest;

import com.grocery.ai.knowledge.KnowledgeException;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.document.DocumentFormat;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks an uploaded file before anything reads it: not empty, not too large, an accepted extension, and content that
 * really is of that type (an .exe renamed to .pdf is refused):
 * <ul>
 *   <li>PDF: starts with {@code %PDF-};</li>
 *   <li>DOCX: a ZIP with {@code word/document.xml} and no macros ({@code vbaProject.bin}: a .docm renamed);</li>
 *   <li>TXT, Markdown, HTML: valid UTF-8 text without NUL bytes.</li>
 * </ul>
 */
@Component
public class DocumentValidator {

    /** A Word file has a few dozen entries; far more is not a Word file (or a ZIP bomb). */
    private static final int MAX_ZIP_ENTRIES = 1000;

    private final long maxBytes;

    public DocumentValidator(KnowledgeProperties properties) {
        this.maxBytes = properties.upload().maxFileSize().toBytes();
    }

    /** The file's format, or a 4xx {@link KnowledgeException} saying what is wrong. */
    public DocumentFormat validate(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw KnowledgeException.empty();
        }
        if (content.length > maxBytes) {
            throw KnowledgeException.tooLarge(maxBytes);
        }
        DocumentFormat format = DocumentFormat.ofFileName(fileName).orElseThrow(KnowledgeException::unsupportedType);
        switch (format) {
            case PDF -> requirePdf(content);
            case DOCX -> requireDocx(content);
            case TXT, MARKDOWN, HTML -> requireUtf8Text(content);
        }
        return format;
    }

    private static void requirePdf(byte[] content) {
        if (!startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw KnowledgeException.invalid("The file is not a PDF.");
        }
    }

    private static void requireDocx(byte[] content) {
        if (!startsWith(content, new byte[]{'P', 'K', 3, 4})) {
            throw KnowledgeException.invalid("The file is not a Word (.docx) document.");
        }
        boolean hasDocument = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw KnowledgeException.invalid("The file is not a Word (.docx) document.");
                }
                String name = entry.getName();
                if (name.endsWith("vbaProject.bin")) {
                    throw KnowledgeException.invalid("Documents with macros are not accepted. Save it as a plain "
                            + ".docx without macros.");
                }
                if ("word/document.xml".equals(name)) {
                    hasDocument = true;
                }
            }
        } catch (IOException e) {
            throw KnowledgeException.invalid("The Word document is damaged.");
        }
        if (!hasDocument) {
            throw KnowledgeException.invalid("The file is not a Word (.docx) document.");
        }
    }

    private static void requireUtf8Text(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw KnowledgeException.invalid("The file is not UTF-8 text. Save it as UTF-8 and upload it again.");
        }
        for (byte b : content) {
            if (b == 0) {
                throw KnowledgeException.invalid("The file is not a text file.");
            }
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
