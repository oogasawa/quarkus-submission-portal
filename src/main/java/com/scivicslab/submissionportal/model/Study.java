package com.scivicslab.submissionportal.model;

import java.time.Instant;
import java.time.LocalDate;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "study",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "alias"}))
@SequenceGenerator(name = "study_seq", sequenceName = "study_seq", allocationSize = 1)
public class Study extends PanacheEntity {

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "alias", nullable = false)
    public String alias;

    @Column(name = "accession")
    public String accession;

    @Column(name = "title")
    public String title;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public SubmissionStatus status = SubmissionStatus.DRAFT;

    @Column(name = "hold_until_date")
    public LocalDate holdUntilDate;

    /** Full submitted JSON stored for faithful round-trip */
    @Column(name = "content_json", columnDefinition = "TEXT")
    public String contentJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    public static java.util.List<Study> findByUserId(String userId) {
        return list("userId", io.quarkus.panache.common.Sort.by("createdAt").descending(), userId);
    }

    public static Study findByUserIdAndAlias(String userId, String alias) {
        return find("userId = ?1 and alias = ?2", userId, alias).firstResult();
    }

    public static Study findByAccession(String accession) {
        return find("accession", accession).firstResult();
    }
}
