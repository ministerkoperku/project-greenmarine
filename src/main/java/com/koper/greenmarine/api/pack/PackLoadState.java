package com.koper.greenmarine.api.pack;

import java.util.*;

/** Tracks successful submissions of the currently selected program contract. */
public final class PackLoadState {
    private String pack = "greenmarine:none", detail = "Disabled";
    private Set<String> expected = Set.of();
    private final Set<String> submitted = new HashSet<>();
    private boolean ready = true, fallback, failed;
    public void begin(String pack, Set<String> expected) {
        this.pack = Objects.requireNonNull(pack); this.expected = Set.copyOf(expected);
        submitted.clear(); ready = true; fallback = failed = false; detail = "Compiling shaders";
    }
    public void path(boolean ready, boolean fallback, String detail) {
        if (failed) return;
        this.ready = ready; this.fallback = fallback; this.detail = Objects.requireNonNull(detail);
    }
    public void submitted(String program) { if (!failed && expected.contains(program)) submitted.add(program); }
    public void fail(String reason) { failed = true; detail = reason == null ? "Unknown rendering failure" : reason; }
    public PackStatus status() {
        var state = pack.equals("greenmarine:none") ? PackStatus.State.DISABLED : failed ? PackStatus.State.FAILED
            : !ready || expected.isEmpty() || !submitted.containsAll(expected) ? PackStatus.State.LOADING
            : fallback ? PackStatus.State.FALLBACK : PackStatus.State.LOADED;
        return new PackStatus(pack, state, detail);
    }
}
