package com.scivicslab.submissionportal.model;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

/**
 * Tracks a submission request (sync or async).
 * For async submissions, the client polls using submissionId.
 */
@Entity
@Table(name = "submission_ticket")
@SequenceGenerator(name = "submission_ticket_seq", sequenceName = "submission_ticket_seq", allocationSize = 1)
public class SubmissionTicket extends PanacheEntity {

    @Column(name = "submission_id", nullable = false, unique = true)
    public String submissionId;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "alias")
    public String alias;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public SubmissionStatus status = SubmissionStatus.SUBMITTED;

    /** Stored actions (comma-separated: ADD,HOLD) */
    @Column(name = "actions")
    public String actions;

    /** Receipt JSON (populated when processing completes) */
    @Column(name = "receipt_json", columnDefinition = "TEXT")
    public String receiptJson;

    /** Original request JSON */
    @Column(name = "request_json", columnDefinition = "TEXT")
    public String requestJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    public static SubmissionTicket findBySubmissionId(String submissionId) {
        return find("submissionId", submissionId).firstResult();
    }

    public static java.util.List<SubmissionTicket> findByUserId(String userId) {
        return list("userId", io.quarkus.panache.common.Sort.by("createdAt").descending(), userId);
    }
}
