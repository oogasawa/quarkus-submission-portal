package com.scivicslab.submissionportal.model;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "insdc_registration")
@SequenceGenerator(name = "insdc_registration_seq", sequenceName = "insdc_registration_seq", allocationSize = 1)
public class InsdcRegistration extends PanacheEntity {

    @Column(name = "user_id", nullable = false)
    public String userId;

    /** Accession number (e.g. DRA012345, GCA_000001234.1) */
    @Column(name = "accession", nullable = false)
    public String accession;

    /** Submission type: SRA, Assembly, ExpressionData, etc. */
    @Column(name = "submission_type", nullable = false)
    public String submissionType;

    /** Title or short description */
    @Column(name = "title")
    public String title;

    /** Status: SUBMITTED, ACCEPTED, RELEASED, SUPPRESSED */
    @Column(name = "status", nullable = false)
    public String status = "SUBMITTED";

    @Column(name = "submitted_at", nullable = false)
    public Instant submittedAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    public static java.util.List<InsdcRegistration> findByUserId(String userId) {
        return list("userId", io.quarkus.panache.common.Sort.by("submittedAt").descending(), userId);
    }
}
