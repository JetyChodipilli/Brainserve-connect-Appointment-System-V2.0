package com.brainserve.appointment.configuration.api;

/** Read-only runtime policy contract used by operational modules. */
public interface WorkspacePolicy {
    boolean booleanValue(String key, boolean fallback);
    int integerValue(String key, int fallback);
    String stringValue(String key, String fallback);
}
