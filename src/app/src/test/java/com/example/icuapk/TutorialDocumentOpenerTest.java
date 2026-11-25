package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TutorialDocumentOpenerTest {
    @Test
    public void genericMimeUsesDocxExtensionIncludingUppercase() {
        assertEquals("word", TutorialDocumentOpener.kindOf(
                "application/octet-stream", "护理教程.DOCX"));
        assertEquals("word", TutorialDocumentOpener.kindOf(
                "text/plain", "护理教程.docx"));
        assertEquals("word", TutorialDocumentOpener.kindOf(null, "legacy.doc"));
    }

    @Test
    public void standardWordMimeStillWorksWithoutFilename() {
        assertEquals("word", TutorialDocumentOpener.kindOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ""));
    }

    @Test
    public void existingPdfAndTextClassificationIsPreserved() {
        assertEquals("pdf", TutorialDocumentOpener.kindOf("application/pdf", "guide.bin"));
        assertEquals("text", TutorialDocumentOpener.kindOf("application/octet-stream", "notes.TXT"));
        assertEquals("other", TutorialDocumentOpener.kindOf("application/octet-stream", "book.bin"));
    }

    @Test
    public void realDocxZipExtractsParagraphsTabsBreaksAndEntities() throws Exception {
        File file = File.createTempFile("icu-tutorial-", ".docx");
        try {
            ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file));
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<?xml version=\"1.0\"?><w:document><w:body>"
                    + "<w:p><w:r><w:t>甲 &amp; 乙</w:t><w:tab/><w:t>第二</w:t>"
                    + "<w:br/><w:t>行</w:t></w:r></w:p>"
                    + "<w:p><w:r><w:t>&lt;下一段&gt;</w:t></w:r></w:p>"
                    + "</w:body></w:document>";
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.close();

            assertEquals("甲 & 乙\t第二\n行\n<下一段>", TutorialDocumentOpener.readDocx(file));
        } finally {
            assertTrue(!file.exists() || file.delete());
        }
    }

    @Test
    public void malformedDocxDoesNotReturnZipBytesAsText() throws Exception {
        File file = File.createTempFile("icu-tutorial-bad-", ".docx");
        try {
            FileOutputStream output = new FileOutputStream(file);
            output.write("not a zip".getBytes(StandardCharsets.UTF_8));
            output.close();
            assertNull(TutorialDocumentOpener.readDocx(file));
        } finally {
            assertTrue(!file.exists() || file.delete());
        }
    }

    @Test
    public void oversizedDisplayedTextIsTruncatedExplicitly() {
        StringBuilder source = new StringBuilder("<w:p><w:t>");
        for (int index = 0; index < 200010; index++) {
            source.append('测');
        }
        source.append("</w:t></w:p>");
        String text = TutorialDocumentOpener.docxToText(source.toString());
        assertTrue(text.startsWith("测测测"));
        assertTrue(text.endsWith("（正文过长，仅显示前200000字）"));
        assertTrue(text.length() < 200050);
    }
}
