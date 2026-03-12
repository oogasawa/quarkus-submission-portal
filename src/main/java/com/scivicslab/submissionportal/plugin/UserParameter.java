package com.scivicslab.submissionportal.plugin;

public record UserParameter(
    String envVarName,
    String label,
    String placeholder,
    boolean secret,
    boolean required
) {}
