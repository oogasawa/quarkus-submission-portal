package com.scivicslab.submissionportal.k8s;

public record MountSpec(
    String storageType,
    String mountPath,
    String sharedFrom
) {}
