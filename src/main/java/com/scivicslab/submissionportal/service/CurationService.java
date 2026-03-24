package com.scivicslab.submissionportal.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.scivicslab.submissionportal.model.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Curation service for DDBJ staff (curators).
 * Handles approval/rejection of submissions and accession issuance.
 */
@ApplicationScoped
public class CurationService {

    private static final Logger LOG = Logger.getLogger(CurationService.class.getName());

    @Inject
    AccessionService accessionService;

    /**
     * List all submissions awaiting curation (status = SUBMITTED or CURATING).
     */
    public List<SubmissionTicket> listPendingSubmissions() {
        return SubmissionTicket.list("status in (?1, ?2) order by createdAt asc",
            SubmissionStatus.SUBMITTED, SubmissionStatus.CURATING);
    }

    /**
     * Get submission detail by ID (curator can see any submission).
     */
    public SubmissionTicket getSubmission(String submissionId) {
        return SubmissionTicket.findBySubmissionId(submissionId);
    }

    /**
     * Get all objects belonging to a submission's owner.
     */
    public SubmissionObjects getSubmissionObjects(String userId) {
        SubmissionObjects objs = new SubmissionObjects();
        objs.studies = Study.findByUserId(userId);
        objs.samples = Sample.findByUserId(userId);
        objs.experiments = Experiment.findByUserId(userId);
        objs.runs = Run.findByUserId(userId);
        objs.analyses = Analysis.findByUserId(userId);
        return objs;
    }

    /**
     * Approve a submission: issue accession numbers to all SUBMITTED objects
     * belonging to the submission's owner, then mark the ticket APPROVED.
     */
    @Transactional
    public ApprovalResult approve(String submissionId) {
        SubmissionTicket ticket = SubmissionTicket.findBySubmissionId(submissionId);
        if (ticket == null) {
            return null;
        }

        ApprovalResult result = new ApprovalResult();
        result.submissionId = submissionId;
        result.issuedAccessions = new ArrayList<>();
        Instant now = Instant.now();

        // Issue accessions to all SUBMITTED objects for this user
        List<Study> studies = Study.list("userId = ?1 and status = ?2",
            ticket.userId, SubmissionStatus.SUBMITTED);
        for (Study s : studies) {
            s.accession = accessionService.nextProjectAccession();
            s.status = SubmissionStatus.APPROVED;
            s.updatedAt = now;
            s.persist();
            result.issuedAccessions.add(
                new IssuedAccession("study", s.alias, s.accession));
        }

        List<Sample> samples = Sample.list("userId = ?1 and status = ?2",
            ticket.userId, SubmissionStatus.SUBMITTED);
        for (Sample s : samples) {
            s.accession = accessionService.nextSampleAccession();
            s.status = SubmissionStatus.APPROVED;
            s.updatedAt = now;
            s.persist();
            result.issuedAccessions.add(
                new IssuedAccession("sample", s.alias, s.accession));
        }

        List<Experiment> experiments = Experiment.list("userId = ?1 and status = ?2",
            ticket.userId, SubmissionStatus.SUBMITTED);
        for (Experiment e : experiments) {
            e.accession = accessionService.nextExperimentAccession();
            e.status = SubmissionStatus.APPROVED;
            e.updatedAt = now;
            e.persist();
            result.issuedAccessions.add(
                new IssuedAccession("experiment", e.alias, e.accession));
        }

        List<Run> runs = Run.list("userId = ?1 and status = ?2",
            ticket.userId, SubmissionStatus.SUBMITTED);
        for (Run r : runs) {
            r.accession = accessionService.nextRunAccession();
            r.status = SubmissionStatus.APPROVED;
            r.updatedAt = now;
            r.persist();
            result.issuedAccessions.add(
                new IssuedAccession("run", r.alias, r.accession));
        }

        List<Analysis> analyses = Analysis.list("userId = ?1 and status = ?2",
            ticket.userId, SubmissionStatus.SUBMITTED);
        for (Analysis a : analyses) {
            a.accession = accessionService.nextAnalysisAccession();
            a.status = SubmissionStatus.APPROVED;
            a.updatedAt = now;
            a.persist();
            result.issuedAccessions.add(
                new IssuedAccession("analysis", a.alias, a.accession));
        }

        ticket.status = SubmissionStatus.APPROVED;
        ticket.completedAt = now;
        ticket.persist();

        LOG.info("Submission " + submissionId + " approved, "
            + result.issuedAccessions.size() + " accessions issued.");
        return result;
    }

    /**
     * Reject a submission with reason.
     */
    @Transactional
    public boolean reject(String submissionId, String reason) {
        SubmissionTicket ticket = SubmissionTicket.findBySubmissionId(submissionId);
        if (ticket == null) {
            return false;
        }

        Instant now = Instant.now();

        // Mark all SUBMITTED objects as REJECTED
        Study.update("status = ?1, updatedAt = ?2 where userId = ?3 and status = ?4",
            SubmissionStatus.REJECTED, now, ticket.userId, SubmissionStatus.SUBMITTED);
        Sample.update("status = ?1, updatedAt = ?2 where userId = ?3 and status = ?4",
            SubmissionStatus.REJECTED, now, ticket.userId, SubmissionStatus.SUBMITTED);
        Experiment.update("status = ?1, updatedAt = ?2 where userId = ?3 and status = ?4",
            SubmissionStatus.REJECTED, now, ticket.userId, SubmissionStatus.SUBMITTED);
        Run.update("status = ?1, updatedAt = ?2 where userId = ?3 and status = ?4",
            SubmissionStatus.REJECTED, now, ticket.userId, SubmissionStatus.SUBMITTED);
        Analysis.update("status = ?1, updatedAt = ?2 where userId = ?3 and status = ?4",
            SubmissionStatus.REJECTED, now, ticket.userId, SubmissionStatus.SUBMITTED);

        ticket.status = SubmissionStatus.REJECTED;
        ticket.completedAt = now;
        // Store rejection reason in receipt JSON
        ticket.receiptJson = "{\"rejected\":true,\"reason\":\""
            + (reason != null ? reason.replace("\"", "\\\"") : "") + "\"}";
        ticket.persist();

        LOG.info("Submission " + submissionId + " rejected. Reason: " + reason);
        return true;
    }

    /** Container for objects belonging to a submission */
    public static class SubmissionObjects {
        public List<Study> studies;
        public List<Sample> samples;
        public List<Experiment> experiments;
        public List<Run> runs;
        public List<Analysis> analyses;
    }

    /** Result of an approval operation */
    public static class ApprovalResult {
        public String submissionId;
        public List<IssuedAccession> issuedAccessions;
    }

    /** A single issued accession */
    public static class IssuedAccession {
        public String objectType;
        public String alias;
        public String accession;

        public IssuedAccession() {}

        public IssuedAccession(String objectType, String alias, String accession) {
            this.objectType = objectType;
            this.alias = alias;
            this.accession = accession;
        }
    }
}
