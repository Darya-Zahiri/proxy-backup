import java.util.concurrent.*;
import java.util.*;

public class SimpleCache {
    private final ConcurrentHashMap<String, CacheEntry> map;
    private final int maxEntries;
    private final long ttlMillis;

    public SimpleCache(int maxEntries, long ttlSeconds) {
        this.maxEntries = maxEntries;
        this.map = new ConcurrentHashMap<>();
        this.ttlMillis = ttlSeconds * 1000L;
    }

    public CachedHttpResponse get(String url) {
        CacheEntry e = map.get(url);
        if (e == null) return null;

        long now = System.currentTimeMillis();
        if (now - e.timestamp > ttlMillis) {
            map.remove(url);
            return null;
        }
        return e.response;
    }

    public void put(String url, CachedHttpResponse response) {
        if (map.size() >= maxEntries) {
            // حذف ساده: حذف اولین کلید موجود
            Iterator<String> it = map.keySet().iterator();
            if (it.hasNext()) {
                map.remove(it.next());
            }
        }
        map.put(url, new CacheEntry(response));
    }

    private static class CacheEntry {
        final CachedHttpResponse response;
        final long timestamp;
        CacheEntry(CachedHttpResponse r) {
            this.response = r;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
