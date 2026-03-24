package com.scivicslab.submissionportal.resource;

import java.util.List;

import com.scivicslab.submissionportal.model.*;

import io.quarkus.oidc.Tenant;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * ENA Webin Reports Service compatible API.
 * Provides read-only access to submitted objects.
 */
@Path("/api/v1")
@Tenant("api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt;

    // ── Studies ──

    @GET
    @Path("/studies")
    public Response listStudies() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        List<Study> studies = Study.findByUserId(userId);
        return Response.ok(studies).build();
    }

    @GET
    @Path("/studies/{accession}")
    public Response getStudy(@PathParam("accession") String accession) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        Study study = Study.findByAccession(accession);
        if (study == null || !study.userId.equals(userId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(study).build();
    }

    // ── Samples ──

    @GET
    @Path("/samples")
    public Response listSamples() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        List<Sample> samples = Sample.findByUserId(userId);
        return Response.ok(samples).build();
    }

    @GET
    @Path("/samples/{accession}")
    public Response getSample(@PathParam("accession") String accession) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        Sample sample = Sample.findByAccession(accession);
        if (sample == null || !sample.userId.equals(userId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(sample).build();
    }

    // ── Experiments ──

    @GET
    @Path("/experiments")
    public Response listExperiments() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        List<Experiment> experiments = Experiment.findByUserId(userId);
        return Response.ok(experiments).build();
    }

    @GET
    @Path("/experiments/{accession}")
    public Response getExperiment(@PathParam("accession") String accession) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        Experiment exp = Experiment.findByAccession(accession);
        if (exp == null || !exp.userId.equals(userId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(exp).build();
    }

    // ── Runs ──

    @GET
    @Path("/runs")
    public Response listRuns() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        List<Run> runs = Run.findByUserId(userId);
        return Response.ok(runs).build();
    }

    @GET
    @Path("/runs/{accession}")
    public Response getRun(@PathParam("accession") String accession) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        Run run = Run.findByAccession(accession);
        if (run == null || !run.userId.equals(userId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(run).build();
    }

    // ── Analyses ──

    @GET
    @Path("/analyses")
    public Response listAnalyses() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        List<Analysis> analyses = Analysis.findByUserId(userId);
        return Response.ok(analyses).build();
    }

    @GET
    @Path("/analyses/{accession}")
    public Response getAnalysis(@PathParam("accession") String accession) {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        Analysis analysis = Analysis.findByAccession(accession);
        if (analysis == null || !analysis.userId.equals(userId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(analysis).build();
    }

    // ── Submissions ──

    @GET
    @Path("/submissions")
    public Response listSubmissions() {
        String userId = getCurrentUsername();
        if (userId == null) return unauthorized();
        List<SubmissionTicket> tickets = SubmissionTicket.findByUserId(userId);
        return Response.ok(tickets).build();
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED).build();
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
