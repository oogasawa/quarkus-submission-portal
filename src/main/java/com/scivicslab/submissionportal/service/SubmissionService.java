package com.scivicslab.submissionportal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.submissionportal.dto.*;
import com.scivicslab.submissionportal.model.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class SubmissionService {

    private static final Logger LOG = Logger.getLogger(SubmissionService.class.getName());

    @Inject
    ObjectMapper objectMapper;

    /**
     * Process a synchronous submission (ADD action).
     * Persists all objects and returns a receipt.
     * Accessions are NOT issued at submit time (DDBJ workflow: curator approves first).
     */
    @Transactional
    public SubmissionReceipt processSubmission(String userId, SubmissionRequest request) {
        SubmissionReceipt receipt = new SubmissionReceipt();
        receipt.messages = new ReceiptMessages();
        receipt.receiptDate = Instant.now().toString();

        // Determine actions
        List<String> actionTypes = new ArrayList<>();
        LocalDate holdDate = null;
        if (request.submission != null && request.submission.actions != null) {
            for (ActionDto action : request.submission.actions) {
                if (action.type != null) {
                    actionTypes.add(action.type.toUpperCase());
                }
                if ("HOLD".equalsIgnoreCase(action.type) && action.holdUntilDate != null) {
                    holdDate = LocalDate.parse(action.holdUntilDate);
                }
            }
        }
        if (actionTypes.isEmpty()) {
            actionTypes.add("ADD");
        }
        receipt.actions = actionTypes;

        boolean isValidateOnly = actionTypes.contains("VALIDATE");
        boolean hasErrors = false;

        // Validate alias uniqueness and required fields
        if (request.submission == null || request.submission.alias == null
                || request.submission.alias.isBlank()) {
            receipt.messages.addError("Submission alias is required.");
            hasErrors = true;
        }

        // Process projects (studies)
        receipt.projects = new ArrayList<>();
        if (request.projects != null) {
            for (ProjectDto dto : request.projects) {
                if (dto.alias == null || dto.alias.isBlank()) {
                    receipt.messages.addError("Project alias is required.");
                    hasErrors = true;
                    continue;
                }
                if (!isValidateOnly) {
                    Study existing = Study.findByUserIdAndAlias(userId, dto.alias);
                    if (existing != null && actionTypes.contains("ADD")) {
                        receipt.messages.addError(
                            "Project alias '" + dto.alias + "' already exists.");
                        hasErrors = true;
                        continue;
                    }
                    Study study = existing != null ? existing : new Study();
                    study.userId = userId;
                    study.alias = dto.alias;
                    study.title = dto.title;
                    study.description = dto.description;
                    study.status = SubmissionStatus.SUBMITTED;
                    study.holdUntilDate = holdDate;
                    study.contentJson = toJson(dto);
                    if (existing == null) {
                        study.createdAt = Instant.now();
                    }
                    study.updatedAt = Instant.now();
                    study.persist();
                }
                // Accession is null — issued after curator approval
                receipt.projects.add(new ReceiptObject(dto.alias, null, "SUBMITTED"));
            }
        }

        // Process samples
        receipt.samples = new ArrayList<>();
        if (request.samples != null) {
            for (SampleDto dto : request.samples) {
                if (dto.alias == null || dto.alias.isBlank()) {
                    receipt.messages.addError("Sample alias is required.");
                    hasErrors = true;
                    continue;
                }
                if (!isValidateOnly) {
                    Sample existing = Sample.findByUserIdAndAlias(userId, dto.alias);
                    if (existing != null && actionTypes.contains("ADD")) {
                        receipt.messages.addError(
                            "Sample alias '" + dto.alias + "' already exists.");
                        hasErrors = true;
                        continue;
                    }
                    Sample sample = existing != null ? existing : new Sample();
                    sample.userId = userId;
                    sample.alias = dto.alias;
                    sample.title = dto.title;
                    if (dto.sampleName != null) {
                        sample.taxonId = dto.sampleName.taxonId;
                    }
                    sample.status = SubmissionStatus.SUBMITTED;
                    sample.holdUntilDate = holdDate;
                    sample.contentJson = toJson(dto);
                    if (existing == null) {
                        sample.createdAt = Instant.now();
                    }
                    sample.updatedAt = Instant.now();
                    sample.persist();
                }
                receipt.samples.add(new ReceiptObject(dto.alias, null, "SUBMITTED"));
            }
        }

        // Process experiments
        receipt.experiments = new ArrayList<>();
        if (request.experiments != null) {
            for (ExperimentDto dto : request.experiments) {
                if (dto.alias == null || dto.alias.isBlank()) {
                    receipt.messages.addError("Experiment alias is required.");
                    hasErrors = true;
                    continue;
                }
                if (!isValidateOnly) {
                    Experiment existing = Experiment.findByUserIdAndAlias(userId, dto.alias);
                    if (existing != null && actionTypes.contains("ADD")) {
                        receipt.messages.addError(
                            "Experiment alias '" + dto.alias + "' already exists.");
                        hasErrors = true;
                        continue;
                    }
                    Experiment exp = existing != null ? existing : new Experiment();
                    exp.userId = userId;
                    exp.alias = dto.alias;
                    exp.title = dto.title;
                    if (dto.studyRef != null) {
                        exp.studyRef = dto.studyRef.accession != null
                            ? dto.studyRef.accession : dto.studyRef.refname;
                    }
                    exp.status = SubmissionStatus.SUBMITTED;
                    exp.contentJson = toJson(dto);
                    if (existing == null) {
                        exp.createdAt = Instant.now();
                    }
                    exp.updatedAt = Instant.now();
                    exp.persist();
                }
                receipt.experiments.add(new ReceiptObject(dto.alias, null, "SUBMITTED"));
            }
        }

        // Process runs
        receipt.runs = new ArrayList<>();
        if (request.runs != null) {
            for (RunDto dto : request.runs) {
                if (dto.alias == null || dto.alias.isBlank()) {
                    receipt.messages.addError("Run alias is required.");
                    hasErrors = true;
                    continue;
                }
                if (!isValidateOnly) {
                    Run existing = Run.findByUserIdAndAlias(userId, dto.alias);
                    if (existing != null && actionTypes.contains("ADD")) {
                        receipt.messages.addError(
                            "Run alias '" + dto.alias + "' already exists.");
                        hasErrors = true;
                        continue;
                    }
                    Run run = existing != null ? existing : new Run();
                    run.userId = userId;
                    run.alias = dto.alias;
                    if (dto.experimentRef != null) {
                        run.experimentRef = dto.experimentRef.accession != null
                            ? dto.experimentRef.accession : dto.experimentRef.refname;
                    }
                    run.status = SubmissionStatus.SUBMITTED;
                    run.contentJson = toJson(dto);
                    if (existing == null) {
                        run.createdAt = Instant.now();
                    }
                    run.updatedAt = Instant.now();
                    run.persist();
                }
                receipt.runs.add(new ReceiptObject(dto.alias, null, "SUBMITTED"));
            }
        }

        // Process analyses
        receipt.analyses = new ArrayList<>();
        if (request.analyses != null) {
            for (AnalysisDto dto : request.analyses) {
                if (dto.alias == null || dto.alias.isBlank()) {
                    receipt.messages.addError("Analysis alias is required.");
                    hasErrors = true;
                    continue;
                }
                if (!isValidateOnly) {
                    Analysis existing = Analysis.findByUserIdAndAlias(userId, dto.alias);
                    if (existing != null && actionTypes.contains("ADD")) {
                        receipt.messages.addError(
                            "Analysis alias '" + dto.alias + "' already exists.");
                        hasErrors = true;
                        continue;
                    }
                    Analysis analysis = existing != null ? existing : new Analysis();
                    analysis.userId = userId;
                    analysis.alias = dto.alias;
                    if (dto.studyRef != null) {
                        analysis.studyRef = dto.studyRef.accession != null
                            ? dto.studyRef.accession : dto.studyRef.refname;
                    }
                    if (dto.sampleRef != null) {
                        analysis.sampleRef = dto.sampleRef.accession != null
                            ? dto.sampleRef.accession : dto.sampleRef.refname;
                    }
                    if (dto.analysisType != null && !dto.analysisType.isEmpty()) {
                        analysis.analysisType = dto.analysisType.keySet().iterator().next();
                    }
                    analysis.status = SubmissionStatus.SUBMITTED;
                    analysis.contentJson = toJson(dto);
                    if (existing == null) {
                        analysis.createdAt = Instant.now();
                    }
                    analysis.updatedAt = Instant.now();
                    analysis.persist();
                }
                receipt.analyses.add(new ReceiptObject(dto.alias, null, "SUBMITTED"));
            }
        }

        // Create submission ticket
        if (!isValidateOnly && !hasErrors) {
            String submissionId = "DRA" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8).toUpperCase();
            SubmissionTicket ticket = new SubmissionTicket();
            ticket.submissionId = submissionId;
            ticket.userId = userId;
            ticket.alias = request.submission != null ? request.submission.alias : null;
            ticket.status = SubmissionStatus.SUBMITTED;
            ticket.actions = String.join(",", actionTypes);
            ticket.requestJson = toJson(request);
            ticket.createdAt = Instant.now();
            ticket.persist();

            receipt.submission = new ReceiptObject(
                request.submission != null ? request.submission.alias : null,
                submissionId, "SUBMITTED");
        }

        receipt.success = !hasErrors;
        if (!hasErrors) {
            if (isValidateOnly) {
                receipt.messages.addInfo("Validation successful.");
            } else {
                receipt.messages.addInfo("Submission has been committed.");
            }
        }

        return receipt;
    }

    /**
     * Create an async submission ticket (for /submit/queue).
     * Returns submission ID for polling. Processing happens asynchronously.
     */
    @Transactional
    public SubmissionTicket createAsyncTicket(String userId, SubmissionRequest request) {
        String submissionId = "DRA" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 8).toUpperCase();

        SubmissionTicket ticket = new SubmissionTicket();
        ticket.submissionId = submissionId;
        ticket.userId = userId;
        ticket.alias = request.submission != null ? request.submission.alias : null;
        ticket.status = SubmissionStatus.SUBMITTED;
        ticket.requestJson = toJson(request);
        ticket.createdAt = Instant.now();

        List<String> actionTypes = new ArrayList<>();
        if (request.submission != null && request.submission.actions != null) {
            for (ActionDto action : request.submission.actions) {
                if (action.type != null) {
                    actionTypes.add(action.type.toUpperCase());
                }
            }
        }
        ticket.actions = actionTypes.isEmpty() ? "ADD" : String.join(",", actionTypes);
        ticket.persist();

        LOG.info("Async submission ticket created: " + submissionId + " for user " + userId);
        return ticket;
    }

    /**
     * Get async submission status by submissionId.
     */
    public SubmissionTicket getTicket(String submissionId, String userId) {
        SubmissionTicket ticket = SubmissionTicket.findBySubmissionId(submissionId);
        if (ticket != null && ticket.userId.equals(userId)) {
            return ticket;
        }
        return null;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            LOG.warning("Failed to serialize to JSON: " + e.getMessage());
            return "{}";
        }
    }
}
