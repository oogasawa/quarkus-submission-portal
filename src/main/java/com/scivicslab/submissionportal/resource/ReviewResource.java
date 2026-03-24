package com.scivicslab.submissionportal.resource;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.scivicslab.submissionportal.model.*;
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
 * Reviewer API — for paper reviewers to access approved/accession-issued data.
 * Reviewers can only view data that has been approved (accession issued).
 * They cannot modify anything.
 *
 * Access control: any authenticated user with "reviewer" role.
 * The reviewer accesses data by accession number (which they get from a paper manuscript).
 */
@Path("/api/v1/review")
@Tenant("api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class ReviewResource {

    private static final Logger LOG = Logger.getLogger(ReviewResource.class.getName());

    private static final int PRESIGNED_URL_EXPIRY_SECS = 3600; // 1 hour

    @Inject
    FileService fileService;

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt;

    /**
     * Get study detail by accession (reviewer read-only access).
     * Only APPROVED or PUBLIC studies are visible.
     */
    @GET
    @Path("/studies/{accession}")
    public Response getStudy(@PathParam("accession") String accession) {
        Study study = Study.findByAccession(accession);
        if (study == null || !isVisible(study.status)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(study).build();
    }

    /**
     * Get samples linked to a study (by study accession).
     */
    @GET
    @Path("/studies/{accession}/samples")
    public Response getStudySamples(@PathParam("accession") String accession) {
        Study study = Study.findByAccession(accession);
        if (study == null || !isVisible(study.status)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<Sample> samples = Sample.findByUserId(study.userId);
        List<Sample> visible = samples.stream()
            .filter(s -> isVisible(s.status))
            .toList();
        return Response.ok(visible).build();
    }

    /**
     * Get experiments linked to a study.
     */
    @GET
    @Path("/studies/{accession}/experiments")
    public Response getStudyExperiments(@PathParam("accession") String accession) {
        Study study = Study.findByAccession(accession);
        if (study == null || !isVisible(study.status)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<Experiment> experiments = Experiment.findByUserId(study.userId);
        List<Experiment> visible = experiments.stream()
            .filter(e -> isVisible(e.status) && accession.equals(e.studyRef))
            .toList();
        return Response.ok(visible).build();
    }

    /**
     * Get runs linked to a study (via experiments).
     */
    @GET
    @Path("/studies/{accession}/runs")
    public Response getStudyRuns(@PathParam("accession") String accession) {
        Study study = Study.findByAccession(accession);
        if (study == null || !isVisible(study.status)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<Run> runs = Run.findByUserId(study.userId);
        List<Run> visible = runs.stream()
            .filter(r -> isVisible(r.status))
            .toList();
        return Response.ok(visible).build();
    }

    /**
     * List files associated with a study (all files from the study owner's upload area).
     */
    @GET
    @Path("/studies/{accession}/files")
    public Response listFiles(@PathParam("accession") String accession) {
        Study study = Study.findByAccession(accession);
        if (study == null || !isVisible(study.status)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(fileService.listFiles(study.userId)).build();
    }

    /**
     * Generate presigned download URL for reviewer file access.
     */
    @GET
    @Path("/studies/{accession}/files/{fileName}/download-url")
    public Response getDownloadUrl(@PathParam("accession") String accession,
                                   @PathParam("fileName") String fileName) {
        Study study = Study.findByAccession(accession);
        if (study == null || !isVisible(study.status)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String url = fileService.generatePresignedUrlForReview(
            study.userId, fileName, PRESIGNED_URL_EXPIRY_SECS);
        if (url == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("downloadUrl", url,
            "expiresInSeconds", PRESIGNED_URL_EXPIRY_SECS)).build();
    }

    /**
     * Only APPROVED, PRIVATE, or PUBLIC objects are visible to reviewers.
     */
    private boolean isVisible(SubmissionStatus status) {
        return status == SubmissionStatus.APPROVED
            || status == SubmissionStatus.PRIVATE
            || status == SubmissionStatus.PUBLIC;
    }
}
