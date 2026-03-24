package com.scivicslab.submissionportal.dto;

import java.util.List;

/**
 * ENA-compatible submission block within a request.
 */
public class SubmissionDto {
    public String alias;
    public List<ActionDto> actions;
}
