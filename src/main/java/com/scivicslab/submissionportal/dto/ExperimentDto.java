package com.scivicslab.submissionportal.dto;

import java.util.Map;

/**
 * ENA-compatible experiment DTO.
 */
public class ExperimentDto {
    public String alias;
    public String accession;
    public String title;
    public ObjectRefDto studyRef;
    public Map<String, Object> design;
    public Map<String, Object> platform;
}
