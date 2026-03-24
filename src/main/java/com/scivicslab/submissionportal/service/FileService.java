package com.scivicslab.submissionportal.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * File management service for the upload area.
 * Wraps MinIO/S3 operations for the submission API.
 */
@ApplicationScoped
public class FileService {

    private static final Logger LOG = Logger.getLogger(FileService.class.getName());

    @ConfigProperty(name = "submission.s3.endpoint", defaultValue = "http://minio.minio.svc:9000")
    String endpoint;

    @ConfigProperty(name = "submission.s3.access-key", defaultValue = "minio-admin")
    String accessKey;

    @ConfigProperty(name = "submission.s3.secret-key", defaultValue = "changeme")
    String secretKey;

    @ConfigProperty(name = "submission.s3.bucket", defaultValue = "submission-portal")
    String bucket;

    private MinioClient getClient() {
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
    }

    /**
     * List files in a user's upload area.
     */
    public List<Map<String, Object>> listFiles(String userId) {
        List<Map<String, Object>> files = new ArrayList<>();
        String prefix = userId + "/";
        MinioClient client = getClient();

        Iterable<Result<Item>> results = client.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .build());

        for (Result<Item> result : results) {
            try {
                Item item = result.get();
                Map<String, Object> info = new HashMap<>();
                info.put("fileName", item.objectName().substring(prefix.length()));
                info.put("size", item.size());
                info.put("lastModified", item.lastModified().toString());
                files.add(info);
            } catch (Exception e) {
                LOG.warning("Failed to list object: " + e.getMessage());
            }
        }
        return files;
    }

    /**
     * Get file metadata (size, etag, last modified).
     */
    public Map<String, Object> getFileMetadata(String userId, String fileName) {
        try {
            MinioClient client = getClient();
            String objectName = userId + "/" + fileName;
            StatObjectResponse stat = client.statObject(
                StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());

            Map<String, Object> info = new HashMap<>();
            info.put("fileName", fileName);
            info.put("size", stat.size());
            info.put("etag", stat.etag());
            info.put("contentType", stat.contentType());
            info.put("lastModified", stat.lastModified().toString());
            return info;
        } catch (Exception e) {
            LOG.warning("File not found: " + userId + "/" + fileName + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Delete a file from the upload area.
     */
    public boolean deleteFile(String userId, String fileName) {
        try {
            MinioClient client = getClient();
            String objectName = userId + "/" + fileName;
            client.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            LOG.info("Deleted file: " + objectName);
            return true;
        } catch (Exception e) {
            LOG.warning("Failed to delete file: " + userId + "/" + fileName
                + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate a presigned download URL (valid for given duration in seconds).
     */
    public String generatePresignedUrl(String userId, String fileName, int expirySecs) {
        try {
            MinioClient client = getClient();
            String objectName = userId + "/" + fileName;
            return client.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expirySecs)
                    .build());
        } catch (Exception e) {
            LOG.warning("Failed to generate presigned URL: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generate a presigned download URL for a reviewed/approved submission's file.
     * Used by reviewers and curators to access submitted data files.
     */
    public String generatePresignedUrlForReview(String ownerUserId, String fileName, int expirySecs) {
        return generatePresignedUrl(ownerUserId, fileName, expirySecs);
    }
}
