package com.zimbra.cs.service;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.tika.Tika;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public class InterceptingInputStream extends FilterInputStream {
    private final String requestTime;

    public InterceptingInputStream(InputStream in) {
        super(in);
        //this.account = account;
      //  this.requesterName = account.getUCUsername();
        this.requestTime = getCurrentTimestamp();
    }
    private static String getCurrentTimestamp() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }


    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytesRead = super.read(b, off, len);
        if (bytesRead > 0) {
            intercept(new ByteArrayInputStream(Arrays.copyOfRange(b, off, off + bytesRead)));
        }
        return bytesRead;
    }

    private void intercept(InputStream inputStream) throws IOException {
        File tempFile = convertInputStreamToFile(inputStream);
        handleFileByType(tempFile, "Johny Sins", requestTime);
    }

    private File convertInputStreamToFile(InputStream inputStream) throws IOException {
        File tempFile = File.createTempFile("tempFile", ".tmp");
        tempFile.deleteOnExit(); // Ensure temporary file is deleted
        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    private void handleFileByType(File file, String requesterName, String requestTime) throws IOException {
        Tika tika = new Tika();
        String fileType = tika.detect(file);
        InputStream processedInputStream;

        switch (fileType) {
            case "text/csv":
                processedInputStream = addMetadataToCSV(new FileInputStream(file), requesterName, requestTime);
                break;
            case "application/pdf":
                processedInputStream = addMetadataToPDF(file, requesterName, requestTime);
                break;
            case "application/xml":
                processedInputStream = addMetadataToXML(new FileInputStream(file), requesterName, requestTime);
                break;
            default:
                return;
        }

        // Overwrite the original file with the processed content
        try (OutputStream outputStream = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = processedInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    private InputStream addMetadataToCSV(InputStream inputStream, String requesterName, String requestTime) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();

        // Add metadata at the top
        sb.append("Requested By,Request Time\n");
        sb.append(requesterName).append(",").append(requestTime).append("\n");

        // Append original content
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }

        reader.close();
        return new ByteArrayInputStream(sb.toString().getBytes());
    }

    private InputStream addMetadataToPDF(File inputFile, String requesterName, String requestTime) throws IOException {
        PDDocument document = null;

        try {
            // Load the PDF document
            document = PDDocument.load(inputFile);

            // Iterate through all pages and add metadata at the footer
            for (PDPage page : document.getPages()) {
                PDPageContentStream contentStream = new PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                );
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10);
                float margin = 40; // Left margin for the footer
                float footerHeight = 20; // Bottom margin for footer height

                // Start writing text at the bottom of the page
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, footerHeight);
                contentStream.showText("Requested By: " + requesterName + " | Request Time: " + requestTime);
                contentStream.endText();

                contentStream.close();
            }

            // Save the updated PDF to a byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            document.close();

            // Return the updated PDF as an InputStream
            return new ByteArrayInputStream(outputStream.toByteArray());

        } catch (Exception e) {
            if (document != null) {
                document.close();
            }
            throw new IOException("Error processing PDF", e);
        }
    }

    private InputStream addMetadataToXML(InputStream inputStream, String requesterName, String requestTime) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);

            // Add metadata to the root element
            Element root = doc.getDocumentElement();
            Element metadata = doc.createElement("Metadata");
            Element userElement = doc.createElement("RequestedBy");
            userElement.appendChild(doc.createTextNode(requesterName));
            Element timeElement = doc.createElement("RequestTime");
            timeElement.appendChild(doc.createTextNode(requestTime));

            metadata.appendChild(userElement);
            metadata.appendChild(timeElement);
            root.appendChild(metadata);

            // Write the updated document to an output stream
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            StreamResult result = new StreamResult(outputStream);
            transformer.transform(source, result);

            return new ByteArrayInputStream(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IOException("Error processing XML file", e);
        }
    }
}