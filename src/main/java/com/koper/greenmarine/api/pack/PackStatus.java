package com.koper.greenmarine.api.pack;

/** Renderer readiness, distinct from manifest/script initialization. */
public record PackStatus(String pack, State state, String detail) {
    public enum State { DISABLED, LOADING, LOADED, FALLBACK, FAILED }
    public boolean loaded() { return state == State.LOADED || state == State.FALLBACK; }
}
