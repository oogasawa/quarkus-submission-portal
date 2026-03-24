package com.scivicslab.submissionportal.model;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "analysis",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "alias"}))
@SequenceGenerator(name = "analysis_seq", sequenceName = "analysis_seq", allocationSize = 1)
public class Analysis extends PanacheEntity {

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "alias", nullable = false)
    public String alias;

    @Column(name = "accession")
    public String accession;

    /** Reference to Study */
    @Column(name = "study_ref")
    public String studyRef;

    /** Reference to Sample */
    @Column(name = "sample_ref")
    public String sampleRef;

    @Column(name = "analysis_type")
    public String analysisType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public SubmissionStatus status = SubmissionStatus.DRAFT;

    @Column(name = "content_json", columnDefinition = "TEXT")
    public String contentJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    public static java.util.List<Analysis> findByUserId(String userId) {
        return list("userId", io.quarkus.panache.common.Sort.by("createdAt").descending(), userId);
    }

    public static Analysis findByUserIdAndAlias(String userId, String alias) {
        return find("userId = ?1 and alias = ?2", userId, alias).firstResult();
    }

    public static Analysis findByAccession(String accession) {
        return find("accession", accession).firstResult();
    }
}
