import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ProxyHandler implements Runnable {
    private final Socket clientSocket;
    private final SimpleCache cache;
    private final FilterManager filter;
    private final LoggerUtil logger;

    public ProxyHandler(Socket clientSocket, SimpleCache cache, FilterManager filter, LoggerUtil logger) {
        this.clientSocket = clientSocket;
        this.cache = cache;
        this.filter = filter;
        this.logger = logger;
    }

    @Override
    public void run() {
        String clientIp = clientSocket.getInetAddress().getHostAddress();
        try (InputStream clientIn = clientSocket.getInputStream();
             OutputStream clientOut = clientSocket.getOutputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.ISO_8859_1))) {

            // Read request line
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 3) return;
            String method = parts[0];
            String fullUrl = parts[1];
            String httpVersion = parts[2];

            // Read headers
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(":");
                if (idx > 0) {
                    String name = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    headers.put(name.toLowerCase(), value);
                }
            }

            // Determine host and port
            String hostHeader = headers.get("host");
            String host;
            int port;
            if ("CONNECT".equalsIgnoreCase(method)) {
                // fullUrl is like host:port
                String[] hp = fullUrl.split(":", 2);
                host = hp[0];
                port = (hp.length > 1) ? Integer.parseInt(hp[1]) : 443;
            } else {
                // fullUrl can be absolute URL or path; handle both
                URL url = new URL(fullUrl.startsWith("http") ? fullUrl : ("http://" + headers.getOrDefault("host", "localhost") + fullUrl));
                host = url.getHost();
                port = (url.getPort() == -1) ? url.getDefaultPort() : url.getPort();
            }

            // Filtering
            if (filter.isBlocked(host)) {
                String forbidden = httpVersion + " 403 Forbidden\r\nContent-Length: 0\r\n\r\n";
                clientOut.write(forbidden.getBytes(StandardCharsets.ISO_8859_1));
                clientOut.flush();
                logger.log(clientIp, method, fullUrl, 403, 0);
                return;
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                handleConnect(host, port, clientIn, clientOut, clientIp, method, fullUrl);
            } else {
                handleHttpRequest(method, fullUrl, httpVersion, headers, reader, clientOut, clientIp);
            }

        } catch (SocketTimeoutException ste) {
            // timeout, close quietly
        } catch (IOException e) {
            // log error maybe
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleConnect(String host, int port, InputStream clientIn, OutputStream clientOut, String clientIp, String method, String url) throws IOException {
        // Establish connection to target
        try (Socket serverSocket = new Socket(host, port)) {
            serverSocket.setSoTimeout(30_000);
            // send 200 to client
            String resp = "HTTP/1.1 200 Connection Established\r\n\r\n";
            clientOut.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            // Now tunnel bytes between client and server
            Thread t1 = new Thread(() -> streamCopy(clientIn, getQuietOutputStream(serverSocket)));
            Thread t2 = new Thread(() -> streamCopy(getQuietInputStream(serverSocket), clientOut));
            t1.start(); t2.start();
            try {
                t1.join(); t2.join();
            } catch (InterruptedException ignored) {}
            logger.log(clientIp, method, url, 200, -1);
        } catch (IOException e) {
            String err = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n";
            clientOut.write(err.getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            logger.log(clientIp, method, url, 502, 0);
        }
    }

    private void handleHttpRequest(String method, String fullUrl, String httpVersion, Map<String,String> headers, BufferedReader reader, OutputStream clientOut, String clientIp) throws IOException {
        // Only GET responses are cached in this simple impl
        boolean isGet = "GET".equalsIgnoreCase(method);

        // If the URL is absolute, create URL object; otherwise build from Host header
        URL urlObj = new URL(fullUrl.startsWith("http") ? fullUrl : ("http://" + headers.getOrDefault("host", "localhost") + fullUrl));
        String cacheKey = urlObj.toString();

        // Cache check
        if (isGet) {
            CachedHttpResponse cached = cache.get(cacheKey);
            if (cached != null) {
                clientOut.write(cached.toBytes());
                clientOut.flush();
                logger.log(clientIp, method, cacheKey, cached.getStatusCode(), cached.getBodyLength());
                return;
            }
        }

        String host = urlObj.getHost();
        int port = (urlObj.getPort() == -1) ? urlObj.getDefaultPort() : urlObj.getPort();
        String path = urlObj.getFile().isEmpty() ? "/" : urlObj.getFile();

        // Open socket to origin server
        try (Socket serverSocket = new Socket(host, port);
             OutputStream serverOut = serverSocket.getOutputStream();
             InputStream serverIn = serverSocket.getInputStream()) {

            serverSocket.setSoTimeout(30_000);

            // Build request line for origin (use path, not full URL)
            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" ").append(httpVersion).append("\r\n");

            // Forward headers, but remove Proxy-Connection and ensure Host
            for (Map.Entry<String,String> h : headers.entrySet()) {
                String name = h.getKey();
                String value = h.getValue();
                if ("proxy-connection".equalsIgnoreCase(name)) continue;
                if ("connection".equalsIgnoreCase(name)) continue; // let server decide
                req.append(name).append(": ").append(value).append("\r\n");
            }
            req.append("Connection: close\r\n"); // simplify: close after response
            req.append("\r\n");

            serverOut.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            serverOut.flush();

            // If there is a request body (POST), read it and forward (simple approach: check Content-Length)
            if (headers.containsKey("content-length")) {
                int len = Integer.parseInt(headers.get("content-length"));
                char[] body = new char[len];
                int read = 0;
                while (read < len) {
                    int n = reader.read(body, read, len - read);
                    if (n == -1) break;
                    read += n;
                }
                byte[] bodyBytes = new String(body).getBytes(StandardCharsets.ISO_8859_1);
                serverOut.write(bodyBytes);
                serverOut.flush();
            }

            // Read origin response into memory (for simplicity) then forward and maybe cache
            ByteArrayOutputStream rawResp = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int r;
            while ((r = serverIn.read(buffer)) != -1) {
                rawResp.write(buffer, 0, r);
            }
            byte[] respBytes = rawResp.toByteArray();

            // Write to client
            clientOut.write(respBytes);
            clientOut.flush();

            // Parse status code and headers for caching decision
            ByteArrayInputStream bis = new ByteArrayInputStream(respBytes);
            BufferedReader respReader = new BufferedReader(new InputStreamReader(bis, StandardCharsets.ISO_8859_1));
            String statusLine = respReader.readLine();
            int statusCode = 0;
            if (statusLine != null) {
                String[] sParts = statusLine.split(" ");
                try { statusCode = Integer.parseInt(sParts[1]); } catch (Exception ignored) {}
            }

            // If GET and 200, cache it
            if (isGet && statusCode == 200) {
                // store full raw bytes and headers
                cache.put(cacheKey, CachedHttpResponse.fromBytes(respBytes));
            }

            // Logging
            int bodyLen = respBytes.length;
            logger.log(clientIp, method, cacheKey, statusCode, bodyLen);

        } catch (IOException e) {
            String err = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n";
            clientOut.write(err.getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            logger.log(clientIp, method, fullUrl, 502, 0);
        }
    }

    private static void streamCopy(InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    // Helper to suppress checked exceptions in lambda threads
    private static OutputStream getQuietOutputStream(Socket s) {
        try { return s.getOutputStream(); } catch (IOException e) { return new ByteArrayOutputStream(); }
    }
    private static InputStream getQuietInputStream(Socket s) {
        try { return s.getInputStream(); } catch (IOException e) { return new ByteArrayInputStream(new byte[0]); }
    }
}
