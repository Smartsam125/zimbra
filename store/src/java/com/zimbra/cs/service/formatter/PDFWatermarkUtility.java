package com.zimbra.cs.service.formatter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static com.zimbra.common.util.ZimbraLog.doc;

public class PDFWatermarkUtility {

    private static final float IMAGE_WIDTH = 150;
    private static final float IMAGE_HEIGHT = 150;
    private static final float FONT_SIZE = 7;
    //private static final float ALPHA = 0.15f;
    private static final float ALPHA = 0.03f; // Barely visible transparency


    private PDFWatermarkUtility() {
        // Private constructor to prevent instantiation
    }

//    public static void addWatermark(PDDocument document, String userName, Locale locale, String imagePath, String outputPath,String uuid) {
//        try {
//            String downloadDateText = formatDate(locale);
//            PDImageXObject image = PDImageXObject.createFromFile(imagePath, document);
//            PDDocumentInformation pdd = document.getDocumentInformation();
//            pdd.setAuthor(userName);
//            pdd.setCustomMetadataValue("DocumentId",uuid);
//            pdd.setCustomMetadataValue("DownloadDate",downloadDateText);
//            for (PDPage page : document.getPages()) {
//                addWatermarksToPage(document, page, image, userName, downloadDateText);
//            }
//            AccessPermission accessPermission = new AccessPermission();
//            accessPermission.setReadOnly();
//            accessPermission.setCanModify(false);
//            StandardProtectionPolicy spp = new StandardProtectionPolicy("1234","1234",accessPermission);
//            spp.setEncryptionKeyLength(128);
//            spp.setPermissions(accessPermission);
//            document.protect(spp);
//            PDAcroForm acroForm = new PDAcroForm(document);
//            document.getDocumentCatalog().setAcroForm(acroForm);
//            acroForm.setSignaturesExist(true);
//            acroForm.flatten();
//            //  document.save(outputPath);
//        } catch (IOException e) {
//            throw new RuntimeException("Error while adding watermark to PDF", e);
//        }
//    }
public static void addWatermark(PDDocument document, String userName, Locale locale, String imagePath, String outputPath, String uuid) {
    try {
        String downloadDateText = formatDate(locale);
        PDImageXObject image = PDImageXObject.createFromFile(imagePath, document);
        PDDocumentInformation pdd = document.getDocumentInformation();
        pdd.setAuthor(userName);
        pdd.setCustomMetadataValue("DocumentId", uuid);
        pdd.setCustomMetadataValue("DownloadDate", downloadDateText);

        // Add watermarks to each page
        for (PDPage page : document.getPages()) {
            addWatermarksToPage(document, page, image, userName, downloadDateText);
        }

        // Flatten annotations and fields for each page
        for (PDPage page : document.getPages()) {
            page.getAnnotations().clear(); // Remove all annotations
        }

        // Set permissions to read-only
        AccessPermission accessPermission = new AccessPermission();
        accessPermission.setReadOnly();
        StandardProtectionPolicy spp = new StandardProtectionPolicy("1234", "1234", accessPermission);
        spp.setEncryptionKeyLength(128);
        spp.setPermissions(accessPermission);
        document.protect(spp);

        // Flatten AcroForm (form fields)
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm != null) {
            acroForm.setSignaturesExist(true);
            acroForm.flatten();
        }

        // Save the flattened PDF
        document.save(outputPath);
    } catch (IOException e) {
        throw new RuntimeException("Error while adding watermark to PDF", e);
    }
}

    private static String formatDate(Locale locale) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", locale);
        return dateFormatter.format(new Date());
    }

    private static void addWatermarksToPage(PDDocument document, PDPage page, PDImageXObject image, String watermarkText, String downloadDateText) throws IOException {
        PDRectangle pageSize = page.getMediaBox();
        float width = pageSize.getWidth();
        float height = pageSize.getHeight();
        try (PDPageContentStream contentStream = new PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            addImageWatermark(contentStream, image, width, height);
            addTextWatermarks(contentStream, watermarkText, downloadDateText, width, height);
        }
    }

    private static void addImageWatermark(PDPageContentStream contentStream, PDImageXObject image, float width, float height) throws IOException {
        float xImageCenter = (width - IMAGE_WIDTH) / 2;
        float yImageCenter = (height - IMAGE_HEIGHT) / 2;

        contentStream.saveGraphicsState();
        PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
        graphicsState.setNonStrokingAlphaConstant(ALPHA);
        contentStream.setGraphicsStateParameters(graphicsState);
        contentStream.drawImage(image, xImageCenter, yImageCenter, IMAGE_WIDTH, IMAGE_HEIGHT);
        contentStream.restoreGraphicsState();
    }

    private static void addTextWatermarks(PDPageContentStream contentStream, String watermarkText, String downloadDateText, float width, float height) throws IOException {
        float[][] positions = {
                {width / 4, height * 3 / 4}, // Top-left
                {width * 3 / 4, height * 3 / 4}, // Top-right
                {width / 4, height / 2}, // Middle-left
                {width * 3 / 4, height / 2}, // Middle-right
                {width / 4, height / 4}, // Bottom-left
                {width * 3 / 4, height / 4} // Bottom-right
        };

        for (float[] position : positions) {
            drawText(contentStream, watermarkText, position[0], position[1], 45);
            drawText(contentStream, downloadDateText, position[0], position[1] - 20, 45);
        }
    }

    private static void drawText(PDPageContentStream contentStream, String text, float x, float y, float angle) throws IOException {
        contentStream.saveGraphicsState();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, FONT_SIZE);
        contentStream.setNonStrokingColor(180, 180, 180);
        contentStream.transform(Matrix.getRotateInstance(Math.toRadians(angle), x, y));
        contentStream.beginText();
        contentStream.newLineAtOffset(-30, 0);
        contentStream.showText(text);
        contentStream.endText();
        contentStream.restoreGraphicsState();
    }
}

