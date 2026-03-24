package com.scivicslab.submissionportal.dto;

import java.util.List;

/**
 * ENA-compatible sample DTO.
 */
public class SampleDto {
    public String alias;
    public String accession;
    public String title;
    public SampleNameDto sampleName;
    public List<SampleAttributeDto> sampleAttributes;
}
