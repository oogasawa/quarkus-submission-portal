package com.scivicslab.submissionportal.plugin;

import java.util.Map;

public class AssemblyPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "assembly-submission";
    }

    @Override
    public String displayName() {
        return "Genome Assembly";
    }

    @Override
    public String description() {
        return "Submit genome assembly data (contigs, scaffolds, chromosomes) to INSDC.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/assembly-submission:0.1.0";
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
    public boolean singleInstance() {
        return true;
    }
}
