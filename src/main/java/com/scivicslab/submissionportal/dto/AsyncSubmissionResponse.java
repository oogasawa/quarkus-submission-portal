package com.scivicslab.submissionportal.dto;

import java.util.Map;

/**
 * Response for async submission (POST /api/v1/submit/queue).
 * Contains submissionId and poll link.
 */
public class AsyncSubmissionResponse {
    public String submissionId;
    public String submissionAccountId;

    @com.fasterxml.jackson.annotation.JsonProperty("_links")
    public Map<String, Map<String, String>> links;

    public AsyncSubmissionResponse() {}

    public AsyncSubmissionResponse(String submissionId, String userId, String pollHref) {
        this.submissionId = submissionId;
        this.submissionAccountId = userId;
        this.links = Map.of("poll", Map.of("href", pollHref));
    }
}
