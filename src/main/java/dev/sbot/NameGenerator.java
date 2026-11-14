package dev.sbot;

import java.util.Locale;
import java.util.Random;

/** Generates unique-looking fictional Minecraft usernames, never using real profile lists. */
final class NameGenerator {
    private static final String[] START = {
            "Zel", "Vex", "Kiro", "Navo", "Lumi", "Yaro", "Dexo", "Fero",
            "Tavi", "Neri", "Zuno", "Rivo", "Mavi", "Kyro", "Juno", "Sero",
            "Aero", "Xavo", "Viro", "Laxo", "Zavi", "Nexo", "Fixo", "Kavi",
            "Relo", "Ciro", "Daro", "Iven", "Tyro", "Orix", "Velo", "Mexo"
    };
    private static final String[] END = {
            "lan", "zin", "ver", "rox", "tix", "nor", "via", "max",
            "dex", "zar", "ren", "tor", "lin", "vo", "ryx", "fel",
            "sol", "vex", "mir", "nix", "zen", "tek", "ki", "no"
    };

    private NameGenerator() { }

    static String generate(Random random, String prefix) {
        String base = START[random.nextInt(START.length)]
                + END[random.nextInt(END.length)];
        int number = random.nextInt(9000) + 1000;
        String actualPrefix = prefix == null ? "" : prefix.replaceAll("[^A-Za-z0-9_]", "");
        String result = actualPrefix + base + "_" + number;
        if (result.length() > 16) result = result.substring(0, 16);
        if (result.length() < 3) result = "SBot_" + number;
        return result;
    }
}
