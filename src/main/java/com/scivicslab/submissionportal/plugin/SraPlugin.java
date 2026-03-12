package com.scivicslab.submissionportal.plugin;

import java.util.Map;

public class SraPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "sra-submission";
    }

    @Override
    public String displayName() {
        return "SRA Submission";
    }

    @Override
    public String description() {
        return "Submit raw sequencing data (FASTQ, BAM) to the Sequence Read Archive.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/sra-submission:0.1.0-2603090815";
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "250m", "memory", "512Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    @Override
    public String userDataMountPath() {
        return "/data";
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }

    @Override
    public boolean passthroughPath() {
        return false;
    }

    @Override
    public Long runAsUser() {
        // UBI8 OpenJDK image runs as UID 185
        return 185L;
    }

    @Override
    public boolean singleInstance() {
        return true;
    }
}
