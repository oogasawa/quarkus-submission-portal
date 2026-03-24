package com.scivicslab.submissionportal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.scivicslab.submissionportal.model.SubmissionStatus;

/**
 * Unit tests for SubmissionStatus enum.
 */
class SubmissionStatusTest {

    @Test
    void allStatusValuesExist() {
        SubmissionStatus[] values = SubmissionStatus.values();
        assertEquals(10, values.length);
    }

    @Test
    void valueOfWorks() {
        assertEquals(SubmissionStatus.DRAFT, SubmissionStatus.valueOf("DRAFT"));
        assertEquals(SubmissionStatus.SUBMITTED, SubmissionStatus.valueOf("SUBMITTED"));
        assertEquals(SubmissionStatus.VALIDATING, SubmissionStatus.valueOf("VALIDATING"));
        assertEquals(SubmissionStatus.CURATING, SubmissionStatus.valueOf("CURATING"));
        assertEquals(SubmissionStatus.APPROVED, SubmissionStatus.valueOf("APPROVED"));
        assertEquals(SubmissionStatus.REJECTED, SubmissionStatus.valueOf("REJECTED"));
        assertEquals(SubmissionStatus.PRIVATE, SubmissionStatus.valueOf("PRIVATE"));
        assertEquals(SubmissionStatus.PUBLIC, SubmissionStatus.valueOf("PUBLIC"));
        assertEquals(SubmissionStatus.SUPPRESSED, SubmissionStatus.valueOf("SUPPRESSED"));
        assertEquals(SubmissionStatus.CANCELLED, SubmissionStatus.valueOf("CANCELLED"));
    }

    @Test
    void invalidStatusThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> SubmissionStatus.valueOf("INVALID"));
    }
}
