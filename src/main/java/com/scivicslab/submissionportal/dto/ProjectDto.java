package com.scivicslab.submissionportal.dto;

import java.util.Map;

/**
 * ENA-compatible project (study) DTO.
 */
public class ProjectDto {
    public String alias;
    public String accession;
    public String title;
    public String description;
    public Map<String, Object> submissionProject;
}
