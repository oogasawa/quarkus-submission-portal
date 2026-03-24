package com.scivicslab.submissionportal.resource;

import java.util.logging.Logger;

import com.scivicslab.submissionportal.dto.*;
import com.scivicslab.submissionportal.model.SubmissionTicket;
import com.scivicslab.submissionportal.service.SubmissionService;

import io.quarkus.oidc.Tenant;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * ENA Webin REST V2 compatible submission API.
 * Authentication: OIDC Bearer token via Keycloak.
 */
@Path("/api/v1")
@Tenant("api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SubmissionResource {

    private static final Logger LOG = Logger.getLogger(SubmissionResource.class.getName());

    @Inject
    SubmissionService submissionService;

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt;

    /**
     * Synchronous submission (ENA /submit equivalent).
     */
    @POST
    @Path("/submit")
    public Response submit(SubmissionRequest request) {
        String userId = getCurrentUsername();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        LOG.info("Sync submission from user: " + userId);
        SubmissionReceipt receipt = submissionService.processSubmission(userId, request);
        return Response.ok(receipt).build();
    }

    /**
     * Validation only (no persistence).
     */
    @POST
    @Path("/submit/validate")
    public Response validate(SubmissionRequest request) {
        String userId = getCurrentUsername();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Force VALIDATE action
        if (request.submission == null) {
            request.submission = new SubmissionDto();
        }
        if (request.submission.actions == null) {
            request.submission.actions = new java.util.ArrayList<>();
        }
        request.submission.actions.clear();
        ActionDto validateAction = new ActionDto();
        validateAction.type = "VALIDATE";
        request.submission.actions.add(validateAction);

        LOG.info("Validation request from user: " + userId);
        SubmissionReceipt receipt = submissionService.processSubmission(userId, request);
        return Response.ok(receipt).build();
    }

    /**
     * Async submission (ENA /submit/queue equivalent).
     * Returns submissionId + poll URL.
     */
    @POST
    @Path("/submit/queue")
    public Response submitAsync(SubmissionRequest request, @Context UriInfo uriInfo) {
        String userId = getCurrentUsername();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        LOG.info("Async submission from user: " + userId);
        SubmissionTicket ticket = submissionService.createAsyncTicket(userId, request);

        String pollHref = uriInfo.getBaseUri() + "api/v1/submit/poll/" + ticket.submissionId;
        AsyncSubmissionResponse response = new AsyncSubmissionResponse(
            ticket.submissionId, userId, pollHref);
        return Response.accepted(response).build();
    }

    /**
     * Poll async submission status (ENA /submit/poll/{id} equivalent).
     * Returns 202 if still processing, 200 + receipt if complete.
     */
    @GET
    @Path("/submit/poll/{submissionId}")
    public Response poll(@PathParam("submissionId") String submissionId) {
        String userId = getCurrentUsername();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        SubmissionTicket ticket = submissionService.getTicket(submissionId, userId);
        if (ticket == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        switch (ticket.status) {
            case SUBMITTED:
            case VALIDATING:
                // Still processing
                return Response.status(Response.Status.ACCEPTED).build();
            default:
                // Processing complete — return receipt
                if (ticket.receiptJson != null) {
                    return Response.ok(ticket.receiptJson)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
                }
                // No receipt yet but status changed — return status
                return Response.ok(java.util.Map.of(
                    "submissionId", ticket.submissionId,
                    "status", ticket.status.name()
                )).build();
        }
    }

    private String getCurrentUsername() {
        if (jwt != null) {
            String username = jwt.getClaim("preferred_username");
            if (username != null) {
                return username;
            }
        }
        if (identity != null && !identity.isAnonymous()) {
            return identity.getPrincipal().getName();
        }
        return null;
    }
}
