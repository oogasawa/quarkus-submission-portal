package com.scivicslab.submissionportal.model;

/**
 * Lifecycle status for INSDC submission objects.
 *
 * DRAFT       → saved but not yet submitted
 * SUBMITTED   → submitted, awaiting validation
 * VALIDATING  → validation in progress
 * CURATING    → passed validation, awaiting curator review
 * APPROVED    → curator approved, accession issued
 * REJECTED    → curator rejected (submitter must fix and resubmit)
 * PRIVATE     → approved and held until release date
 * PUBLIC      → released to public
 * SUPPRESSED  → temporarily hidden by submitter or curator
 * CANCELLED   → withdrawn before release
 */
public enum SubmissionStatus {
    DRAFT,
    SUBMITTED,
    VALIDATING,
    CURATING,
    APPROVED,
    REJECTED,
    PRIVATE,
    PUBLIC,
    SUPPRESSED,
    CANCELLED
}
