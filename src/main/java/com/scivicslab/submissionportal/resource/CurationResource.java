package com.scivicslab.submissionportal.resource;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.scivicslab.submissionportal.dto.RejectRequest;
import com.scivicslab.submissionportal.model.SubmissionTicket;
import com.scivicslab.submissionportal.service.CurationService;
import com.scivicslab.submissionportal.service.CurationService.ApprovalResult;
import com.scivicslab.submissionportal.service.CurationService.SubmissionObjects;
import com.scivicslab.submissionportal.service.FileService;

import io.quarkus.oidc.Tenant;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Curation API — DDBJ-specific endpoints for curator (staff) operations.
 * Requires "curator" role in Keycloak.
 */
@Path("/api/v1/curate")
@Tenant("api")
@RolesAllowed("curator")
@Produces(MediaType.APPLICATION_JSON)
public class CurationResource {

    private static final Logger LOG = Logger.getLogger(CurationResource.class.getName());

    private static final int PRESIGNED_URL_EXPIRY_SECS = 3600; // 1 hour

    @Inject
    CurationService curationService;

    @Inject
    FileService fileService;

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt;

    /**
     * List submissions awaiting curation.
     */
    @GET
    @Path("/submissions")
    public Response listPendingSubmissions() {
        List<SubmissionTicket> tickets = curationService.listPendingSubmissions();
        return Response.ok(tickets).build();
    }

    /**
     * Get submission detail + all related objects.
     */
    @GET
    @Path("/submissions/{submissionId}")
    public Response getSubmission(@PathParam("submissionId") String submissionId) {
        SubmissionTicket ticket = curationService.getSubmission(submissionId);
        if (ticket == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        SubmissionObjects objects = curationService.getSubmissionObjects(ticket.userId);
        return Response.ok(Map.of(
            "submission", ticket,
            "objects", objects
        )).build();
    }

    /**
     * List files in a submission owner's upload area.
     */
    @GET
    @Path("/submissions/{submissionId}/files")
    public Response listFiles(@PathParam("submissionId") String submissionId) {
        SubmissionTicket ticket = curationService.getSubmission(submissionId);
        if (ticket == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(fileService.listFiles(ticket.userId)).build();
    }

    /**
     * Get file metadata for a submission owner's file.
     */
    @GET
    @Path("/submissions/{submissionId}/files/{fileName}")
    public Response getFileMetadata(@PathParam("submissionId") String submissionId,
                                    @PathParam("fileName") String fileName) {
        SubmissionTicket ticket = curationService.getSubmission(submissionId);
        if (ticket == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        var meta = fileService.getFileMetadata(ticket.userId, fileName);
        if (meta == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(meta).build();
    }

    /**
     * Generate presigned download URL for a curator to access a submitted file.
     */
    @GET
    @Path("/submissions/{submissionId}/files/{fileName}/download-url")
    public Response getDownloadUrl(@PathParam("submissionId") String submissionId,
                                   @PathParam("fileName") String fileName) {
        SubmissionTicket ticket = curationService.getSubmission(submissionId);
        if (ticket == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String url = fileService.generatePresignedUrl(
            ticket.userId, fileName, PRESIGNED_URL_EXPIRY_SECS);
        if (url == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("downloadUrl", url,
            "expiresInSeconds", PRESIGNED_URL_EXPIRY_SECS)).build();
    }

    /**
     * Approve a submission — issue accession numbers to all objects.
     */
    @POST
    @Path("/submissions/{submissionId}/approve")
    public Response approve(@PathParam("submissionId") String submissionId) {
        String curatorId = getCurrentUsername();
        LOG.info("Curator " + curatorId + " approving submission " + submissionId);

        ApprovalResult result = curationService.approve(submissionId);
        if (result == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(result).build();
    }

    /**
     * Reject a submission with reason.
     */
    @POST
    @Path("/submissions/{submissionId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("submissionId") String submissionId,
                           RejectRequest request) {
        String curatorId = getCurrentUsername();
        LOG.info("Curator " + curatorId + " rejecting submission " + submissionId
            + " reason: " + (request != null ? request.reason : "none"));

        String reason = request != null ? request.reason : null;
        boolean success = curationService.reject(submissionId, reason);
        if (!success) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("rejected", true, "submissionId", submissionId)).build();
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
