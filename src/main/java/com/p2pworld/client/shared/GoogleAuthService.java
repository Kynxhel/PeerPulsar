package com.p2pworld.client.shared;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2pworld.FabricP2PMod;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-Java Google OAuth 2.0 Desktop Loopback + PKCE (RFC 8252) Sign-In Client.
 * Opens the system browser for Google login, validates state + PKCE on 127.0.0.1,
 * exchanges for access + refresh + OIDC id_token, and verifies id_tokens via Google tokeninfo.
 */
public class GoogleAuthService {
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String SCOPES = "https://www.googleapis.com/auth/drive.file openid email profile";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    // Built-in Desktop OAuth 2.0 App Credentials loaded from bundled oauth.properties (injected at build time from gitignored local.properties)
    private static final String DEFAULT_CLIENT_ID;
    private static final String DEFAULT_CLIENT_SECRET;

    static {
        String cid = "";
        String csec = "";
        try (InputStream is = GoogleAuthService.class.getResourceAsStream("/assets/fabricp2p/oauth.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                cid = props.getProperty("google.clientId", "").trim();
                csec = props.getProperty("google.clientSecret", "").trim();
            }
        } catch (Exception ignored) {}
        DEFAULT_CLIENT_ID = cid;
        DEFAULT_CLIENT_SECRET = csec;
    }

    private static GoogleAuthService instance;
    private final AtomicBoolean signingIn = new AtomicBoolean(false);
    private final Object tokenRefreshLock = new Object();

    private String resolveClientId() {
        String custom = SharedWorldConfig.getInstance().getGoogleClientId();
        return !custom.isBlank() ? custom : DEFAULT_CLIENT_ID;
    }

    private String resolveClientSecret() {
        String custom = SharedWorldConfig.getInstance().getGoogleClientSecret();
        return !custom.isBlank() ? custom : DEFAULT_CLIENT_SECRET;
    }

    private GoogleAuthService() {}

    public static synchronized GoogleAuthService getInstance() {
        if (instance == null) {
            instance = new GoogleAuthService();
        }
        return instance;
    }

    public boolean isSigningIn() {
        return signingIn.get();
    }

    public boolean isSignedIn() {
        return SharedWorldConfig.getInstance().isGoogleSignedIn();
    }

    public void signOut() {
        SharedWorldConfig.getInstance().clearGoogleAuth();
        FabricP2PMod.LOGGER.info("[GoogleAuth] Signed out.");
    }

    /**
     * Starts the interactive browser OAuth 2.0 + PKCE + state login flow.
     */
    public CompletableFuture<AuthResult> signInAsync() {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        String clientId = resolveClientId();
        String clientSecret = resolveClientSecret();

        if (clientId.isBlank()) {
            return CompletableFuture.completedFuture(new AuthResult(
                    false, null, "Google OAuth Client ID not configured in this build."
            ));
        }

        if (!signingIn.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new AuthResult(false, null, "Sign-in already in progress..."));
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                HttpServer server = null;
                try {
                    // 1. Start local loopback HTTP server on an ephemeral port
                    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                    int port = server.getAddress().getPort();
                    String redirectUri = "http://127.0.0.1:" + port + "/callback";
                    String expectedState = generateRandomToken(24);

                    CompletableFuture<String> codeFuture = new CompletableFuture<>();
                    server.createContext("/callback", exchange -> {
                        String query = exchange.getRequestURI().getRawQuery();
                        Map<String, String> params = parseQuery(query);
                        String code = params.get("code");
                        String state = params.get("state");
                        String err = params.get("error");

                        int statusCode;
                        String html;
                        if (!expectedState.equals(state)) {
                            statusCode = 400;
                            String reason = "Invalid OAuth state parameter (CSRF check failed)";
                            html = "<html><body style='background:#060913;color:#ef4444;font-family:sans-serif;text-align:center;padding-top:80px'>"
                                    + "<h1>Sign-In Cancelled / Failed</h1><p>" + escapeHtml(reason) + "</p>"
                                    + "</body></html>";
                        } else if (code != null && !code.isBlank() && (err == null || err.isBlank())) {
                            statusCode = 200;
                            html = "<html><body style='background:#060913;color:#f8fafc;font-family:sans-serif;text-align:center;padding-top:80px'>"
                                    + "<h1 style='color:#10b981'>&#10004; Google Sign-In Successful!</h1>"
                                    + "<p style='color:#94a3b8'>You can close this tab and return to Minecraft (Fabric P2P World).</p>"
                                    + "</body></html>";
                            codeFuture.complete(code);
                        } else {
                            statusCode = 200;
                            String reason = (err != null && !err.isBlank()) ? err : "Unknown error";
                            html = "<html><body style='background:#060913;color:#ef4444;font-family:sans-serif;text-align:center;padding-top:80px'>"
                                    + "<h1>Sign-In Cancelled / Failed</h1><p>" + escapeHtml(reason) + "</p>"
                                    + "</body></html>";
                            codeFuture.completeExceptionally(new IOException("OAuth error: " + reason));
                        }

                        byte[] body = html.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        exchange.sendResponseHeaders(statusCode, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                    });
                    server.start();

                    // 2. PKCE verifier & challenge
                    String codeVerifier = generateRandomToken(32);
                    String codeChallenge = generateCodeChallenge(codeVerifier);

                    String authUrl = AUTH_URL
                            + "?client_id=" + enc(clientId)
                            + "&redirect_uri=" + enc(redirectUri)
                            + "&response_type=code"
                            + "&scope=" + enc(SCOPES)
                            + "&state=" + enc(expectedState)
                            + "&code_challenge=" + enc(codeChallenge)
                            + "&code_challenge_method=S256"
                            + "&access_type=offline"
                            + "&prompt=" + enc("select_account consent");

                    openBrowser(authUrl);

                    // 3. Wait up to 2 minutes for user to finish login in browser
                    String authCode = codeFuture.get(120, TimeUnit.SECONDS);

                    // 4. Exchange auth code for tokens
                    StringBuilder form = new StringBuilder();
                    form.append("client_id=").append(enc(clientId));
                    if (!clientSecret.isBlank()) {
                        form.append("&client_secret=").append(enc(clientSecret));
                    }
                    form.append("&code=").append(enc(authCode));
                    form.append("&code_verifier=").append(enc(codeVerifier));
                    form.append("&grant_type=authorization_code");
                    form.append("&redirect_uri=").append(enc(redirectUri));

                    JsonObject tokenResp = postFormJson(TOKEN_URL, form.toString());
                    if (!tokenResp.has("access_token") || tokenResp.get("access_token").isJsonNull()) {
                        String errDesc = (tokenResp.has("error_description") && !tokenResp.get("error_description").isJsonNull())
                                ? tokenResp.get("error_description").getAsString()
                                : tokenResp.toString();
                        return new AuthResult(false, null, "Token exchange failed: " + errDesc);
                    }

                    String accessToken = tokenResp.get("access_token").getAsString();
                    String refreshToken = (tokenResp.has("refresh_token") && !tokenResp.get("refresh_token").isJsonNull())
                            ? tokenResp.get("refresh_token").getAsString().trim()
                            : "";
                    String idToken = (tokenResp.has("id_token") && !tokenResp.get("id_token").isJsonNull())
                            ? tokenResp.get("id_token").getAsString().trim()
                            : "";
                    long expiresInSec = (tokenResp.has("expires_in") && !tokenResp.get("expires_in").isJsonNull())
                            ? tokenResp.get("expires_in").getAsLong()
                            : 3600;
                    long expiryMs = System.currentTimeMillis() + Math.max(60, expiresInSec - 60) * 1000L;

                    // 5. Fetch verified user email (must contain '@')
                    String email = fetchUserEmail(accessToken);
                    if (email == null || !email.contains("@")) {
                        return new AuthResult(false, null, "Could not retrieve verified email address from Google.");
                    }

                    // Ensure we have a refresh token (newly issued or already stored for this exact account)
                    boolean sameAccount = !cfg.getGoogleUserEmail().isBlank()
                            && cfg.getGoogleUserEmail().equalsIgnoreCase(email);
                    String existingRefreshForSameEmail = sameAccount ? cfg.getGoogleRefreshToken() : "";
                    if (refreshToken.isBlank() && existingRefreshForSameEmail.isBlank()) {
                        return new AuthResult(
                                false,
                                null,
                                "Google did not return a refresh token. Please revoke previous access at myaccount.google.com/permissions and sign in again."
                        );
                    }

                    // Clear any previous account's folder bindings if switching accounts
                    if (!cfg.getGoogleUserEmail().isBlank() && !sameAccount) {
                        cfg.clearGoogleAuth();
                    }
                    cfg.setGoogleTokens(accessToken, refreshToken, idToken, expiryMs, email);

                    FabricP2PMod.LOGGER.info("[GoogleAuth] Signed in successfully as {}", email);
                    return new AuthResult(true, email, null);
                } catch (Exception e) {
                    FabricP2PMod.LOGGER.error("[GoogleAuth] Sign-in failed: {}", e.getMessage());
                    return new AuthResult(false, null, e.getMessage());
                } finally {
                    this.signingIn.set(false);
                    if (server != null) {
                        server.stop(1);
                    }
                }
            });
        } catch (RuntimeException e) {
            this.signingIn.set(false);
            throw e;
        }
    }

    /**
     * Checks whether a JWT has a valid Base64URL payload with a numeric {@code exp} claim (seconds)
     * strictly greater than {@code System.currentTimeMillis() + skewMs}.
     */
    public static boolean isJwtUnexpired(String jwt, long skewMs) {
        if (jwt == null || jwt.isBlank()) {
            return false;
        }
        try {
            String[] parts = jwt.trim().split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return false;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(decoded, StandardCharsets.UTF_8);
            JsonElement elem = JsonParser.parseString(payloadJson);
            if (!elem.isJsonObject()) {
                return false;
            }
            JsonObject payload = elem.getAsJsonObject();
            if (!payload.has("exp") || payload.get("exp").isJsonNull()) {
                return false;
            }
            long expSec = payload.get("exp").getAsLong();
            return expSec * 1000L > System.currentTimeMillis() + skewMs;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns a valid access token, automatically refreshing via refresh_token if expired (thread-safe).
     */
    public String getValidAccessToken() throws IOException {
        synchronized (tokenRefreshLock) {
            SharedWorldConfig cfg = SharedWorldConfig.getInstance();
            if (!cfg.isGoogleSignedIn()) {
                throw new IOException("Not signed in with Google");
            }
            if (!cfg.getGoogleAccessToken().isBlank() && System.currentTimeMillis() < cfg.getGoogleTokenExpiryMs()) {
                return cfg.getGoogleAccessToken();
            }
            refreshTokensLocked(cfg);
            return cfg.getGoogleAccessToken();
        }
    }

    /**
     * Returns a valid Google-signed OIDC id_token for cryptographic anti-spoof verification,
     * refreshing tokens if missing or expired. Returns empty string if not signed in or if a
     * fresh unexpired id_token cannot be obtained.
     */
    public String getValidIdToken() {
        synchronized (tokenRefreshLock) {
            SharedWorldConfig cfg = SharedWorldConfig.getInstance();
            if (!cfg.isGoogleSignedIn()) {
                return "";
            }
            String cached = cfg.getGoogleIdToken();
            if (isJwtUnexpired(cached, 60_000L)) {
                return cached;
            }
            try {
                refreshTokensLocked(cfg);
                String refreshed = cfg.getGoogleIdToken();
                if (isJwtUnexpired(refreshed, 30_000L)) {
                    return refreshed;
                }
                cfg.clearGoogleIdToken();
                FabricP2PMod.LOGGER.warn("[GoogleAuth] Token refresh did not return a fresh unexpired id_token; cleared stale id_token.");
                return "";
            } catch (Exception e) {
                if (isJwtUnexpired(cached, 5_000L)) {
                    FabricP2PMod.LOGGER.warn("[GoogleAuth] Failed to refresh id_token ({}); using still-valid cached id_token.", e.getMessage());
                    return cached;
                }
                if (!cached.isBlank() && !isJwtUnexpired(cached, 0L)) {
                    cfg.clearGoogleIdToken();
                }
                FabricP2PMod.LOGGER.warn("[GoogleAuth] Failed to refresh id_token: {}", e.getMessage());
                return "";
            }
        }
    }

    private void refreshTokensLocked(SharedWorldConfig cfg) throws IOException {
        String clientId = resolveClientId();
        if (clientId.isBlank()) {
            throw new IOException("Google OAuth Client ID is missing");
        }
        StringBuilder form = new StringBuilder();
        form.append("client_id=").append(enc(clientId));
        String secret = resolveClientSecret();
        if (!secret.isBlank()) {
            form.append("&client_secret=").append(enc(secret));
        }
        form.append("&refresh_token=").append(enc(cfg.getGoogleRefreshToken()));
        form.append("&grant_type=refresh_token");

        JsonObject resp = postFormJson(TOKEN_URL, form.toString());
        if (!resp.has("access_token") || resp.get("access_token").isJsonNull()) {
            throw new IOException("Token refresh failed: " + resp);
        }
        String newAccess = resp.get("access_token").getAsString();
        String newRefresh = (resp.has("refresh_token") && !resp.get("refresh_token").isJsonNull())
                ? resp.get("refresh_token").getAsString().trim()
                : null;
        String newIdToken = (resp.has("id_token") && !resp.get("id_token").isJsonNull())
                ? resp.get("id_token").getAsString().trim()
                : "";
        long expiresIn = (resp.has("expires_in") && !resp.get("expires_in").isJsonNull())
                ? resp.get("expires_in").getAsLong()
                : 3600;
        boolean hasFreshIdToken = !newIdToken.isBlank() && isJwtUnexpired(newIdToken, 0L);

        cfg.setGoogleTokens(newAccess, newRefresh, hasFreshIdToken ? newIdToken : "",
                System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L, null);

        if (!hasFreshIdToken && !cfg.getGoogleIdToken().isBlank() && !isJwtUnexpired(cfg.getGoogleIdToken(), 0L)) {
            cfg.clearGoogleIdToken();
        }
    }

    /**
     * Cryptographically verifies a Google OIDC id_token via Google's official tokeninfo endpoint.
     * Returns the verified lowercase email and Google subject ID, or null if invalid/unverified/expired.
     */
    public static VerifiedGoogleIdentity verifyIdTokenWithGoogle(String idToken) {
        if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKENINFO_URL + "?id_token=" + enc(idToken.trim())))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            String iss = (obj.has("iss") && !obj.get("iss").isJsonNull()) ? obj.get("iss").getAsString() : "";
            if (!iss.equals("https://accounts.google.com") && !iss.equals("accounts.google.com")) {
                return null;
            }
            String expectedClientId = GoogleAuthService.getInstance().resolveClientId();
            if (expectedClientId != null && !expectedClientId.isBlank()) {
                String aud = (obj.has("aud") && !obj.get("aud").isJsonNull()) ? obj.get("aud").getAsString().trim() : "";
                String azp = (obj.has("azp") && !obj.get("azp").isJsonNull()) ? obj.get("azp").getAsString().trim() : "";
                if (!expectedClientId.equals(aud) && !expectedClientId.equals(azp)) {
                    FabricP2PMod.LOGGER.warn("[GoogleAuth] Rejected id_token: aud/azp mismatch (aud='{}', azp='{}').", aud, azp);
                    return null;
                }
            }
            String emailVerified = (obj.has("email_verified") && !obj.get("email_verified").isJsonNull())
                    ? obj.get("email_verified").getAsString()
                    : "false";
            if (!"true".equalsIgnoreCase(emailVerified)) {
                return null;
            }
            String email = (obj.has("email") && !obj.get("email").isJsonNull())
                    ? obj.get("email").getAsString().trim().toLowerCase(Locale.ROOT)
                    : "";
            String sub = (obj.has("sub") && !obj.get("sub").isJsonNull())
                    ? obj.get("sub").getAsString().trim()
                    : "";
            if (email.isBlank() || !email.contains("@") || sub.isBlank()) {
                return null;
            }
            if (!obj.has("exp") || obj.get("exp").isJsonNull()) {
                return null;
            }
            long expSec = obj.get("exp").getAsLong();
            if (expSec * 1000L <= System.currentTimeMillis()) {
                return null;
            }
            return new VerifiedGoogleIdentity(sub, email);
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[GoogleAuth] id_token verification failed: {}", e.getMessage());
            return null;
        }
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[GoogleAuth] Could not auto-open browser: {}", e.getMessage());
        }
    }

    private static String fetchUserEmail(String accessToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(USERINFO_URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (obj.has("email")) {
                String email = obj.get("email").getAsString().trim();
                if (email.contains("@")) {
                    return email;
                }
            }
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[GoogleAuth] Failed to fetch userinfo email: {}", e.getMessage());
        }
        return null;
    }

    private static JsonObject postFormJson(String url, String formBody) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() != null && !resp.body().isBlank() ? resp.body() : "{}";
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Token endpoint error: " + e.getMessage(), e);
        }
    }

    private static String generateRandomToken(int byteLen) {
        byte[] bytes = new byte[byteLen];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    public record AuthResult(boolean success, String email, String error) {}

    public record VerifiedGoogleIdentity(String subjectId, String email) {}
}
