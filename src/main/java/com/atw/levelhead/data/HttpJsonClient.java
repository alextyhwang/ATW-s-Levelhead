package com.atw.levelhead.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class HttpJsonClient {
    private final String userAgent;

    public HttpJsonClient(String userAgent) {
        this.userAgent = userAgent;
    }

    public String get(String url) throws Exception {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("GET");
        return readBody(connection);
    }

    public String get(String url, String headerName, String headerValue) throws Exception {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("GET");
        if (headerName != null && headerValue != null && !headerValue.isEmpty()) {
            connection.setRequestProperty(headerName, headerValue);
        }
        return readBody(connection);
    }

    public String postJson(String url, String json) throws Exception {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }
        return readBody(connection);
    }

    public int postForStatus(String url, String json) throws Exception {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    public static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", userAgent);
        return connection;
    }

    private String readBody(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            connection.disconnect();
            return "{\"success\":false,\"cause\":\"HTTP_" + status + "\"}";
        }

        try (InputStream inputStream = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }
}
