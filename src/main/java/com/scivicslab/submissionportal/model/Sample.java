package com.scivicslab.submissionportal.model;

import java.time.Instant;
import java.time.LocalDate;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "sample",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "alias"}))
@SequenceGenerator(name = "sample_seq", sequenceName = "sample_seq", allocationSize = 1)
public class Sample extends PanacheEntity {

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "alias", nullable = false)
    public String alias;

    @Column(name = "accession")
    public String accession;

    @Column(name = "title")
    public String title;

    @Column(name = "taxon_id")
    public Long taxonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public SubmissionStatus status = SubmissionStatus.DRAFT;

    @Column(name = "hold_until_date")
    public LocalDate holdUntilDate;

    @Column(name = "content_json", columnDefinition = "TEXT")
    public String contentJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    public static java.util.List<Sample> findByUserId(String userId) {
        return list("userId", io.quarkus.panache.common.Sort.by("createdAt").descending(), userId);
    }

    public static Sample findByUserIdAndAlias(String userId, String alias) {
        return find("userId = ?1 and alias = ?2", userId, alias).firstResult();
    }

    public static Sample findByAccession(String accession) {
        return find("accession", accession).firstResult();
    }
}
