import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class Main {
    public static final int PORT = 8080;
    public static final int THREAD_POOL = 100;
    public static final String BLACKLIST_FILE = "blacklist.txt";
    public static final String LOG_FILE = "proxy.log";

    public static void main(String[] args) {
        int port = PORT;
        if (args.length >= 1) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        System.out.println("Starting proxy on port " + port);
        FilterManager filter = new FilterManager(BLACKLIST_FILE);
        SimpleCache cache = new SimpleCache(100, 60 * 5); // maxEntries=100, ttlSeconds=300
        LoggerUtil logger = new LoggerUtil(LOG_FILE);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(30_000); // 30s socket timeout for client read
                ProxyHandler handler = new ProxyHandler(clientSocket, cache, filter, logger);
                pool.execute(handler);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            pool.shutdown();
        }
    }
}
