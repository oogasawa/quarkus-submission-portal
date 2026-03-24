package com.scivicslab.submissionportal.dto;

import java.util.List;
import java.util.Map;

/**
 * ENA-compatible analysis DTO.
 */
public class AnalysisDto {
    public String alias;
    public String accession;
    public ObjectRefDto studyRef;
    public ObjectRefDto sampleRef;
    public Map<String, Object> analysisType;
    public List<FileRefDto> files;
}
