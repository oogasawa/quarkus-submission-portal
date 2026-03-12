package com.scivicslab.submissionportal.plugin;

import java.util.Map;

public class FileBrowserPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "file-browser";
    }

    @Override
    public String displayName() {
        return "File Browser";
    }

    @Override
    public String description() {
        return "Browse, upload, download, and manage files in your storage.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/filebrowser-sp:0.1.0-2603091024";
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
        return Map.of("cpu", "100m", "memory", "128Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "500m", "memory", "512Mi");
    }

    @Override
    public String userDataMountPath() {
        return "/srv";
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }

    @Override
    public boolean passthroughPath() {
        return true;
    }

    @Override
    public Long runAsUser() {
        return 1000L;
    }

    @Override
    public boolean singleInstance() {
        return true;
    }

    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 3;
    }
}
