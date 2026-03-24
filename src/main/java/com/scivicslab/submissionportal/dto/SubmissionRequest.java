package com.scivicslab.submissionportal.dto;

import java.util.List;

/**
 * Top-level ENA-compatible submission request.
 * A single request can contain multiple object types.
 */
public class SubmissionRequest {
    public SubmissionDto submission;
    public List<ProjectDto> projects;
    public List<SampleDto> samples;
    public List<ExperimentDto> experiments;
    public List<RunDto> runs;
    public List<AnalysisDto> analyses;
}
