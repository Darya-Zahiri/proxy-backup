import java.io.*;
import java.util.*;
import java.nio.file.*;

public class FilterManager {
    private final List<String> blockedPrefixes = new ArrayList<>();

    public FilterManager(String blacklistFile) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(blacklistFile));
            for (String l : lines) {
                String s = l.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    blockedPrefixes.add(s.toLowerCase());
                }
            }
        } catch (IOException e) {
            // no blacklist file -> no blocks
        }
    }

    public boolean isBlocked(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String p : blockedPrefixes) {
            if (h.equals(p) || h.startsWith(p)) return true;
        }
        return false;
    }
}
