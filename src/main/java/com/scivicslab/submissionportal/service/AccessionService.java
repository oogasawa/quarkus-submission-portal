package com.scivicslab.submissionportal.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Generates DDBJ-format INSDC accession numbers.
 * Prefix conventions: D = DDBJ (E = ENA, S = NCBI).
 *
 * In production this should use a persistent sequence (DB sequence or distributed ID).
 * Current implementation uses in-memory atomic counters for prototyping.
 */
@ApplicationScoped
public class AccessionService {

    private static final Logger LOG = Logger.getLogger(AccessionService.class.getName());

    // In-memory counters — replace with DB sequences in production
    private final AtomicLong projectSeq = new AtomicLong(100000);
    private final AtomicLong sampleSeq = new AtomicLong(100000);
    private final AtomicLong experimentSeq = new AtomicLong(100000);
    private final AtomicLong runSeq = new AtomicLong(100000);
    private final AtomicLong analysisSeq = new AtomicLong(100000);

    /** Generate Study/Project accession: PRJDB + number */
    public String nextProjectAccession() {
        String acc = "PRJDB" + projectSeq.getAndIncrement();
        LOG.info("Issued project accession: " + acc);
        return acc;
    }

    /** Generate Sample accession: SAMD + 00 + number */
    public String nextSampleAccession() {
        String acc = "SAMD00" + sampleSeq.getAndIncrement();
        LOG.info("Issued sample accession: " + acc);
        return acc;
    }

    /** Generate Experiment accession: DRX + number */
    public String nextExperimentAccession() {
        String acc = "DRX" + String.format("%06d", experimentSeq.getAndIncrement());
        LOG.info("Issued experiment accession: " + acc);
        return acc;
    }

    /** Generate Run accession: DRR + number */
    public String nextRunAccession() {
        String acc = "DRR" + String.format("%06d", runSeq.getAndIncrement());
        LOG.info("Issued run accession: " + acc);
        return acc;
    }

    /** Generate Analysis accession: DRZ + number */
    public String nextAnalysisAccession() {
        String acc = "DRZ" + String.format("%06d", analysisSeq.getAndIncrement());
        LOG.info("Issued analysis accession: " + acc);
        return acc;
    }
}
