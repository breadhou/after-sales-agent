package com.mall.agent.flow;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Observation is separate from authorization; callers use the guarded helpers. */
public interface FlowObserver {
    FlowObserver NOOP = new FlowObserver() {
        public void onEvent(String phase, Long targetId, Map<String, Object> attributes) { }
        public void onSourceEvidence(String sourceKey, String text, String digest) { }
    };

    void onEvent(String phase, Long targetId, Map<String, Object> attributes);
    void onSourceEvidence(String sourceKey, String text, String digest);

    /** Allows recorders to retain a missing-evidence signal when a callback fails. */
    default void onObservationFailure(Throwable failure) { }

    static void event(FlowObserver observer, String phase, Long targetId, Map<String, Object> attributes) {
        try { observer.onEvent(phase, targetId, attributes); }
        catch (Throwable failure) { failed(observer, failure); }
    }

    static void source(FlowObserver observer, String key, String text, String digest) {
        try { observer.onSourceEvidence(key, text, digest); }
        catch (Throwable failure) { failed(observer, failure); }
    }

    static String textDigest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void failed(FlowObserver observer, Throwable failure) {
        try { observer.onObservationFailure(failure); }
        catch (Throwable ignored) { /* Observation cannot change the business path. */ }
    }
}
