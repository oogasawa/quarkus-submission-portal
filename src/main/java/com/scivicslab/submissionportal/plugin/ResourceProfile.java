package com.scivicslab.submissionportal.plugin;

import java.util.Map;

public record ResourceProfile(
    String name,
    String displayName,
    Map<String, String> requests,
    Map<String, String> limits,
    String storageSize
) {
    public ResourceProfile(String name, String displayName,
                           Map<String, String> requests, Map<String, String> limits) {
        this(name, displayName, requests, limits, "20Gi");
    }
}
