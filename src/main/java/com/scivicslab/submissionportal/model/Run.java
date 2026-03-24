package com.scivicslab.submissionportal.model;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "run",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "alias"}))
@SequenceGenerator(name = "run_seq", sequenceName = "run_seq", allocationSize = 1)
public class Run extends PanacheEntity {

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "alias", nullable = false)
    public String alias;

    @Column(name = "accession")
    public String accession;

    /** Reference to Experiment (by accession or alias) */
    @Column(name = "experiment_ref")
    public String experimentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public SubmissionStatus status = SubmissionStatus.DRAFT;

    @Column(name = "content_json", columnDefinition = "TEXT")
    public String contentJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    public static java.util.List<Run> findByUserId(String userId) {
        return list("userId", io.quarkus.panache.common.Sort.by("createdAt").descending(), userId);
    }

    public static Run findByUserIdAndAlias(String userId, String alias) {
        return find("userId = ?1 and alias = ?2", userId, alias).firstResult();
    }

    public static Run findByAccession(String accession) {
        return find("accession", accession).firstResult();
    }
}
