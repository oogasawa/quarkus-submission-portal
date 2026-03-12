package com.scivicslab.submissionportal.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NfsStorageClient {

    private static final Logger LOG = Logger.getLogger(NfsStorageClient.class.getName());

    @ConfigProperty(name = "subportal.nfs.mount-path", defaultValue = "/mnt/nfs-data")
    String mountPath;

    private Path userDir(String userId) {
        String sanitized = userId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return Path.of(mountPath, sanitized);
    }

    /**
     * Lists all files recursively under a user's NFS directory.
     * Returns paths relative to the user directory.
     */
    public List<String> listUserFiles(String userId) {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .forEach(p -> files.add(dir.relativize(p).toString()));
        } catch (IOException e) {
            LOG.warning("Failed to list NFS files for " + userId + ": " + e.getMessage());
        }
        Collections.sort(files);
        return files;
    }

    /**
     * Returns total size of all files in a user's NFS directory.
     */
    public long getUserStorageSize(String userId) {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long[] total = {0};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .forEach(p -> {
                    try { total[0] += Files.size(p); }
                    catch (IOException ignored) {}
                });
        } catch (IOException e) {
            LOG.warning("Failed to compute NFS size for " + userId + ": " + e.getMessage());
        }
        return total[0];
    }
}
