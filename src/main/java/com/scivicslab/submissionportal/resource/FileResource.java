package com.scivicslab.submissionportal.resource;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.scivicslab.submissionportal.service.FileService;

import io.quarkus.oidc.Tenant;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Upload area file management API (DDBJ-specific).
 * Manages metadata and provides presigned URLs for uploaded files.
 * Actual file upload is done via tus protocol or SFTP (see 030_SPDataUpload).
 */
@Path("/api/v1/files")
@Tenant("api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class FileResource {

    private static final Logger LOG = Logger.getLogger(FileResource.class.getName());

    private static final int PRESIGNED_URL_EXPIRY_SECS = 3600;

    @Inject
    FileService fileService;

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt;

    /**
     * List all files in the user's upload area.
     */
    @GET
    public Response listFiles() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();

        List<Map<String, Object>> files = fileService.listFiles(userId);
        return Response.ok(files).build();
    }

    /**
     * Get file metadata (size, checksum/etag, content-type, last modified).
     */
    @GET
    @Path("/{fileName}")
    public Response getFileMetadata(@PathParam("fileName") String fileName) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();

        Map<String, Object> meta = fileService.getFileMetadata(userId, fileName);
        if (meta == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(meta).build();
    }

    /**
     * Delete a file from the upload area.
     */
    @DELETE
    @Path("/{fileName}")
    public Response deleteFile(@PathParam("fileName") String fileName) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();

        LOG.info("File delete requested: user=" + userId + ", file=" + fileName);
        boolean deleted = fileService.deleteFile(userId, fileName);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("deleted", true, "fileName", fileName)).build();
    }

    /**
     * Generate a presigned download URL for a file.
     */
    @GET
    @Path("/{fileName}/download-url")
    public Response getDownloadUrl(@PathParam("fileName") String fileName) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();

        String url = fileService.generatePresignedUrl(userId, fileName, PRESIGNED_URL_EXPIRY_SECS);
        if (url == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("downloadUrl", url,
            "expiresInSeconds", PRESIGNED_URL_EXPIRY_SECS)).build();
    }

    /**
     * Trigger file validation (async — starts a validation job).
     * Returns 202 Accepted with a validation ID.
     */
    @POST
    @Path("/{fileName}/validate")
    public Response validateFile(@PathParam("fileName") String fileName) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();

        // Check file exists
        Map<String, Object> meta = fileService.getFileMetadata(userId, fileName);
        if (meta == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // TODO: integrate with POJO-actor based validation pipeline
        // For now, return accepted with a placeholder
        LOG.info("File validation requested: user=" + userId + ", file=" + fileName);
        return Response.accepted(Map.of(
            "fileName", fileName,
            "validationStatus", "PENDING",
            "message", "Validation queued. Use GET /files/{fileName}/validation to check status."
        )).build();
    }

    /**
     * Get file validation result.
     */
    @GET
    @Path("/{fileName}/validation")
    public Response getValidationResult(@PathParam("fileName") String fileName) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();

        // TODO: look up validation result from DB/actor system
        // Placeholder response
        return Response.ok(Map.of(
            "fileName", fileName,
            "validationStatus", "NOT_VALIDATED",
            "message", "File validation not yet implemented."
        )).build();
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    private String getCurrentUsername() {
        if (jwt != null) {
            String username = jwt.getClaim("preferred_username");
            if (username != null) return username;
        }
        if (identity != null && !identity.isAnonymous()) {
            return identity.getPrincipal().getName();
        }
        return null;
    }
}
