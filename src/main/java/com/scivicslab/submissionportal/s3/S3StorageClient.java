package com.scivicslab.submissionportal.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class S3StorageClient {

    private static final Logger LOG = Logger.getLogger(S3StorageClient.class.getName());

    @ConfigProperty(name = "submission.s3.endpoint", defaultValue = "http://minio.minio.svc:9000")
    String endpoint;

    @ConfigProperty(name = "submission.s3.access-key", defaultValue = "minio-admin")
    String accessKey;

    @ConfigProperty(name = "submission.s3.secret-key", defaultValue = "changeme")
    String secretKey;

    @ConfigProperty(name = "submission.s3.bucket", defaultValue = "submission-portal")
    String bucket;

    private MinioClient client;

    @PostConstruct
    void init() {
        client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                LOG.info("Bucket created: " + bucket);
            }
        } catch (Exception e) {
            LOG.warning("Failed to check/create bucket: " + e.getMessage());
        }
    }

    private String prefix(String userId) {
        return userId + "/";
    }

    /**
     * Lists top-level objects in a user's storage area.
     */
    public List<String> listUserFiles(String userId) {
        List<String> files = new ArrayList<>();
        String pfx = prefix(userId);

        Iterable<Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(pfx)
                        .recursive(true)
                        .build());

        for (Result<Item> result : results) {
            try {
                String name = result.get().objectName();
                files.add(name.substring(pfx.length()));
            } catch (Exception e) {
                LOG.warning("Failed to list object: " + e.getMessage());
            }
        }
        return files;
    }

    /**
     * Returns total size of a user's storage in bytes.
     */
    public long getUserStorageSize(String userId) {
        long totalSize = 0;
        String pfx = prefix(userId);

        Iterable<Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(pfx)
                        .recursive(true)
                        .build());

        for (Result<Item> result : results) {
            try {
                totalSize += result.get().size();
            } catch (Exception e) {
                LOG.warning("Failed to get object size: " + e.getMessage());
            }
        }
        return totalSize;
    }
}
