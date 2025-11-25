import java.io.*;

public class CachedHttpResponse {
    private final byte[] rawBytes;
    private final int statusCode;

    private CachedHttpResponse(byte[] rawBytes, int statusCode) {
        this.rawBytes = rawBytes;
        this.statusCode = statusCode;
    }

    public static CachedHttpResponse fromBytes(byte[] raw) throws IOException {
        // parse status code
        ByteArrayInputStream bis = new ByteArrayInputStream(raw);
        BufferedReader r = new BufferedReader(new InputStreamReader(bis, "ISO-8859-1"));
        String statusLine = r.readLine();
        int code = 0;
        if (statusLine != null) {
            String[] parts = statusLine.split(" ");
            try { code = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        }
        return new CachedHttpResponse(raw, code);
    }

    public byte[] toBytes() { return rawBytes; }
    public int getStatusCode() { return statusCode; }
    public int getBodyLength() { return rawBytes.length; }
}
