import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LoggerUtil {
    private final File logFile;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public LoggerUtil(String path) {
        this.logFile = new File(path);
        try {
            if (!logFile.exists()) logFile.createNewFile();
        } catch (IOException ignored) {}
    }

    public synchronized void log(String clientIp, String method, String url, int status, long bytes) {
        String time = sdf.format(new Date());
        String line = String.format("[%s] %s %s %s %d %s%n", time, clientIp, method, url, status, (bytes >= 0 ? bytes + "b" : "-"));
        try (FileWriter fw = new FileWriter(logFile, true)) {
            fw.write(line);
        } catch (IOException ignored) {}
        // optional: also print to console
        System.out.print(line);
    }
}
