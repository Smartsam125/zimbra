package com.zimbra.cs.service.formatter;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
public class CallbackService {

    public static void sendCallback(String docId, String filename, String userName, String clientIp, String endpoint)
    {
        new Thread(() -> {
            try {
                // Format download date
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                String downloadDate = sdf.format(new Date());
                // Create JSON payload
                JSONObject payload = new JSONObject();
                payload.put("email", userName);
                payload.put("document_name", filename);
                payload.put("ip_address", clientIp);
                payload.put("download_date", downloadDate);
                payload.put("doc_id", docId);

                // Set up HTTP connection
                URL url = new URL(endpoint);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                // Write JSON payload to output stream
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.toString().getBytes());
                    os.flush();
                }
                // Get response code (optional)
                int responseCode = connection.getResponseCode();
                System.out.println("Callback response code: " + responseCode);

                connection.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}

