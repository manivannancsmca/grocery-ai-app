package com.grocery.ai.knowledge;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small real PDF and Word files for the knowledge base tests. */
public final class KnowledgeTestFiles {

    private KnowledgeTestFiles() {
    }

    /** A one-page PDF with one line of text (PDFBox). */
    public static byte[] pdf(String line) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(line);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A minimal .docx: one Word paragraph per argument. */
    public static byte[] docx(String... paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<w:p><w:r><w:t>").append(paragraph).append("</w:t></w:r></w:p>");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w="
                + "\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
                + "</w:body></w:document>";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>");
        entries.put("word/document.xml", xml);
        return zip(entries);
    }

    public static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
