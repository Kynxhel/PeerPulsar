package com.p2pworld.client.shared;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2pworld.FabricP2PMod;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java Google OAuth 2.0 Desktop Loopback + PKCE (RFC 8252) Sign-In Client.
 * Opens the system browser for Google login, captures the auth code on 127.0.0.1,
 * exchanges it for access + refresh tokens, and auto-refreshes expired tokens.
 */
public class GoogleAuthService {
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final String SCOPES = "https://www.googleapis.com/auth/drive.file openid email profile";

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
    private volatile boolean signingIn = false;

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
        return signingIn;
    }

    public boolean isSignedIn() {
        return SharedWorldConfig.getInstance().isGoogleSignedIn();
    }

    public void signOut() {
        SharedWorldConfig.getInstance().clearGoogleAuth();
        FabricP2PMod.LOGGER.info("[GoogleAuth] Signed out.");
    }

    /**
     * Starts the interactive browser OAuth 2.0 + PKCE login flow.
     */
    public CompletableFuture<AuthResult> signInAsync() {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        String clientId = resolveClientId();
        String clientSecret = resolveClientSecret();

        if (signingIn) {
            return CompletableFuture.completedFuture(new AuthResult(false, null, "Sign-in already in progress..."));
        }

        this.signingIn = true;

        return CompletableFuture.supplyAsync(() -> {
            HttpServer server = null;
            try {
                // 1. Start local loopback HTTP server on an ephemeral port
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                int port = server.getAddress().getPort();
                String redirectUri = "http://127.0.0.1:" + port + "/callback";

                CompletableFuture<String> codeFuture = new CompletableFuture<>();
                server.createContext("/callback", exchange -> {
                    String query = exchange.getRequestURI().getRawQuery();
                    Map<String, String> params = parseQuery(query);
                    String code = params.get("code");
                    String err = params.get("error");

                    String html;
                    if (code != null && !code.isBlank()) {
                        html = "<html><body style='background:#060913;color:#f8fafc;font-family:sans-serif;text-align:center;padding-top:80px'>"
                                + "<h1 style='color:#10b981'>&#10004; Google Sign-In Successful!</h1>"
                                + "<p style='color:#94a3b8'>You can close this tab and return to Minecraft (Fabric P2P World).</p>"
                                + "</body></html>";
                        codeFuture.complete(code);
                    } else {
                        html = "<html><body style='background:#060913;color:#ef4444;font-family:sans-serif;text-align:center;padding-top:80px'>"
                                + "<h1>Sign-In Cancelled / Failed</h1><p>" + (err != null ? err : "Unknown error") + "</p>"
                                + "</body></html>";
                        codeFuture.completeExceptionally(new IOException("OAuth error: " + err));
                    }

                    byte[] body = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
                server.start();

                // 2. PKCE verifier & challenge
                String codeVerifier = generateCodeVerifier();
                String codeChallenge = generateCodeChallenge(codeVerifier);

                String authUrl = AUTH_URL
                        + "?client_id=" + enc(clientId)
                        + "&redirect_uri=" + enc(redirectUri)
                        + "&response_type=code"
                        + "&scope=" + enc(SCOPES)
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
                if (!tokenResp.has("access_token")) {
                    String errDesc = tokenResp.has("error_description")
                            ? tokenResp.get("error_description").getAsString()
                            : tokenResp.toString();
                    return new AuthResult(false, null, "Token exchange failed: " + errDesc);
                }

                String accessToken = tokenResp.get("access_token").getAsString();
                String refreshToken = tokenResp.has("refresh_token") ? tokenResp.get("refresh_token").getAsString() : "";
                long expiresInSec = tokenResp.has("expires_in") ? tokenResp.get("expires_in").getAsLong() : 3600;
                long expiryMs = System.currentTimeMillis() + (expiresInSec - 60) * 1000L;

                // 5. Fetch user email
                String email = fetchUserEmail(accessToken);
                cfg.setGoogleTokens(accessToken, refreshToken, expiryMs, email);

                FabricP2PMod.LOGGER.info("[GoogleAuth] Signed in successfully as {}", email);
                return new AuthResult(true, email, null);
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[GoogleAuth] Sign-in failed: {}", e.getMessage());
                return new AuthResult(false, null, e.getMessage());
            } finally {
                this.signingIn = false;
                if (server != null) {
                    server.stop(1);
                }
            }
        });
    }

    /**
     * Returns a valid access token, automatically refreshing via refresh_token if expired.
     */
    public String getValidAccessToken() throws IOException {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isGoogleSignedIn()) {
            throw new IOException("Not signed in with Google");
        }
        if (!cfg.getGoogleAccessToken().isBlank() && System.currentTimeMillis() < cfg.getGoogleTokenExpiryMs()) {
            return cfg.getGoogleAccessToken();
        }

        // Refresh expired access token
        StringBuilder form = new StringBuilder();
        form.append("client_id=").append(enc(resolveClientId()));
        String secret = resolveClientSecret();
        if (!secret.isBlank()) {
            form.append("&client_secret=").append(enc(secret));
        }
        form.append("&refresh_token=").append(enc(cfg.getGoogleRefreshToken()));
        form.append("&grant_type=refresh_token");

        JsonObject resp = postFormJson(TOKEN_URL, form.toString());
        if (!resp.has("access_token")) {
            throw new IOException("Token refresh failed: " + resp);
        }
        String newAccess = resp.get("access_token").getAsString();
        long expiresIn = resp.has("expires_in") ? resp.get("expires_in").getAsLong() : 3600;
        cfg.setGoogleTokens(newAccess, null, System.currentTimeMillis() + (expiresIn - 60) * 1000L, null);
        return newAccess;
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
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
            HttpURLConnection conn = (HttpURLConnection) URI.create(USERINFO_URL).toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            String body = readAll(conn.getInputStream());
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("email")) return obj.get("email").getAsString();
            if (obj.has("name")) return obj.get("name").getAsString();
        } catch (Exception ignored) {}
        return "Google User";
    }

    private static JsonObject postFormJson(String url, String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
        }
        InputStream stream = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(stream);
        return JsonParser.parseString(resp).getAsJsonObject();
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "{}";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
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

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    public record AuthResult(boolean success, String email, String error) {}
}
