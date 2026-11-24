package com.kynxhel.peerpulsar.client.network;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UPnPService {
    private static UPnPService instance;
    private final Object portLock = new Object();
    private final AtomicLong mapAttemptGen = new AtomicLong(0);

    private volatile String controlUrl = null;
    private volatile String serviceType = null;
    private volatile String externalIp = null;
    private volatile String routerWanIp = null;
    private volatile String localIp = null;
    private volatile int mappedPort = -1;
    private volatile boolean isMapped = false;
    private volatile UPnPStatus status = UPnPStatus.IDLE;
    private ScheduledExecutorService leaseRenewer = null;

    private static final String[] SSDP_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANIPConnection:2"
    };

    private static final Pattern SERVICE_BLOCK_PATTERN = Pattern.compile(
            "(?is)<(?:[a-zA-Z0-9_-]+:)?service\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?service>"
    );
    private static final Pattern SERVICE_TYPE_PATTERN = Pattern.compile(
            "(?is)<(?:[a-zA-Z0-9_-]+:)?serviceType[^>]*>\\s*(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:[12])\\s*</(?:[a-zA-Z0-9_-]+:)?serviceType>"
    );
    private static final Pattern CONTROL_URL_PATTERN = Pattern.compile(
            "(?is)<(?:[a-zA-Z0-9_-]+:)?controlURL[^>]*>\\s*([^<]+?)\\s*</(?:[a-zA-Z0-9_-]+:)?controlURL>"
    );
    private static final Pattern EXTERNAL_IP_PATTERN = Pattern.compile(
            "(?is)<(?:[a-zA-Z0-9_-]+:)?NewExternalIPAddress[^>]*>\\s*([^<]+?)\\s*</(?:[a-zA-Z0-9_-]+:)?NewExternalIPAddress>"
    );

    private UPnPService() {}

    public static synchronized UPnPService getInstance() {
        if (instance == null) {
            instance = new UPnPService();
        }
        return instance;
    }

    public enum UPnPStatus {
        IDLE,
        SEARCHING,
        SUCCESS,
        FAILED
    }

    public UPnPStatus getStatus() {
        return status;
    }

    public boolean isMapped() {
        return isMapped;
    }

    public String getExternalIp() {
        return externalIp != null ? externalIp : "127.0.0.1";
    }

    public String getRouterWanIp() {
        return routerWanIp;
    }

    /**
     * Probes the local network for a UPnP IGD router, queries its WAN IP,
     * and optionally verifies whether AddPortMapping/DeletePortMapping works.
     */
    public GatewayProbeResult probeGateway(boolean testPortMapping) {
        synchronized (portLock) {
            this.localIp = resolveLocalIpAddress();
            String prevControlUrl = this.controlUrl;
            String prevServiceType = this.serviceType;
            this.controlUrl = null;
            this.serviceType = null;
            boolean found = discoverRouter();
            if (!found) {
                if (this.isMapped) {
                    this.controlUrl = prevControlUrl;
                    this.serviceType = prevServiceType;
                }
                this.routerWanIp = null;
                return new GatewayProbeResult(false, null, false);
            }

            this.routerWanIp = null;
            queryExternalIp();
            boolean canMapPort = false;
            if (testPortMapping && this.localIp != null && !this.localIp.startsWith("127.")) {
                int probePort = 54321 + (int) (System.currentTimeMillis() % 4000);
                // Use a short 60-second lease for diagnostic probes so it expires automatically even on crash
                if (sendAddPortMapping(probePort, this.localIp, "TCP", "PeerPulsar Diagnostic Probe", 60)
                        || sendAddPortMapping(probePort, this.localIp, "TCP", "PeerPulsar Diagnostic Probe", 0)) {
                    canMapPort = true;
                    deletePortMapping(probePort, "TCP");
                }
            }
            return new GatewayProbeResult(true, this.routerWanIp, canMapPort);
        }
    }

    public record GatewayProbeResult(boolean upnpFound, String routerWanIp, boolean portMappingWorks) {}

    public String getLocalIp() {
        if (localIp == null) {
            localIp = resolveLocalIpAddress();
        }
        return localIp;
    }

    public String refreshLocalIp() {
        String resolved = resolveLocalIpAddress();
        this.localIp = resolved;
        return resolved;
    }

    public int getMappedPort() {
        return mappedPort;
    }

    public boolean openPort(int port, String description) {
        try {
            return openPortAsync(port, description).get(7, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.mapAttemptGen.incrementAndGet();
            this.status = UPnPStatus.FAILED;
            return false;
        }
    }

    public void closePort() {
        try {
            closePortAsync().get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[UPnP] Timed out or interrupted waiting to close UPnP mapping: {}", e.getMessage());
            synchronized (portLock) {
                this.isMapped = false;
                this.mappedPort = -1;
                this.status = UPnPStatus.IDLE;
            }
        }
    }

    public CompletableFuture<Boolean> openPortAsync(int port) {
        return openPortAsync(port, "PeerPulsar P2P World (26.3)");
    }

    /**
     * Discovers UPnP router (IGD v1/v2) and forwards the specified TCP port.
     */
    public CompletableFuture<Boolean> openPortAsync(int port, String description) {
        final long attemptGen = this.mapAttemptGen.incrementAndGet();
        this.status = UPnPStatus.SEARCHING;

        return CompletableFuture.supplyAsync(() -> {
            synchronized (portLock) {
                if (this.mapAttemptGen.get() != attemptGen) {
                    return false;
                }
                try {
                    stopLeaseRenewerLocked();
                    // Clean up any previously mapped port first
                    if (isMapped && mappedPort > 0 && mappedPort != port && controlUrl != null) {
                        try {
                            deletePortMapping(mappedPort, "TCP");
                        } catch (Exception ignored) {}
                        isMapped = false;
                    }

                    this.localIp = resolveLocalIpAddress();

                    if (controlUrl == null || serviceType == null) {
                        boolean found = discoverRouter();
                        if (!found) {
                            PeerPulsar.LOGGER.warn("[UPnP] Router discovery failed or UPnP is disabled.");
                            resolveExternalIpFallback();
                            if (this.mapAttemptGen.get() == attemptGen) {
                                this.status = UPnPStatus.FAILED;
                            }
                            return false;
                        }
                    }

                    if (this.mapAttemptGen.get() != attemptGen) {
                        return false;
                    }

                    this.externalIp = null;
                    queryExternalIp();
                    if (externalIp == null || externalIp.isEmpty() || externalIp.equals("0.0.0.0")) {
                        resolveExternalIpFallback();
                    }

                    if (this.mapAttemptGen.get() != attemptGen) {
                        return false;
                    }

                    int grantedLease = addPortMapping(port, localIp, "TCP", description);
                    if (grantedLease < 0 && this.controlUrl == null && this.mapAttemptGen.get() == attemptGen) {
                        PeerPulsar.LOGGER.info("[UPnP] Re-discovering gateway after stale control URL failure...");
                        if (discoverRouter()) {
                            this.externalIp = null;
                            queryExternalIp();
                            if (externalIp == null || externalIp.isEmpty() || externalIp.equals("0.0.0.0")) {
                                resolveExternalIpFallback();
                            }
                            grantedLease = addPortMapping(port, localIp, "TCP", description);
                        }
                    }

                    if (grantedLease >= 0) {
                        if (this.mapAttemptGen.get() != attemptGen) {
                            PeerPulsar.LOGGER.info("[UPnP] Port {} mapping finished after timeout/cancel — deleting stray mapping.", port);
                            try {
                                deletePortMapping(port, "TCP");
                            } catch (Exception ignored) {}
                            return false;
                        }
                        this.mappedPort = port;
                        this.isMapped = true;
                        this.status = UPnPStatus.SUCCESS;
                        if (grantedLease > 0) {
                            scheduleLeaseRenewalLocked(attemptGen, port, localIp, description, grantedLease);
                        }
                        PeerPulsar.LOGGER.info("[UPnP] Port {} mapped to {}. External IP: {} (lease={}s)",
                                port, localIp, externalIp, grantedLease);
                        return true;
                    } else {
                        if (this.mapAttemptGen.get() == attemptGen) {
                            this.status = UPnPStatus.FAILED;
                        }
                        return false;
                    }
                } catch (Exception e) {
                    PeerPulsar.LOGGER.error("[UPnP] Exception during port mapping: {}", e.getMessage());
                    resolveExternalIpFallback();
                    if (this.mapAttemptGen.get() == attemptGen) {
                        this.status = UPnPStatus.FAILED;
                    }
                    return false;
                }
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private void stopLeaseRenewerLocked() {
        if (this.leaseRenewer != null) {
            this.leaseRenewer.shutdownNow();
            this.leaseRenewer = null;
        }
    }

    private void scheduleLeaseRenewalLocked(long attemptGen, int port, String mappedLocalIp, String description, int leaseSec) {
        stopLeaseRenewerLocked();
        long intervalSec = Math.max(300L, (leaseSec * 3L) / 4L);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PeerPulsar-UPnP-Renew");
            t.setDaemon(true);
            return t;
        });
        this.leaseRenewer = exec;
        exec.scheduleAtFixedRate(() -> {
            synchronized (portLock) {
                if (!isMapped || mappedPort != port || mapAttemptGen.get() != attemptGen) {
                    return;
                }
                if (!sendAddPortMapping(port, mappedLocalIp, "TCP", description, leaseSec)
                        && this.controlUrl == null
                        && mapAttemptGen.get() == attemptGen) {
                    if (discoverRouter()) {
                        sendAddPortMapping(port, mappedLocalIp, "TCP", description, leaseSec);
                    }
                }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    public CompletableFuture<Void> closePortAsync() {
        this.mapAttemptGen.incrementAndGet();
        return CompletableFuture.runAsync(() -> {
            synchronized (portLock) {
                stopLeaseRenewerLocked();
                if (isMapped && mappedPort > 0 && controlUrl != null) {
                    try {
                        boolean deleted = deletePortMapping(mappedPort, "TCP");
                        PeerPulsar.LOGGER.info("[UPnP] Port {} unmap result={}.", mappedPort, deleted);
                    } catch (Exception e) {
                        PeerPulsar.LOGGER.warn("[UPnP] Failed to unmap port {}: {}", mappedPort, e.getMessage());
                    }
                } else {
                    PeerPulsar.LOGGER.debug("[UPnP] closePort called with no active UPnP mapping.");
                }
                isMapped = false;
                mappedPort = -1;
                status = UPnPStatus.IDLE;
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private DatagramSocket createSsdpSocket() throws IOException {
        String ip = this.localIp;
        if (ip != null && !ip.isBlank() && !ip.startsWith("127.")) {
            try {
                return new DatagramSocket(new InetSocketAddress(InetAddress.getByName(ip), 0));
            } catch (Exception ignored) {}
        }
        return new DatagramSocket();
    }

    private boolean discoverRouter() {
        Set<String> triedLocations = new HashSet<>();
        try (DatagramSocket socket = createSsdpSocket()) {
            socket.setSoTimeout(1500);
            InetAddress group = InetAddress.getByName("239.255.255.250");

            for (String target : SSDP_TARGETS) {
                String ssdpRequest = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "ST: " + target + "\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n\r\n";
                byte[] requestBytes = ssdpRequest.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(requestBytes, requestBytes.length, group, 1900);
                socket.send(packet);
            }

            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + 3000;

            while (System.currentTimeMillis() < deadline) {
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(responsePacket);
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);
                    String location = extractHeader(response, "LOCATION");
                    if (location != null && triedLocations.add(location) && parseDeviceDescription(location)) {
                        return true;
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[UPnP] SSDP discovery error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Parses each <service> block individually so tag order (<controlURL> before or after <serviceType>)
     * and optional XML namespace prefixes never cause cross-service mismatches.
     */
    private boolean parseDeviceDescription(String locationUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(locationUrl).toURL().openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            String xml = readStream(conn.getInputStream());

            Matcher serviceMatcher = SERVICE_BLOCK_PATTERN.matcher(xml);
            while (serviceMatcher.find()) {
                String block = serviceMatcher.group(1);
                Matcher typeMatcher = SERVICE_TYPE_PATTERN.matcher(block);
                Matcher ctrlMatcher = CONTROL_URL_PATTERN.matcher(block);
                if (typeMatcher.find() && ctrlMatcher.find()) {
                    this.serviceType = typeMatcher.group(1).trim();
                    String rawControlUrl = ctrlMatcher.group(1).trim();
                    URI resolved = URI.create(locationUrl).resolve(rawControlUrl);
                    this.controlUrl = resolved.toString();
                    return true;
                }
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[UPnP] Failed to parse device XML from {}: {}", locationUrl, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private int addPortMapping(int port, String localIpAddr, String protocol, String description) {
        // Prefer a finite 1-hour lease (renewed while hosting) so ports auto-expire if the JVM crashes
        if (sendAddPortMapping(port, localIpAddr, protocol, description, 3600)) {
            return 3600;
        }
        // Fallback for older IGDv1 routers that only accept leaseDuration=0
        if (sendAddPortMapping(port, localIpAddr, protocol, description, 0)) {
            return 0;
        }
        return -1;
    }

    private boolean sendAddPortMapping(int port, String localIpAddr, String protocol, String description, int leaseDuration) {
        String safeDesc = escapeXml(description);
        String soapBody = "<?xml version=\"1.0\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">\r\n" +
                "<NewRemoteHost></NewRemoteHost>\r\n" +
                "<NewExternalPort>" + port + "</NewExternalPort>\r\n" +
                "<NewProtocol>" + protocol + "</NewProtocol>\r\n" +
                "<NewInternalPort>" + port + "</NewInternalPort>\r\n" +
                "<NewInternalClient>" + localIpAddr + "</NewInternalClient>\r\n" +
                "<NewEnabled>1</NewEnabled>\r\n" +
                "<NewPortMappingDescription>" + safeDesc + "</NewPortMappingDescription>\r\n" +
                "<NewLeaseDuration>" + leaseDuration + "</NewLeaseDuration>\r\n" +
                "</u:AddPortMapping>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        return sendSoapRequest("AddPortMapping", soapBody);
    }

    private boolean deletePortMapping(int port, String protocol) {
        String soapBody = "<?xml version=\"1.0\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">\r\n" +
                "<NewRemoteHost></NewRemoteHost>\r\n" +
                "<NewExternalPort>" + port + "</NewExternalPort>\r\n" +
                "<NewProtocol>" + protocol + "</NewProtocol>\r\n" +
                "</u:DeletePortMapping>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        return sendSoapRequest("DeletePortMapping", soapBody);
    }

    private void queryExternalIp() {
        if (controlUrl == null || serviceType == null) return;
        String soapBody = "<?xml version=\"1.0\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\">\r\n" +
                "</u:GetExternalIPAddress>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>";

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(controlUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#GetExternalIPAddress\"");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(soapBody.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                String responseXml = readStream(conn.getInputStream());
                Matcher matcher = EXTERNAL_IP_PATTERN.matcher(responseXml);
                if (matcher.find()) {
                    String ip = matcher.group(1).trim();
                    if (!ip.isEmpty() && !ip.equals("0.0.0.0")) {
                        this.routerWanIp = ip;
                        if (!NetworkDiagnosticsService.isCgnatOrPrivateIp(ip)) {
                            this.externalIp = ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void resolveExternalIpFallback() {
        for (String endpoint : NetworkEndpoints.getOrderedIpEcho(PeerPulsarConfig.get())) {
            HttpURLConnection conn = null;
            try {
                URL url = URI.create(endpoint).toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                String ip = readStream(conn.getInputStream()).trim();
                if (!ip.isEmpty() && ip.length() <= 45) {
                    this.externalIp = ip;
                    return;
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private boolean sendSoapRequest(String action, String soapBody) {
        String currentControlUrl = this.controlUrl;
        String currentServiceType = this.serviceType;
        if (currentControlUrl == null || currentServiceType == null) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(currentControlUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"" + currentServiceType + "#" + action + "\"");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(soapBody.getBytes(StandardCharsets.UTF_8));
            }

            return conn.getResponseCode() == 200;
        } catch (IOException e) {
            PeerPulsar.LOGGER.warn("[UPnP] SOAP {} connection/IO error (clearing cached gateway): {}", action, e.getMessage());
            this.controlUrl = null;
            this.serviceType = null;
            return false;
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[UPnP] SOAP {} request failed: {}", action, e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String escapeXml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Resolves the active non-loopback LAN IPv4 address instantaneously using UDP route lookup,
     * falling back to enumerating active NetworkInterfaces (avoids 127.0.1.1 on offline Linux hosts).
     */
    private String resolveLocalIpAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {}

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }

    private static String extractHeader(String response, String headerName) {
        Pattern pattern = Pattern.compile("(?i)^" + headerName + ":\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
