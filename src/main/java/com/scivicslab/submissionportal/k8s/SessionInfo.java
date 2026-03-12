package com.scivicslab.submissionportal.k8s;

import com.scivicslab.submissionportal.plugin.ToolPlugin;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record SessionInfo(
    String sessionId,
    String userId,
    ToolPlugin toolPlugin,
    String resourceProfile,
    Map<String, String> userParams,
    String userStorageType,
    List<MountSpec> additionalMounts
) {
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       String resourceProfile, Map<String, String> userParams,
                       String userStorageType) {
        this(sessionId, userId, toolPlugin, resourceProfile, userParams, userStorageType, Collections.emptyList());
    }

    public String podName() {
        return "subportal-" + toolPlugin.name() + "-" + sessionId;
    }

    public String serviceName() {
        return "subportal-svc-" + sessionId;
    }
}
