package com.p2pworld.client.shared;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2pworld.FabricP2PMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java Google Drive API v3 helper for uploading, downloading, and listing
 * Shared Cloud Worlds directly from Google Drive without needing Drive Desktop!
 */
public class GoogleDriveService {
    private static final String DRIVE_API = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String ROOT_FOLDER_NAME = "FabricP2P_SharedWorlds";

    private static GoogleDriveService instance;

    private GoogleDriveService() {}

    public static synchronized GoogleDriveService getInstance() {
        if (instance == null) {
            instance = new GoogleDriveService();
        }
        return instance;
    }

    /**
     * Ensures the root "FabricP2P_SharedWorlds" folder exists in Google Drive and returns its ID.
     */
    public String getOrCreateRootFolderId() throws IOException {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.getGoogleDriveFolderId().isBlank()) {
            return cfg.getGoogleDriveFolderId();
        }
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String existing = findFolderId(token, ROOT_FOLDER_NAME, null);
        if (existing != null) {
            cfg.setGoogleDriveFolderId(existing);
            return existing;
        }
        String created = createFolder(token, ROOT_FOLDER_NAME, null);
        cfg.setGoogleDriveFolderId(created);
        return created;
    }

    public List<DriveItem> listSubfolders(String parentFolderId) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String q = "'" + parentFolderId + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        return queryFiles(token, q);
    }

    public String getOrCreateSubfolder(String parentFolderId, String name) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String existing = findFolderId(token, name, parentFolderId);
        if (existing != null) return existing;
        return createFolder(token, name, parentFolderId);
    }

    public String readTextFile(String parentFolderId, String fileName) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String fileId = findFileId(token, fileName, parentFolderId);
        if (fileId == null) return null;

        HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_API + "/" + fileId + "?alt=media").toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        if (conn.getResponseCode() != 200) return null;
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    public void uploadTextFile(String parentFolderId, String fileName, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        uploadBytes(parentFolderId, fileName, "application/json", bytes);
    }

    public void uploadFile(String parentFolderId, String fileName, String mimeType, Path localFile) throws IOException {
        byte[] bytes = Files.readAllBytes(localFile);
        uploadBytes(parentFolderId, fileName, mimeType, bytes);
    }

    /**
     * Uploads or updates a file and returns its Google Drive file ID for fast incremental sync.
     */
    public String uploadOrUpdateFileById(String parentFolderId, String existingFileId, String fileName, String mimeType, Path localFile) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        byte[] data = Files.readAllBytes(localFile);

        if (existingFileId != null && !existingFileId.isBlank()) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_UPLOAD_API + "/" + existingFileId + "?uploadType=media").toURL().openConnection();
            conn.setRequestMethod("PATCH");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", mimeType);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            if (conn.getResponseCode() < 400) {
                return existingFileId;
            }
        }

        // Create new file via multipart upload and return its assigned ID
        String boundary = "FabricP2PBoundary" + System.currentTimeMillis();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", fileName);
        JsonArray parents = new JsonArray();
        parents.add(parentFolderId);
        meta.add("parents", parents);

        HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_UPLOAD_API + "?uploadType=multipart&fields=id").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            os.write(("\r\n--" + boundary + "\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() >= 400) {
            throw new IOException("Drive file upload failed (" + conn.getResponseCode() + ")");
        }
        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(resp).getAsJsonObject().get("id").getAsString();
    }

    public boolean downloadFileById(String fileId, Path destFile) throws IOException {
        if (fileId == null || fileId.isBlank()) return false;
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_API + "/" + fileId + "?alt=media").toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        if (conn.getResponseCode() != 200) return false;

        Files.createDirectories(destFile.getParent());
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(destFile)) {
            in.transferTo(out);
        }
        return true;
    }

    public void deleteFileById(String fileId) {
        if (fileId == null || fileId.isBlank()) return;
        try {
            String token = GoogleAuthService.getInstance().getValidAccessToken();
            HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_API + "/" + fileId).toURL().openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.getResponseCode();
        } catch (Exception ignored) {}
    }

    /**
     * Automatically grants Editor (writer) permission on a shared Google Drive folder
     * to a joining friend's Google email address (zero manual sharing needed!).
     */
    public boolean shareFolderWithEmail(String folderId, String email) {
        if (folderId == null || folderId.isBlank() || email == null || email.isBlank() || !email.contains("@")) {
            return false;
        }
        try {
            String token = GoogleAuthService.getInstance().getValidAccessToken();
            JsonObject perm = new JsonObject();
            perm.addProperty("role", "writer");
            perm.addProperty("type", "user");
            perm.addProperty("emailAddress", email.trim());

            String url = DRIVE_API + "/" + folderId + "/permissions?sendNotificationEmail=false";
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(perm.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                FabricP2PMod.LOGGER.info("[GoogleDrive] Auto-shared folder {} with {} (Editor).", folderId, email);
                return true;
            }
            FabricP2PMod.LOGGER.warn("[GoogleDrive] Share permission response {}: {}", code, email);
            return false;
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[GoogleDrive] Failed to share folder with {}: {}", email, e.getMessage());
            return false;
        }
    }

    public boolean downloadFile(String parentFolderId, String fileName, Path destFile) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String fileId = findFileId(token, fileName, parentFolderId);
        if (fileId == null) return false;

        HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_API + "/" + fileId + "?alt=media").toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        if (conn.getResponseCode() != 200) return false;

        Files.createDirectories(destFile.getParent());
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(destFile)) {
            in.transferTo(out);
        }
        return true;
    }

    private void uploadBytes(String parentFolderId, String fileName, String mimeType, byte[] data) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String existingId = findFileId(token, fileName, parentFolderId);

        if (existingId != null) {
            // Update existing file content via PATCH
            HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_UPLOAD_API + "/" + existingId + "?uploadType=media").toURL().openConnection();
            conn.setRequestMethod("PATCH");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", mimeType);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            if (conn.getResponseCode() >= 400) {
                throw new IOException("Drive update failed (" + conn.getResponseCode() + ")");
            }
            return;
        }

        // Multipart upload for new file
        String boundary = "FabricP2PBoundary" + System.currentTimeMillis();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", fileName);
        JsonArray parents = new JsonArray();
        parents.add(parentFolderId);
        meta.add("parents", parents);

        HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_UPLOAD_API + "?uploadType=multipart").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            os.write(("\r\n--" + boundary + "\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() >= 400) {
            String err = conn.getErrorStream() != null
                    ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                    : "";
            FabricP2PMod.LOGGER.warn("[GoogleDrive] Upload error: {}", err);
            throw new IOException("Drive upload failed (" + conn.getResponseCode() + ")");
        }
    }

    private String findFolderId(String token, String name, String parentId) throws IOException {
        String q = "name = '" + escapeQuery(name) + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        if (parentId != null) q += " and '" + parentId + "' in parents";
        List<DriveItem> items = queryFiles(token, q);
        return items.isEmpty() ? null : items.get(0).id();
    }

    private String findFileId(String token, String name, String parentId) throws IOException {
        String q = "name = '" + escapeQuery(name) + "' and '" + parentId + "' in parents and trashed = false";
        List<DriveItem> items = queryFiles(token, q);
        return items.isEmpty() ? null : items.get(0).id();
    }

    private String createFolder(String token, String name, String parentId) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.addProperty("mimeType", "application/vnd.google-apps.folder");
        if (parentId != null) {
            JsonArray parents = new JsonArray();
            parents.add(parentId);
            meta.add("parents", parents);
        }

        HttpURLConnection conn = (HttpURLConnection) URI.create(DRIVE_API).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        }
        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(resp).getAsJsonObject().get("id").getAsString();
    }

    private List<DriveItem> queryFiles(String token, String query) throws IOException {
        String url = DRIVE_API + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&fields=files(id,name)";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
        List<DriveItem> list = new ArrayList<>();
        if (root.has("files")) {
            for (JsonElement el : root.getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                list.add(new DriveItem(f.get("id").getAsString(), f.get("name").getAsString()));
            }
        }
        return list;
    }

    private static String escapeQuery(String s) {
        return s.replace("'", "\\'");
    }

    public record DriveItem(String id, String name) {}
}
