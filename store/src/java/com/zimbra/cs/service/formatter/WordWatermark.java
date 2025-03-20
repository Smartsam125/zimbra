package com.zimbra.cs.service.formatter;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class WordWatermark {

    public static void addMetadata(InputStream inputStream, OutputStream outputStream, String userName,String clientIP,String Guid) {
        try (XWPFDocument document = new XWPFDocument(OPCPackage.open(inputStream))) {

            // Add user information and date to document metadata
            addCustomMetadata(document, userName,clientIP,Guid);
            protectDocument(document, "DickHeadXXX");
            document.write(outputStream);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add metadata to the Word document.", e);
        }
    }
    private static void protectDocument(XWPFDocument document, String password) {
        try {
            document.enforceReadonlyProtection(password, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to protect the document.", e);
        }
    }
    private static void addCustomMetadata(XWPFDocument document, String userName,String clientIP,String Guid) {
        try {
            POIXMLProperties properties = document.getProperties();
            POIXMLProperties.CustomProperties customProperties = properties.getCustomProperties();

            // Add userName to metadata
            customProperties.addProperty("AuthorName", userName);

            // Add current date to metadata
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String currentDate = sdf.format(new Date());
            customProperties.addProperty("DateAdded", currentDate);
            customProperties.addProperty("ClientIP", clientIP);
            customProperties.addProperty("DocumentId", Guid);

        } catch (Exception e) {
            throw new RuntimeException("Error adding custom metadata.", e);
        }
    }
}
