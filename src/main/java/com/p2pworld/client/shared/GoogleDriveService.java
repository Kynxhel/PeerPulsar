package com.p2pworld.client.shared;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2pworld.FabricP2PMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure-Java Google Drive API v3 helper built on java.net.http.HttpClient.
 * Supports native HTTP PATCH updates, streaming resumable uploads for large region files,
 * paginated folder listings, and scoped per-world sharing.
 */
public class GoogleDriveService {
    private static final String DRIVE_API = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String ROOT_FOLDER_NAME = "FabricP2P_SharedWorlds";
    private static final long RESUMABLE_THRESHOLD_BYTES = 4L * 1024L * 1024L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
        String cachedId = cfg.getGoogleDriveFolderId();
        if (!cachedId.isBlank()) {
            return cachedId;
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
        String q = "'" + escapeQuery(parentFolderId) + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        return queryFiles(token, q);
    }

    public String getOrCreateSubfolder(String parentFolderId, String name) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String existing = findFolderId(token, name, parentFolderId);
        if (existing != null) return existing;
        return createFolder(token, name, parentFolderId);
    }

    public String findSubfolder(String parentFolderId, String name) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        return findFolderId(token, name, parentFolderId);
    }

    public String readTextFile(String parentFolderId, String fileName) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String fileId = findFileId(token, fileName, parentFolderId);
        if (fileId == null) return null;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API + "/" + encPathSegment(fileId) + "?alt=media"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> resp = sendString(req);
        if (resp.statusCode() != 200) {
            return null;
        }
        return resp.body();
    }

    public void uploadTextFile(String parentFolderId, String fileName, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        uploadBytes(parentFolderId, fileName, "application/json", bytes);
    }

    public void uploadFile(String parentFolderId, String fileName, String mimeType, Path localFile) throws IOException {
        uploadOrUpdateFileById(parentFolderId, null, fileName, mimeType, localFile);
    }

    /**
     * Uploads or updates a file and returns its Google Drive file ID for fast incremental sync.
     * Uses native HTTP PATCH for updates and Drive Resumable Upload for files larger than 4 MB.
     */
    public String uploadOrUpdateFileById(String parentFolderId, String existingFileId, String fileName, String mimeType, Path localFile) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        long size = Files.size(localFile);

        if (size > RESUMABLE_THRESHOLD_BYTES) {
            return uploadResumable(token, parentFolderId, existingFileId, fileName, mimeType, localFile);
        }

        if (existingFileId != null && !existingFileId.isBlank()) {
            HttpRequest patchReq = HttpRequest.newBuilder()
                    .uri(URI.create(DRIVE_UPLOAD_API + "/" + encPathSegment(existingFileId) + "?uploadType=media"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", mimeType)
                    .method("PATCH", HttpRequest.BodyPublishers.ofFile(localFile))
                    .build();
            HttpResponse<String> patchResp = sendString(patchReq);
            if (patchResp.statusCode() >= 200 && patchResp.statusCode() < 300) {
                return existingFileId;
            }
            if (patchResp.statusCode() != 404) {
                throw new IOException("Drive PATCH failed (" + patchResp.statusCode() + "): " + truncate(patchResp.body()));
            }
        }

        byte[] data = Files.readAllBytes(localFile);
        return createMultipartFile(token, parentFolderId, fileName, mimeType, data);
    }

    private String uploadResumable(String token, String parentFolderId, String existingFileId,
                                   String fileName, String mimeType, Path localFile) throws IOException {
        HttpRequest initReq;
        if (existingFileId != null && !existingFileId.isBlank()) {
            initReq = HttpRequest.newBuilder()
                    .uri(URI.create(DRIVE_UPLOAD_API + "/" + encPathSegment(existingFileId) + "?uploadType=resumable&fields=id"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Upload-Content-Type", mimeType)
                    .header("X-Upload-Content-Length", String.valueOf(Files.size(localFile)))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody())
                    .build();
        } else {
            JsonObject meta = new JsonObject();
            meta.addProperty("name", fileName);
            JsonArray parents = new JsonArray();
            parents.add(parentFolderId);
            meta.add("parents", parents);

            initReq = HttpRequest.newBuilder()
                    .uri(URI.create(DRIVE_UPLOAD_API + "?uploadType=resumable&fields=id"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Upload-Content-Type", mimeType)
                    .header("X-Upload-Content-Length", String.valueOf(Files.size(localFile)))
                    .POST(HttpRequest.BodyPublishers.ofString(meta.toString(), StandardCharsets.UTF_8))
                    .build();
        }

        HttpResponse<String> initResp = sendString(initReq);
        if (initResp.statusCode() == 404 && existingFileId != null) {
            return uploadResumable(token, parentFolderId, null, fileName, mimeType, localFile);
        }
        if (initResp.statusCode() < 200 || initResp.statusCode() >= 300) {
            throw new IOException("Drive resumable init failed (" + initResp.statusCode() + "): " + truncate(initResp.body()));
        }

        String uploadUri = initResp.headers().firstValue("Location").orElse(null);
        if (uploadUri == null || uploadUri.isBlank()) {
            throw new IOException("Drive resumable response missing Location header");
        }

        HttpRequest putReq = HttpRequest.newBuilder()
                .uri(URI.create(uploadUri))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", mimeType)
                .PUT(HttpRequest.BodyPublishers.ofFile(localFile))
                .build();
        HttpResponse<String> putResp = sendString(putReq);
        if (putResp.statusCode() < 200 || putResp.statusCode() >= 300) {
            throw new IOException("Drive resumable upload failed (" + putResp.statusCode() + "): " + truncate(putResp.body()));
        }
        if (existingFileId != null && !existingFileId.isBlank()) {
            return existingFileId;
        }
        return JsonParser.parseString(putResp.body()).getAsJsonObject().get("id").getAsString();
    }

    public boolean downloadFileById(String fileId, Path destFile) throws IOException {
        if (fileId == null || fileId.isBlank()) return false;
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API + "/" + encPathSegment(fileId) + "?alt=media"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    return false;
                }
                Files.createDirectories(destFile.getParent());
                try (OutputStream out = Files.newOutputStream(destFile)) {
                    in.transferTo(out);
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Drive download interrupted", e);
        }
    }

    public void deleteFileById(String fileId) {
        if (fileId == null || fileId.isBlank()) return;
        try {
            String token = GoogleAuthService.getInstance().getValidAccessToken();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DRIVE_API + "/" + encPathSegment(fileId)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .DELETE()
                    .build();
            sendString(req);
        } catch (Exception ignored) {}
    }

    /**
     * Grants Editor (writer) permission on a specific shared world Google Drive folder
     * to a verified friend's Google email address.
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

            String url = DRIVE_API + "/" + encPathSegment(folderId) + "/permissions?sendNotificationEmail=false";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(perm.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = sendString(req);
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                FabricP2PMod.LOGGER.info("[GoogleDrive] Auto-shared world folder {} with {} (Editor).", folderId, email);
                return true;
            }
            FabricP2PMod.LOGGER.warn("[GoogleDrive] Share permission response {}: {}", code, truncate(resp.body()));
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
        return downloadFileById(fileId, destFile);
    }

    private void uploadBytes(String parentFolderId, String fileName, String mimeType, byte[] data) throws IOException {
        String token = GoogleAuthService.getInstance().getValidAccessToken();
        String existingId = findFileId(token, fileName, parentFolderId);

        if (existingId != null) {
            HttpRequest patchReq = HttpRequest.newBuilder()
                    .uri(URI.create(DRIVE_UPLOAD_API + "/" + encPathSegment(existingId) + "?uploadType=media"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", mimeType)
                    .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(data))
                    .build();
            HttpResponse<String> resp = sendString(patchReq);
            if (resp.statusCode() >= 400) {
                throw new IOException("Drive update failed (" + resp.statusCode() + "): " + truncate(resp.body()));
            }
            return;
        }

        createMultipartFile(token, parentFolderId, fileName, mimeType, data);
    }

    private String createMultipartFile(String token, String parentFolderId, String fileName, String mimeType, byte[] data) throws IOException {
        String boundary = "FabricP2P_" + UUID.randomUUID().toString().replace("-", "");
        JsonObject meta = new JsonObject();
        meta.addProperty("name", fileName);
        JsonArray parents = new JsonArray();
        parents.add(parentFolderId);
        meta.add("parents", parents);

        ByteArrayOutputStream body = new ByteArrayOutputStream(data.length + 512);
        body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_UPLOAD_API + "?uploadType=multipart&fields=id"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> resp = sendString(req);
        if (resp.statusCode() >= 400) {
            throw new IOException("Drive multipart upload failed (" + resp.statusCode() + "): " + truncate(resp.body()));
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject().get("id").getAsString();
    }

    private String findFolderId(String token, String name, String parentId) throws IOException {
        String q = "name = '" + escapeQuery(name) + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        if (parentId != null) {
            q += " and '" + escapeQuery(parentId) + "' in parents";
        }
        List<DriveItem> items = queryFiles(token, q);
        return items.isEmpty() ? null : items.get(0).id();
    }

    private String findFileId(String token, String name, String parentId) throws IOException {
        String q = "name = '" + escapeQuery(name) + "' and '" + escapeQuery(parentId) + "' in parents and trashed = false";
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

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API + "?fields=id"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = sendString(req);
        if (resp.statusCode() >= 400) {
            throw new IOException("Drive createFolder failed (" + resp.statusCode() + "): " + truncate(resp.body()));
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject().get("id").getAsString();
    }

    private List<DriveItem> queryFiles(String token, String query) throws IOException {
        List<DriveItem> list = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(DRIVE_API)
                    .append("?q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .append("&pageSize=1000&fields=nextPageToken,files(id,name)");
            if (pageToken != null && !pageToken.isBlank()) {
                url.append("&pageToken=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = sendString(req);
            if (resp.statusCode() >= 400) {
                throw new IOException("Drive query failed (" + resp.statusCode() + "): " + truncate(resp.body()));
            }

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (root.has("files")) {
                for (JsonElement el : root.getAsJsonArray("files")) {
                    JsonObject f = el.getAsJsonObject();
                    list.add(new DriveItem(f.get("id").getAsString(), f.get("name").getAsString()));
                }
            }
            pageToken = (root.has("nextPageToken") && !root.get("nextPageToken").isJsonNull())
                    ? root.get("nextPageToken").getAsString()
                    : null;
        } while (pageToken != null && !pageToken.isBlank());

        return list;
    }

    private static HttpResponse<String> sendString(HttpRequest req) throws IOException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    private static String escapeQuery(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String encPathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public record DriveItem(String id, String name) {}
}
