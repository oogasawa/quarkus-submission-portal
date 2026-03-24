package com.scivicslab.submissionportal.dto;

import java.util.Map;

/**
 * ENA-compatible run DTO.
 */
public class RunDto {
    public String alias;
    public String accession;
    public ObjectRefDto experimentRef;
    public Map<String, Object> dataBlock;
}
