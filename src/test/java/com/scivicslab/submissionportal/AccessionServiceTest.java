package com.scivicslab.submissionportal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.scivicslab.submissionportal.service.AccessionService;

/**
 * Unit tests for AccessionService — accession number generation.
 */
class AccessionServiceTest {

    private AccessionService service;

    @BeforeEach
    void setUp() {
        service = new AccessionService();
    }

    @Test
    void projectAccessionHasDdbjPrefix() {
        String acc = service.nextProjectAccession();
        assertTrue(acc.startsWith("PRJDB"), "Project accession must start with PRJDB");
    }

    @Test
    void sampleAccessionHasDdbjPrefix() {
        String acc = service.nextSampleAccession();
        assertTrue(acc.startsWith("SAMD00"), "Sample accession must start with SAMD00");
    }

    @Test
    void experimentAccessionHasDdbjPrefix() {
        String acc = service.nextExperimentAccession();
        assertTrue(acc.startsWith("DRX"), "Experiment accession must start with DRX");
    }

    @Test
    void runAccessionHasDdbjPrefix() {
        String acc = service.nextRunAccession();
        assertTrue(acc.startsWith("DRR"), "Run accession must start with DRR");
    }

    @Test
    void analysisAccessionHasDdbjPrefix() {
        String acc = service.nextAnalysisAccession();
        assertTrue(acc.startsWith("DRZ"), "Analysis accession must start with DRZ");
    }

    @Test
    void consecutiveAccessionsAreUnique() {
        String acc1 = service.nextProjectAccession();
        String acc2 = service.nextProjectAccession();
        assertNotEquals(acc1, acc2, "Consecutive accessions must be unique");
    }

    @Test
    void allTypesGenerateDistinctAccessions() {
        String project = service.nextProjectAccession();
        String sample = service.nextSampleAccession();
        String experiment = service.nextExperimentAccession();
        String run = service.nextRunAccession();
        String analysis = service.nextAnalysisAccession();

        // All should be distinct due to different prefixes
        assertNotEquals(project, sample);
        assertNotEquals(project, experiment);
        assertNotEquals(project, run);
        assertNotEquals(project, analysis);
    }
}
