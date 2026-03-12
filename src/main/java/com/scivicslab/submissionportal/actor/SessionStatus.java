package com.scivicslab.submissionportal.actor;

public record SessionStatus(
    String sessionId,
    String userId,
    String toolName,
    SessionState state,
    String podName,
    String accessUrl,
    String memo,
    String storageType
) {
    public SessionStatus(String sessionId, String userId, String toolName,
                         SessionState state, String podName, String accessUrl, String memo) {
        this(sessionId, userId, toolName, state, podName, accessUrl, memo, null);
    }
}
