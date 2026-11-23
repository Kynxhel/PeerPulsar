package com.p2pworld.client.network;

import com.p2pworld.FabricP2PMod;

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
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UPnPService {
    private static UPnPService instance;
    private final Object portLock = new Object();

    private volatile String controlUrl = null;
    private volatile String serviceType = null;
    private volatile String externalIp = null;
    private volatile String routerWanIp = null;
    private volatile String localIp = null;
    private volatile int mappedPort = -1;
    private volatile boolean isMapped = false;
    private volatile UPnPStatus status = UPnPStatus.IDLE;

    private static final String[] SSDP_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANIPConnection:2"
    };

    private static final String[] IP_FALLBACK_ENDPOINTS = {
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com"
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
            boolean found = (controlUrl != null && serviceType != null) || discoverRouter();
            if (!found) {
                this.routerWanIp = null;
                return new GatewayProbeResult(false, null, false);
            }

            this.routerWanIp = null;
            queryExternalIp();
            boolean canMapPort = false;
            if (testPortMapping && this.localIp != null && !this.localIp.startsWith("127.")) {
                int probePort = 54321 + (int) (System.currentTimeMillis() % 4000);
                if (addPortMapping(probePort, this.localIp, "TCP", "FabricP2P Diagnostic Probe")) {
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

    public int getMappedPort() {
        return mappedPort;
    }

    public boolean openPort(int port, String description) {
        try {
            return openPortAsync(port, description).get(7, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.status = UPnPStatus.FAILED;
            return false;
        }
    }

    public void closePort() {
        try {
            closePortAsync().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            synchronized (portLock) {
                this.isMapped = false;
                this.mappedPort = -1;
                this.status = UPnPStatus.IDLE;
            }
        }
    }

    public CompletableFuture<Boolean> openPortAsync(int port) {
        return openPortAsync(port, "Minecraft P2P World (26.3)");
    }

    /**
     * Discovers UPnP router (IGD v1/v2) and forwards the specified TCP port.
     */
    public CompletableFuture<Boolean> openPortAsync(int port, String description) {
        this.status = UPnPStatus.SEARCHING;

        return CompletableFuture.supplyAsync(() -> {
            synchronized (portLock) {
                try {
                    // Clean up any previously mapped port first
                    if (isMapped && mappedPort > 0 && mappedPort != port && controlUrl != null) {
                        try {
                            deletePortMapping(mappedPort, "TCP");
                        } catch (Exception ignored) {}
                        isMapped = false;
                    }

                    this.localIp = resolveLocalIpAddress();
                    this.mappedPort = port;

                    if (controlUrl == null || serviceType == null) {
                        boolean found = discoverRouter();
                        if (!found) {
                            FabricP2PMod.LOGGER.warn("[UPnP] Router discovery failed or UPnP is disabled.");
                            resolveExternalIpFallback();
                            this.status = UPnPStatus.FAILED;
                            return false;
                        }
                    }

                    queryExternalIp();
                    if (externalIp == null || externalIp.isEmpty() || externalIp.equals("0.0.0.0")) {
                        resolveExternalIpFallback();
                    }

                    boolean success = addPortMapping(port, localIp, "TCP", description);
                    if (success) {
                        this.isMapped = true;
                        this.status = UPnPStatus.SUCCESS;
                        FabricP2PMod.LOGGER.info("[UPnP] Port {} mapped to {}. External IP: {}", port, localIp, externalIp);
                        return true;
                    } else {
                        this.status = UPnPStatus.FAILED;
                        return false;
                    }
                } catch (Exception e) {
                    FabricP2PMod.LOGGER.error("[UPnP] Exception during port mapping: {}", e.getMessage());
                    resolveExternalIpFallback();
                    this.status = UPnPStatus.FAILED;
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Void> closePortAsync() {
        return CompletableFuture.runAsync(() -> {
            synchronized (portLock) {
                if (isMapped && mappedPort > 0 && controlUrl != null) {
                    try {
                        deletePortMapping(mappedPort, "TCP");
                        FabricP2PMod.LOGGER.info("[UPnP] Port {} unmapped.", mappedPort);
                    } catch (Exception e) {
                        FabricP2PMod.LOGGER.warn("[UPnP] Failed to unmap port: {}", e.getMessage());
                    }
                }
                isMapped = false;
                mappedPort = -1;
                status = UPnPStatus.IDLE;
            }
        });
    }

    private boolean discoverRouter() {
        Set<String> triedLocations = new HashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
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
            FabricP2PMod.LOGGER.warn("[UPnP] SSDP discovery error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Parses each <service> block individually so tag order (<controlURL> before or after <serviceType>)
     * and optional XML namespace prefixes never cause cross-service mismatches.
     */
    private boolean parseDeviceDescription(String locationUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(locationUrl).toURL().openConnection();
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
            FabricP2PMod.LOGGER.warn("[UPnP] Failed to parse device XML from {}: {}", locationUrl, e.getMessage());
        }
        return false;
    }

    private boolean addPortMapping(int port, String localIpAddr, String protocol, String description) {
        if (sendAddPortMapping(port, localIpAddr, protocol, description, 0)) {
            return true;
        }
        // Some IGDv2 routers reject leaseDuration=0; retry with a 1-hour lease
        return sendAddPortMapping(port, localIpAddr, protocol, description, 3600);
    }

    private boolean sendAddPortMapping(int port, String localIpAddr, String protocol, String description, int leaseDuration) {
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
                "<NewPortMappingDescription>" + description + "</NewPortMappingDescription>\r\n" +
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

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(controlUrl).toURL().openConnection();
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
                Pattern pattern = Pattern.compile("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>");
                Matcher matcher = pattern.matcher(responseXml);
                if (matcher.find()) {
                    String ip = matcher.group(1).trim();
                    if (!ip.isEmpty() && !ip.equals("0.0.0.0")) {
                        this.routerWanIp = ip;
                        this.externalIp = ip;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void resolveExternalIpFallback() {
        for (String endpoint : IP_FALLBACK_ENDPOINTS) {
            try {
                URL url = URI.create(endpoint).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                String ip = readStream(conn.getInputStream()).trim();
                if (!ip.isEmpty() && ip.length() <= 45) {
                    this.externalIp = ip;
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean sendSoapRequest(String action, String soapBody) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(controlUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(soapBody.getBytes(StandardCharsets.UTF_8));
            }

            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[UPnP] SOAP {} request failed: {}", action, e.getMessage());
            return false;
        }
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
