package dev.npclife;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Name source for armies. Reads plugins/NPCLife/names.txt (one Minecraft username per line - paste your own list,
 * e.g. exported from wherever you collect names). When the file runs out, plausible usernames are generated.
 * NPCs that get a name from the file also get that player's skin; generated names keep the default skin.
 */
final class NameManager {
    record Pick(String name, boolean real) { }

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String[] A = {"Shadow", "Blaze", "Frost", "Pixel", "Nova", "Toxic", "Ghost", "Raven", "Viper", "Storm",
            "Ender", "Cobalt", "Ember", "Rogue", "Hunter", "Nitro", "Zephyr", "Onyx", "Cipher", "Falcon", "Titan", "Lunar",
            "Rapid", "Silent", "Crimson", "Arctic", "Vortex", "Echo", "Delta", "Atlas", "Mystic", "Savage", "Turbo", "Void"};
    private static final String[] B = {"Slayer", "Wolf", "Knight", "Fox", "Blade", "Ninja", "Spark", "Hawk", "King", "Pro",
            "Gamer", "Bolt", "Rider", "Strike", "Craft", "Bear", "Drift", "Dash", "Fang", "Lord", "Zero", "Prime", "Rex"};

    private final NPCLifePlugin plugin;
    private final List<String> pool = new ArrayList<>();
    private final Random rng = new Random();

    NameManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    void load() {
        pool.clear();
        File f = new File(plugin.getDataFolder(), "names.txt");
        if (!f.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                Files.writeString(f.toPath(), "# One Minecraft username per line (3-16 letters, digits or underscore). Lines starting with # are ignored.\n"
                        + "# NPCs named from this file also get that player's skin. When the list runs out, names are generated.\n");
            } catch (IOException ex) { plugin.getLogger().warning("Could not create names.txt: " + ex); }
            return;
        }
        try {
            Set<String> seen = new HashSet<>();
            for (String line : Files.readAllLines(f.toPath())) {
                String n = line.trim();
                if (n.isEmpty() || n.startsWith("#") || !VALID.matcher(n).matches()) continue;
                if (seen.add(n.toLowerCase(Locale.ROOT))) pool.add(n);
            }
        } catch (IOException ex) { plugin.getLogger().warning("Could not read names.txt: " + ex); }
    }

    int size() { return pool.size(); }

    /** Returns count unique names, none of which appear (case-insensitively) in {@code used}. */
    List<Pick> take(int count, Set<String> used) {
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, rng);
        List<Pick> out = new ArrayList<>();
        for (String n : shuffled) {
            if (out.size() >= count) break;
            if (used.add(n.toLowerCase(Locale.ROOT))) out.add(new Pick(n, true));
        }
        int guard = 0;
        while (out.size() < count && guard++ < 20000) {
            String g = generate();
            if (used.add(g.toLowerCase(Locale.ROOT))) out.add(new Pick(g, false));
        }
        Collections.shuffle(out, rng);
        return out;
    }

    private String generate() {
        String s = A[rng.nextInt(A.length)] + (rng.nextInt(4) == 0 ? "_" : "")
                + (rng.nextBoolean() ? B[rng.nextInt(B.length)] : String.valueOf(rng.nextInt(1000)));
        return s.length() > 16 ? s.substring(0, 16) : s;
    }
}
