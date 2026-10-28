package com.apocollis.chunksteward.server;

/** Pure helper for the generator class lists; no Minecraft types, so it is unit tested. */
final class GeneratorSegments {
    private GeneratorSegments() {
    }

    /** True when {@code className} equals an entry, or an entry ending in a dot is a package prefix of it. */
    static boolean matches(String[] entries, String className) {
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith(".") ? className.startsWith(trimmed) : className.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
