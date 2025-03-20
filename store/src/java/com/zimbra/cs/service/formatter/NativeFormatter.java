/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007, 2008, 2009, 2010, 2011, 2012, 2013, 2014, 2015, 2016 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.service.formatter;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimePart;
import javax.mail.internet.MimeUtility;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.zimbra.client.ZMailbox;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.mime.MimeConstants;
import com.zimbra.common.mime.MimeDetect;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.common.util.HttpUtil;
import com.zimbra.common.util.ImageUtil;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.LogFactory;
import com.zimbra.common.util.StringUtil;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.convert.ConversionUnsupportedException;
import com.zimbra.cs.extension.ExtensionUtil;
import com.zimbra.cs.html.BrowserDefang;
import com.zimbra.cs.html.DefangFactory;
import com.zimbra.cs.mailbox.CalendarItem;
import com.zimbra.cs.mailbox.Contact;
import com.zimbra.cs.mailbox.DeliveryOptions;
import com.zimbra.cs.mailbox.Document;
import com.zimbra.cs.mailbox.Folder;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.MailItem.CustomMetadata;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.MailServiceException.NoSuchItemException;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.mime.MPartInfo;
import com.zimbra.cs.mime.Mime;
import com.zimbra.cs.mime.ParsedDocument;
import com.zimbra.cs.mime.ParsedMessage;
import com.zimbra.cs.service.UserServlet;
import com.zimbra.cs.service.UserServletContext;
import com.zimbra.cs.service.UserServletException;
import com.zimbra.cs.service.formatter.FormatterFactory.FormatType;
import com.zimbra.cs.service.mail.UploadScanner;
import com.zimbra.cs.servlet.ETagHeaderFilter;
import com.zimbra.cs.store.Blob;
import com.zimbra.cs.store.StoreManager;
import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.client.HttpClients;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.tika.Tika;
import org.json.JSONObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBackground;

public final class NativeFormatter extends Formatter {

    private static final String CONVERSION_PATH = "/extension/convertd";
    public static final String ATTR_INPUTSTREAM = "inputstream";
    public static final String ATTR_MSGDIGEST  = "msgdigest";
    public static final String ATTR_FILENAME  = "filename";
    public static final String ATTR_CONTENTURL = "contenturl";
    public static final String ATTR_CONTENTTYPE = "contenttype";
    public static final String ATTR_CONTENTLENGTH = "contentlength";
    public static final String ATTR_LOCALE  = "locale";
    public static final String RETURN_CODE_NO_RESIZE = "NO_RESIZE";
    private ZMailbox mMailbox;


    private static final Log log = LogFactory.getLog(NativeFormatter.class);

    private static final Set<String> SCRIPTABLE_CONTENT_TYPES = ImmutableSet.of(MimeConstants.CT_TEXT_HTML,
                                                                                MimeConstants.CT_APPLICATION_XHTML,
                                                                                MimeConstants.CT_TEXT_XML,
                                                                                MimeConstants.CT_APPLICATION_ZIMBRA_DOC,
                                                                                MimeConstants.CT_APPLICATION_ZIMBRA_SLIDES,
                                                                                MimeConstants.CT_APPLICATION_ZIMBRA_SPREADSHEET,
                                                                                MimeConstants.CT_IMAGE_SVG,
                                                                                MimeConstants.CT_TEXT_XML_LEGACY);

    @Override
    public FormatType getType() {
        return FormatType.HTML_CONVERTED;
    }

    @Override
    public Set<MailItem.Type> getDefaultSearchTypes() {
        // TODO: all?
        return EnumSet.of(MailItem.Type.MESSAGE);
    }

    @Override
    public void formatCallback(UserServletContext context) throws IOException, ServiceException, UserServletException, ServletException {
        try {
            sendZimbraHeaders(context, context.resp, context.target);
            HttpUtil.Browser browser = HttpUtil.guessBrowser(context.req);
            if (browser == HttpUtil.Browser.IE) {
                context.resp.addHeader("X-Content-Type-Options", "nosniff"); // turn off content detection..
            }
            if (context.target instanceof Message) {
                handleMessage(context, (Message) context.target);
            } else if (context.target instanceof CalendarItem) {
                // Don't return private appointments/tasks if the requester is not the mailbox owner.
                CalendarItem calItem = (CalendarItem) context.target;
                if (calItem.isPublic() || calItem.allowPrivateAccess(
                        context.getAuthAccount(), context.isUsingAdminPrivileges())) {
                    handleCalendarItem(context, calItem);
                } else {
                    context.resp.sendError(HttpServletResponse.SC_FORBIDDEN, "permission denied");
                }
            } else if (context.target instanceof Document) {
                handleDocument(context, (Document) context.target);
            } else if (context.target instanceof Contact) {
                handleContact(context, (Contact) context.target);
            } else {
                throw UserServletException.notImplemented("can only handle messages/appointments/tasks/documents");
            }
        } catch (MessagingException me) {
            throw ServiceException.FAILURE(me.getMessage(), me);
        }
    }

    private void handleMessage(UserServletContext context, Message msg) throws IOException, ServiceException, MessagingException, ServletException {
        if (context.hasBody()) {
            List<MPartInfo> parts = Mime.getParts(msg.getMimeMessage());
            MPartInfo body = Mime.getTextBody(parts, false);
            if (body != null) {
                handleMessagePart(context, body.getMimePart(), msg);
            } else {
                context.resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "body not found");
            }
        } else if (context.hasPart()) {
            MimePart mp = getMimePart(msg, context.getPart());
            handleMessagePart(context, mp, msg);
        } else {
            context.resp.setContentType(MimeConstants.CT_TEXT_PLAIN);
            long size = msg.getSize();
            if (size > 0)
                context.resp.setContentLength((int)size);
            InputStream is = msg.getContentStream();
            ByteUtil.copy(is, true, context.resp.getOutputStream(), false);
        }
    }

    private void handleCalendarItem(UserServletContext context, CalendarItem calItem) throws IOException, ServiceException, MessagingException, ServletException {
        if (context.hasPart()) {
            MimePart mp;
            if (context.itemId.hasSubpart()) {
                MimeMessage mbp = calItem.getSubpartMessage(context.itemId.getSubpartId());
                mp = Mime.getMimePart(mbp, context.getPart());
            } else {
                mp = getMimePart(calItem, context.getPart());
            }
            handleMessagePart(context, mp, calItem);
        } else {
            context.resp.setContentType(MimeConstants.CT_TEXT_PLAIN);
            InputStream is = calItem.getRawMessage();
            if (is != null)
                ByteUtil.copy(is, true, context.resp.getOutputStream(), false);
        }
    }

    private void handleContact(UserServletContext context, Contact con) throws IOException, ServiceException, MessagingException, ServletException {
        if (!con.hasAttachment()) {
            context.resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "body not found");
        } else if (context.hasPart()) {
            MimePart mp = Mime.getMimePart(con.getMimeMessage(false), context.getPart());
            handleMessagePart(context, mp, con);
        } else {
            context.resp.setContentType(MimeConstants.CT_TEXT_PLAIN);
            InputStream is = new ByteArrayInputStream(con.getContent());
            ByteUtil.copy(is, true, context.resp.getOutputStream(), false);
        }
    }

    private static final String HTML_VIEW = "html";
    private static final String TEXT_VIEW = "text";

    private void handleMessagePart(UserServletContext context, MimePart mp, MailItem item) throws IOException, MessagingException, ServletException {
        if (mp == null) {
            context.resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "part not found");
        } else {
            String contentType = mp.getContentType();
            String shortContentType = Mime.getContentType(mp);

            if (contentType == null) {
                contentType = MimeConstants.CT_TEXT_PLAIN;
            } else if (shortContentType.equalsIgnoreCase(MimeConstants.CT_APPLICATION_OCTET_STREAM)) {
                if ((contentType = MimeDetect.getMimeDetect().detect(Mime.getFilename(mp), mp.getInputStream())) == null)
                    contentType = MimeConstants.CT_APPLICATION_OCTET_STREAM;
                else
                    shortContentType = contentType;
            }
            // CR or LF in Content-Type causes Chrome to barf, unfortunately
            contentType = contentType.replace('\r', ' ').replace('\n', ' ');

            // IE displays garbage if the content-type header is too long
            HttpUtil.Browser browser = HttpUtil.guessBrowser(context.req);
            if (browser == HttpUtil.Browser.IE && contentType.length() > 80)
                contentType = shortContentType;

            // useful for show original of message attachment
            boolean simpleText = (context.hasView() && context.getView().equals(TEXT_VIEW) &&
                    MimeConstants.CT_MESSAGE_RFC822.equals(contentType));
            if (simpleText) {
                contentType = MimeConstants.CT_TEXT_PLAIN;
            }
            boolean html = checkGlobalOverride(Provisioning.A_zimbraAttachmentsViewInHtmlOnly,
                    context.getAuthAccount()) || (context.hasView() && context.getView().equals(HTML_VIEW));
            InputStream in = null;
            try {
                if (!html || ExtensionUtil.getExtension("convertd") == null ||
                        contentType.startsWith(MimeConstants.CT_TEXT_HTML) || contentType.matches(MimeConstants.CT_IMAGE_WILD)) {
                    byte[] data = null;

                    // If this is an image that exceeds the max size, resize it.  Don't resize
                    // gigantic images because ImageIO reads image content into memory.
                    if ((context.hasMaxWidth() || context.hasMaxHeight()) &&
                        (Mime.getSize(mp) < LC.max_image_size_to_resize.intValue())) {
                        try {
                            data = getResizedImageData(mp.getInputStream(), Mime.getContentType(mp),
                                mp.getFileName(), context.getMaxWidth(), context.getMaxHeight());
                        } catch (Exception e) {
                            log.info("Unable to resize image.  Returning original content.", e);
                        }
                    }

                    // Return the data, or resized image if available.
                    long size;
                    String returnCode = null;
                    if (data != null) {
                        returnCode = new String(Arrays.copyOfRange(data, 0,
                            NativeFormatter.RETURN_CODE_NO_RESIZE.length()), "UTF-8");
                    }
                    if (data != null && !NativeFormatter.RETURN_CODE_NO_RESIZE.equals(returnCode)) {
                        in = new ByteArrayInputStream(data);
                        size = data.length;
                    } else {
                        in = mp.getInputStream();
                        String enc = mp.getEncoding();
                        if (enc != null) {
                            enc = enc.toLowerCase();
                        }
                        size = enc == null || enc.equals("7bit") || enc.equals("8bit") || enc.equals("binary") ? mp.getSize() : 0;
                    }
                    String defaultCharset = context.targetAccount.getAttr(Provisioning.A_zimbraPrefMailDefaultCharset, null);
                    if (simpleText) {
                        sendbackBinaryData(context.req, context.resp, in, contentType, Part.INLINE,
                                null /* filename */, size, true);
                    } else {
                        String filename= Mime.getFilename(mp);
                        String sender = item.getSender();
                      //  int attachmentId = item.getId();
                      //  filename="JonhnySins";
                        sendbackOriginalDoc(in, contentType, defaultCharset,filename, mp.getDescription(),
                                size, context.req, context.resp,context,sender,item.getId());
                    }
                } else {
                    in = mp.getInputStream();
                    handleConversion(context, in, Mime.getFilename(mp), contentType, item.getDigest(), mp.getSize());
                }
            } finally {
                ByteUtil.closeStream(in);
            }
        }
    }

    /**
     * If the image stored in the {@code MimePart} exceeds the given width,
     * shrinks the image and returns the shrunk data.  If the
     * image width is smaller than {@code maxWidth} or resizing is not supported,
     * returns {@code null}.
     */
    public static byte[] getResizedImageData(InputStream in, String contentType, String fileName, Integer maxWidth, Integer maxHeight)
    throws IOException {
        ImageReader reader = null;
        ImageWriter writer = null;
       
        if (maxWidth == null)
            maxWidth = LC.max_image_size_to_resize.intValue();

        if (maxHeight == null)
            maxHeight = LC.max_image_size_to_resize.intValue();

        try {
            // Get ImageReader for stream content.
            reader = ImageUtil.getImageReader(contentType, fileName);
            if (reader == null) {
                log.debug("No ImageReader available.");
                return null;
            }

            // Read message content.
            reader.setInput(new MemoryCacheImageInputStream(in));
            BufferedImage img = reader.read(0);
            int width = img.getWidth(), height = img.getHeight();

            if (width <= maxWidth && height <= maxHeight) {
                log.debug("Image %dx%d is less than max %dx%d.  Not resizing.",
                          width, height, maxWidth, maxHeight);
                return RETURN_CODE_NO_RESIZE.getBytes();
            }

            // Resize.
            writer = ImageIO.getImageWriter(reader);
            if (writer == null) {
                log.debug("No ImageWriter available.");
                return null;
            }

            double ratio =
                Math.min((double) maxWidth / width,
                         (double) maxHeight / height);

            width *= ratio;
            height *= ratio;

            BufferedImage small = ImageUtil.resize(img, width, height);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(small);
            return out.toByteArray();
        } finally {
            ByteUtil.closeStream(in);
            if (reader != null) {
                reader.dispose();
            }
            if (writer != null) {
                writer.dispose();
            }
        }


    }

    private void handleDocument(UserServletContext context, Document doc) throws IOException, ServiceException, ServletException {
        String v = context.params.get(UserServlet.QP_VERSION);
        int version = v != null ? Integer.parseInt(v) : -1;
        String contentType = doc.getContentType();

        doc = (version > 0 ? (Document)doc.getMailbox().getItemRevision(context.opContext, doc.getId(), doc.getType(), version) : doc);
        InputStream is = doc.getContentStream();
        CustomMetadata customData = doc.getCustomData("Profile");
        if (customData != null && customData.containsKey("p") && customData.get("p").equals("1")) {
            try {
                if ((context.hasMaxWidth() || context.hasMaxHeight())
                    && (doc.getSize() < LC.max_image_size_to_resize.intValue())) {
                    byte[] data = getResizedImageData(is, doc.getContentType(), doc.getName(),
                        context.getMaxWidth(), context.getMaxHeight());
                    String returnCode = null;
                    if (data != null) {
                        returnCode = new String(Arrays.copyOfRange(data, 0,
                            NativeFormatter.RETURN_CODE_NO_RESIZE.length()), "UTF-8");
                    }
                    if (data != null && !NativeFormatter.RETURN_CODE_NO_RESIZE.equals(returnCode)) {
                            InputStream profileInputStream = new ByteArrayInputStream(data);
                            long size = data.length;
                            sendbackBinaryData(context.req, context.resp, profileInputStream,
                                contentType, null, doc.getName(), size);
                            return;
                    }
                }
            } catch (Exception e) {
                log.info("Unable to resize image.  Returning original content.", e);
            }
        }
        if (HTML_VIEW.equals(context.getView()) && !(contentType != null && contentType.startsWith(MimeConstants.CT_TEXT_HTML))) {
            if (ExtensionUtil.getExtension("convertd") != null) {
                // If the requested view is html, but the requested content is not, use convertd extension when deployed
                handleConversion(context, is, doc.getName(), doc.getContentType(), doc.getDigest(), doc.getSize());
            } else {
                // If the requested view is html, but the content is not, respond with a conversion error, so that
                // either an error page, or page invoking an error callback handler can be shown
                try {
                    updateClient(context, new ConversionUnsupportedException(String.format("Native format cannot be displayed inline: %s", contentType)));
                } catch (UserServletException e) {
                    throw new ServletException(e.getLocalizedMessage(), e);
                }
            }
        } else {
            String defaultCharset = context.targetAccount.getAttr(Provisioning.A_zimbraPrefMailDefaultCharset, null);
            boolean neuter = doc.getAccount().getBooleanAttr(Provisioning.A_zimbraNotebookSanitizeHtml, true);
            String sender = doc.getSender();

            if (neuter)
                sendbackOriginalDoc(is, contentType, defaultCharset, doc.getName(), null, doc.getSize(), context.req, context.resp,context,sender,doc.getId());
            else
                sendbackBinaryData(context.req, context.resp, is, contentType, null , doc.getName(), doc.getSize());
        }
    }
    private void handleConversion(UserServletContext ctxt, InputStream is, String filename, String ct, String digest, long length) throws IOException, ServletException {
        try {
            ctxt.req.setAttribute(ATTR_INPUTSTREAM, is);
            ctxt.req.setAttribute(ATTR_MSGDIGEST, digest);
            ctxt.req.setAttribute(ATTR_FILENAME, filename);
            ctxt.req.setAttribute(ATTR_CONTENTTYPE, ct);
            ctxt.req.setAttribute(ATTR_CONTENTURL, ctxt.req.getRequestURI());
            ctxt.req.setAttribute(ATTR_CONTENTLENGTH, length);
            Account authAcct = ctxt.getAuthAccount();
            if (null != authAcct) {
                String locale = authAcct.getPrefLocale();
                if (locale != null) {
                    ctxt.req.setAttribute(ATTR_LOCALE, locale);
                }
            }
            RequestDispatcher dispatcher = ctxt.req.getRequestDispatcher(CONVERSION_PATH);
            dispatcher.forward(ctxt.req, ctxt.resp);
        } finally {
            ByteUtil.closeStream(is);
        }
    }

    public static MimePart getMimePart(CalendarItem calItem, String part) throws IOException, MessagingException, ServiceException
    {
        return Mime.getMimePart(calItem.getMimeMessage(), part);
    }

    public static MimePart getMimePart(Message msg, String part) throws IOException, MessagingException, ServiceException {
        return Mime.getMimePart(msg.getMimeMessage(), part);
    }
//    public static void sendbackOriginalDoc(InputStream is, String contentType, String defaultCharset, String filename,
//            String desc, HttpServletRequest req, HttpServletResponse resp,UserServletContext context) throws IOException {
//        sendbackOriginalDoc(is, contentType, defaultCharset, filename, desc, 0, req, resp,context);
//    }
    private static void addMetadataToExcel(InputStream inputStream, OutputStream outputStream, String userName, String clientIP, String Guid) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            // Add custom metadata
            POIXMLProperties properties = workbook.getProperties();
            POIXMLProperties.CustomProperties customProperties = properties.getCustomProperties();

            customProperties.addProperty("AuthorName", userName);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String currentDate = sdf.format(new Date());
            customProperties.addProperty("DateAdded", currentDate);
            customProperties.addProperty("ClientIP", clientIP);
            customProperties.addProperty("DocumentId", Guid);

            workbook.write(outputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to add metadata to Excel file.", e);
        }
    }
    private static void lockDocument(PDDocument document, String ownerPassword) throws IOException {
        AccessPermission accessPermission = new AccessPermission();
        accessPermission.setCanModify(false);
        accessPermission.setCanFillInForm(false);
        accessPermission.setCanExtractContent(false);
        StandardProtectionPolicy protectionPolicy = new StandardProtectionPolicy(ownerPassword, null, accessPermission);
        protectionPolicy.setEncryptionKeyLength(128);
        protectionPolicy.setPermissions(accessPermission);
        document.protect(protectionPolicy);
    }
    private String getFileExtension(String filePath) {
        return filePath.substring(filePath.lastIndexOf(".") + 1);
    }
    private void writeFormData(DataOutputStream outputStream, String boundary, String filePath) throws IOException {
        // Write the data field for the file
        String fileName = new File(filePath).getName();
        outputStream.writeBytes("--" + boundary + "\r\n");
        outputStream.writeBytes("Content-Disposition: form-data; name=\"data\"; filename=\"" + fileName + "\"\r\n");
        outputStream.writeBytes("Content-Type: " + "application/vnd.openxmlformats-officedocument.wordprocessingml.document" + "\r\n");
        outputStream.writeBytes("\r\n");

        // Write the actual file content
        try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        outputStream.writeBytes("\r\n");
    }
    private void saveConvertedFile(InputStream is) throws IOException {
        // Define the path where the converted PDF will be saved
        String outputFilePath = "converted_file.pdf";
        try (FileOutputStream fileOutputStream = new FileOutputStream(outputFilePath)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, bytesRead);
            }
            System.out.println("Converted PDF saved to: " + outputFilePath);
        }
    }

    private static void sendbackOriginalDoc(InputStream is, String contentType, String defaultCharset, String filename,
            String desc, long size, HttpServletRequest req, HttpServletResponse resp,UserServletContext context,String sender,int docId) throws IOException {
        String disp = req.getParameter(UserServlet.QP_DISP);
        //String user = req.getParameter(UserServlet.)
        disp = (disp == null || disp.toLowerCase().startsWith("i")) ? Part.INLINE : Part.ATTACHMENT;
        if (desc != null && desc.length() <= 2048) { // do not return ridiculously long header.
            if (desc.contains(" ") && !(desc.startsWith("\"") && desc.endsWith("\""))) {
                desc = "\"" + desc.trim() +"\"";
            }
            resp.addHeader("Content-Description", desc);
        }
        // defang when the html and svg attachment was requested with disposition inline
        if (disp.equals(Part.INLINE) && isScriptableContent(contentType)) {
            BrowserDefang defanger = DefangFactory.getDefanger(contentType);
            String content = defanger.defang(Mime.getTextReader(is, contentType, defaultCharset), true);
            resp.setContentType(contentType);
            String charset = Mime.getCharset(contentType);
            resp.setCharacterEncoding(Strings.isNullOrEmpty(charset) ? Charsets.UTF_8.name() : charset);
            if (!content.isEmpty()) {
                resp.setContentLength(content.getBytes().length);
            }
            resp.getWriter().write(content);
        } else {
            if (contentType.startsWith(MimeConstants.CT_APPLICATION_SHOCKWAVE_FLASH)) {
                disp = Part.ATTACHMENT;
            }
            resp.setContentType(contentType);
            String extension= FilenameUtils.getExtension(filename);
            String clientName = context.getAuthAccount() == null ? null : context.getAuthAccount().getName();
             //String userAuthToken= req.getParameter(UserServlet.AUTH_JWT);
            String clientIp = IpAddressUtil.getClientIp(req);
            String userName=clientName+"-"+clientIp;
           // String username=clientName;
            //CallbackService.sendCallback(docId, filename, userName, clientIp, endpoint);
            //send a call back to  a post request containing the docId, filename, user, clientIp
            String endpoint="https://play.gdexperts.com:9003/api/downloads/callback";
            String documentId=UUID.randomUUID().toString();
            try {
                // Format download date
                log.info("Sending callback to: " + endpoint);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                String downloadDate = sdf.format(new Date());
                // Create JSON payload
                JSONObject payload = new JSONObject();
                payload.put("email", userName);
                payload.put("document_name", filename);
                payload.put("ip_address", clientIp);
                payload.put("download_date", downloadDate);
                payload.put("doc_id", documentId);
                payload.put("sender", sender);
                payload.put("attachment_id", docId);
                URL url = new URL(endpoint);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.toString().getBytes());
                    os.flush();
                }

                // Get response code (optional)
                int responseCode = connection.getResponseCode();
                log.info("Callback response code: " + responseCode);

                connection.disconnect();
            } catch (Exception e)
            {
                e.printStackTrace();
                log.info("Error sending HTTP request: " + e);
            }
            if ("pdf".equalsIgnoreCase(extension) )
            {
                PDDocument document = null;
                try {
                        document = PDDocument.load(is);
                    if (document != null) {
                        log.info("Processing PDF file...");
                        ByteArrayOutputStream modifiedOutput = new ByteArrayOutputStream();
                        // Add watermark and lock the document
                        Locale locale = req.getLocale();
                        String imagePath = "/opt/zimbra/jetty_base/webapps/zimbraAdmin/img/logo/ugandapolice.png";
                        String outputPath = "output.pdf";
                        PDFWatermarkUtility.addWatermark(document, userName, locale, imagePath, outputPath,documentId);
                       // lockDocument(document, "DickHeadXXX");
                        document.save(modifiedOutput);
                        InputStream modifiedInputStream = new ByteArrayInputStream(modifiedOutput.toByteArray());
                        sendbackBinaryData(req, resp, modifiedInputStream, contentType, disp, filename, modifiedOutput.size());
                    }
                } catch (Exception e)
                {
                    log.error("Error processing document", e);
                    resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Document processing failed.");
                } finally {
                    if (document != null) {
                        try {
                            document.close();
                        } catch (IOException e) {
                            log.warn("Failed to close document", e);
                        }
                    }
                }
            }
          else if ("docx".equalsIgnoreCase(extension)) {
                PDDocument document;
                log.info("Converting DOCX to PDF...");
                HttpClient client = HttpClients.createDefault();
                HttpPost postRequest = new HttpPost("http://localhost:8082/lool/convert-to?format=pdf");
                MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
                entityBuilder.addPart("data", new InputStreamBody(is,"application/octet-stream",filename));
                HttpEntity entity = entityBuilder.build();
                postRequest.setHeader("accept", "application/octet-stream");
                postRequest.setEntity(entity);
                CloseableHttpResponse response = (CloseableHttpResponse) client.execute(postRequest);
                InputStream pdfInputStream = null;
                if(response.getStatusLine().getStatusCode() == 200)
                {
                    pdfInputStream = response.getEntity().getContent();

                }
                if(pdfInputStream!=null)
                {
                   log.info("Processing PDF file after conversion...");
                    document = PDDocument.load(pdfInputStream);
                    ByteArrayOutputStream modifiedOutput = new ByteArrayOutputStream();
                    // Add watermark and lock the document
                    Locale locale = req.getLocale();
                    String imagePath = "/opt/zimbra/jetty_base/webapps/zimbraAdmin/img/logo/ugandapolice.png";
                    String outputPath = "output.pdf";
                    PDFWatermarkUtility.addWatermark(document, userName, locale, imagePath, outputPath,documentId);
                   // lockDocument(document, "DickHeadXXX");
                    document.save(modifiedOutput);
                    InputStream modifiedInputStream = new ByteArrayInputStream(modifiedOutput.toByteArray());
                    sendbackBinaryData(req, resp, modifiedInputStream,"application/pdf",disp,filename,modifiedOutput.size());
                }
            }
            else if ("xlsx".equalsIgnoreCase(extension)) {
                log.info("Processing Excel document...");
                ByteArrayOutputStream modifiedOutput = new ByteArrayOutputStream();
                try {
                    String watermarkText = userName;
                    addMetadataToExcel(is, modifiedOutput, watermarkText,clientIp,documentId);
                } catch (Exception e) {
                    log.error("Error while adding metadata to Excel", e);
                    throw new RuntimeException("Failed to process Excel document.", e);
                }
                InputStream modifiedInputStream = new ByteArrayInputStream(modifiedOutput.toByteArray());
                sendbackBinaryData(req, resp, modifiedInputStream, contentType, disp, filename, modifiedOutput.size());
            }
            else if ("csv".equalsIgnoreCase(extension)) {
                log.info("Processing CSV file...");
                ByteArrayOutputStream modifiedOutput = new ByteArrayOutputStream();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(modifiedOutput))) {
                    Locale locale = req.getLocale();
                    SimpleDateFormat dateFormatter = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", locale);
                    String downloadDateText = dateFormatter.format(new Date());
                    String watermarkText = userName;
                    // Append metadata as comments at the start of the CSV file
                    writer.println("# User: " + watermarkText);
                    writer.println("# Downloaded: " + downloadDateText);

                    // Write original content
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.println(line);
                    }
                } catch (IOException e) {
                    log.error("Error while adding metadata to CSV", e);
                    throw new RuntimeException("Failed to process CSV file.", e);
                }

                InputStream modifiedInputStream = new ByteArrayInputStream(modifiedOutput.toByteArray());
                sendbackBinaryData(req, resp, modifiedInputStream, contentType, disp, filename, modifiedOutput.size());
            }
            else {
                sendbackBinaryData(req, resp, is, contentType, disp, filename, size);
            }

        }
    }

    @Override
    public boolean supportsSave() {
        return true;
    }

    @Override
    public void saveCallback(UserServletContext context, String contentType, Folder folder, String filename)
            throws IOException, ServiceException, UserServletException
    {
        if (filename == null) {
            Mailbox mbox = folder.getMailbox();
            try {
                ParsedMessage pm = new ParsedMessage(context.getPostBody(), mbox.attachmentsIndexingEnabled());
                DeliveryOptions dopt = new DeliveryOptions().setFolderId(folder).setNoICal(true);
                mbox.addMessage(context.opContext, pm, dopt, null);
                return;
            } catch (ServiceException e) {
                throw new UserServletException(HttpServletResponse.SC_BAD_REQUEST, "error parsing message");
            }
        }

        InputStream is = context.getRequestInputStream();
        try {
            Blob blob = StoreManager.getInstance().storeIncoming(is);
            saveDocument(blob, context, contentType, folder, filename, is);
        } finally {
            is.close();
        }
    }

    private void saveDocument(Blob blob, UserServletContext context, String contentType, Folder folder, String filename, InputStream is)
        throws IOException, ServiceException, UserServletException
    {
        Mailbox mbox = folder.getMailbox();
        MailItem item = null;

        String creator = context.getAuthAccount() == null ? null : context.getAuthAccount().getName();
        ParsedDocument pd = null;

        try {
            if (contentType == null) {
                contentType = MimeDetect.getMimeDetect().detect(filename);
                if (contentType == null)
                    contentType = MimeConstants.CT_APPLICATION_OCTET_STREAM;
            }

            pd = new ParsedDocument(blob, filename, contentType, System.currentTimeMillis(), creator,
                    context.req.getHeader("X-Zimbra-Description"), true);

            item = mbox.getItemByPath(context.opContext, filename, folder.getId());
            // XXX: should we just overwrite here instead?
            if (!(item instanceof Document))
                throw new UserServletException(HttpServletResponse.SC_BAD_REQUEST, "cannot overwrite existing object at that path");

            // scan upload for viruses
            StringBuffer info = new StringBuffer();
            UploadScanner.Result result = UploadScanner.acceptStream(is, info);
            if (result == UploadScanner.REJECT)
                throw MailServiceException.UPLOAD_REJECTED(filename, info.toString());
            if (result == UploadScanner.ERROR)
                throw MailServiceException.SCAN_ERROR(filename);

            item = mbox.addDocumentRevision(context.opContext, item.getId(), pd);
        } catch (NoSuchItemException nsie) {
            item = mbox.createDocument(context.opContext, folder.getId(), pd, MailItem.Type.DOCUMENT, 0);
        }

        sendZimbraHeaders(context, context.resp, item);
    }
    private static long getContentLength(HttpServletRequest req)
    {
        // note HttpServletRequest.getContentLength() returns int, that's
        // why we parse it ourselves
        final String contentLengthStr = req.getHeader("Content-Length");
        return contentLengthStr != null ? Long.parseLong(contentLengthStr) : -1;
    }

    public static void sendZimbraHeaders(UserServletContext context, HttpServletResponse resp, MailItem item) {
        if (resp == null || item == null)
            return;

        if (context.wantCustomHeaders) {
            resp.addHeader("X-Zimbra-ItemId", item.getId() + "");
            resp.addHeader("X-Zimbra-Version", item.getVersion() + "");
            resp.addHeader("X-Zimbra-Modified", item.getChangeDate() + "");
            resp.addHeader("X-Zimbra-Change", item.getModifiedSequence() + "");
            resp.addHeader("X-Zimbra-Revision", item.getSavedSequence() + "");
            resp.addHeader("X-Zimbra-ItemType", item.getType().toString());
            try {
                String val = item.getName();
                if (!StringUtil.isAsciiString(val)) {
                    val = MimeUtility.encodeText(val, "utf-8", "B");
                }
                resp.addHeader("X-Zimbra-ItemName", val);
                val = item.getPath();
                if (!StringUtil.isAsciiString(val)) {
                    val = MimeUtility.encodeText(val, "utf-8", "B");
                }
                resp.addHeader("X-Zimbra-ItemPath", val);
            } catch (UnsupportedEncodingException e1) {
            } catch (ServiceException e) {
            }
        }
        // set Last-Modified header to date when item's content was last modified
        resp.addDateHeader("Last-Modified", item.getDate());
        // set ETag header to item's mod_content value
        resp.addHeader("ETag", String.valueOf(item.getSavedSequence()));
        resp.addHeader(ETagHeaderFilter.ZIMBRA_ETAG_HEADER, String.valueOf(item.getSavedSequence()));
    }

    private static final int READ_AHEAD_BUFFER_SIZE = 256;
    private static final byte[][] SCRIPT_PATTERN = {
        { '<', 's', 'c', 'r', 'i', 'p', 't' },
        { '<', 'S', 'C', 'R', 'I', 'P', 'T' }
    };

    public static void sendbackBinaryData(HttpServletRequest req, HttpServletResponse resp, InputStream in,
                                          String contentType, String disposition, String filename, long size)
    throws IOException {
        sendbackBinaryData(req, resp, in, contentType, disposition, filename, size, false);
    }

    public static void sendbackBinaryData(HttpServletRequest req, HttpServletResponse resp, InputStream in,
                                          String contentType, String disposition, String filename,
                                          long size, boolean ignoreContentDisposition)
    throws IOException {
       // InputStream modifiedInputStream=null;
        resp.setContentType(contentType);
        if (disposition == null) {
            String disp = req.getParameter(UserServlet.QP_DISP);
            disposition = (disp == null || disp.toLowerCase().startsWith("i") ) ? Part.INLINE : Part.ATTACHMENT;
        }
//        if(disposition.equals(Part.ATTACHMENT)){
//            if(contentType.equals("application/pdf")){
//                //implement content deposition
//                log.info("Processing PDF file...");
//                ByteArrayOutputStream modifiedOutput = new ByteArrayOutputStream();
//                try (PDDocument document = PDDocument.load(in)) {
//                    Locale locale = req.getLocale();
//                    SimpleDateFormat dateFormatter = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", locale);
//                    String downloadDateText = "Downloaded on: " + dateFormatter.format(new Date());
//                    for (PDPage page : document.getPages()) {
//                        PDRectangle pageSize = page.getMediaBox();
//                        float x = pageSize.getLowerLeftX() + 20;
//                        float y = pageSize.getLowerLeftY() + 20;
//                        try (PDPageContentStream contentStream = new PDPageContentStream(
//                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
//                            contentStream.beginText();
//                            contentStream.setFont(PDType1Font.HELVETICA, 10);
//                            contentStream.newLineAtOffset(x, y);
//                            contentStream.showText(downloadDateText);
//                            contentStream.endText();
//                        }
//                    }
//                    document.save(modifiedOutput);
//                } catch (IOException e) {
//                    log.error("Error while adding metadata to PDF", e);
//                }
//                modifiedInputStream = new ByteArrayInputStream(modifiedOutput.toByteArray());
//            }
//        }
        PushbackInputStream pis = new PushbackInputStream(in, READ_AHEAD_BUFFER_SIZE);
        boolean isSafe = false;
        HttpUtil.Browser browser = HttpUtil.guessBrowser(req);
        if (browser != HttpUtil.Browser.IE) {
            isSafe = true;
        } else if (disposition.equals(Part.ATTACHMENT)) {
            isSafe = true;
            if (isScriptableContent(contentType)) {
                resp.addHeader("X-Download-Options", "noopen"); // ask it to save the file
            }
        }
        if (!isSafe) {
            byte[] buf = new byte[READ_AHEAD_BUFFER_SIZE];
            int bytesRead = pis.read(buf, 0, READ_AHEAD_BUFFER_SIZE);
            boolean hasScript;
            for (int i = 0; i < bytesRead; i++) {
                if (buf[i] == SCRIPT_PATTERN[0][0] || buf[i] == SCRIPT_PATTERN[1][0]) {
                    hasScript = true;
                    for (int pos = 1; pos < 7 && (i + pos) < bytesRead; pos++) {
                        if (buf[i+pos] != SCRIPT_PATTERN[0][pos] &&
                                buf[i+pos] != SCRIPT_PATTERN[1][pos]) {
                            hasScript = false;
                            break;
                        }
                    }
                    if (hasScript) {
                        resp.addHeader("Cache-Control", "no-transform");
                        disposition = Part.ATTACHMENT;
                        break;
                    }
                }
            }
            if (bytesRead > 0)
                pis.unread(buf, 0, bytesRead);
        }
        if (!ignoreContentDisposition) {
            String cd = HttpUtil.createContentDisposition(req, disposition, filename == null ? "unknown" : filename);
            resp.addHeader("Content-Disposition", cd);
        }
        if (size > 0)
            resp.setContentLength((int)size);
        ByteUtil.copy(pis, true, resp.getOutputStream(), false);
    }
    /**
     * Determines whether or not the contentType passed might contain script or other unsavory tags.
     * @param contentType The content type to check
     * @return true if there's a possiblilty that <script> is valid, false if not
     */
    private static boolean isScriptableContent(String contentType) {
        // Make sure we don't have to worry about a null content type;
        if (Strings.isNullOrEmpty(contentType)) {
            return false;
        }
        contentType = Mime.getContentType(contentType).toLowerCase();
        // only set no-open for 'script type content'
        return SCRIPTABLE_CONTENT_TYPES.contains(contentType);

    }
}
