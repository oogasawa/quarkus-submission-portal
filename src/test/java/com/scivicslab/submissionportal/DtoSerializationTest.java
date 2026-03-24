package com.scivicslab.submissionportal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.scivicslab.submissionportal.dto.*;

/**
 * Unit tests for DTO JSON serialization/deserialization.
 * Verifies ENA-compatible JSON structure round-trips correctly.
 */
class DtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void submissionRequestRoundTrip() throws Exception {
        String json = """
            {
              "submission": {
                "alias": "test-sub-001",
                "actions": [
                  {"type": "ADD"},
                  {"type": "HOLD", "holdUntilDate": "2027-03-20"}
                ]
              },
              "projects": [
                {
                  "alias": "test-study-001",
                  "title": "Test Study",
                  "description": "A test study"
                }
              ],
              "samples": [
                {
                  "alias": "test-sample-001",
                  "title": "Test Sample",
                  "sampleName": {"taxonId": 9606}
                }
              ]
            }
            """;

        SubmissionRequest req = mapper.readValue(json, SubmissionRequest.class);

        assertNotNull(req.submission);
        assertEquals("test-sub-001", req.submission.alias);
        assertEquals(2, req.submission.actions.size());
        assertEquals("ADD", req.submission.actions.get(0).type);
        assertEquals("HOLD", req.submission.actions.get(1).type);
        assertEquals("2027-03-20", req.submission.actions.get(1).holdUntilDate);

        assertEquals(1, req.projects.size());
        assertEquals("test-study-001", req.projects.get(0).alias);
        assertEquals("Test Study", req.projects.get(0).title);

        assertEquals(1, req.samples.size());
        assertEquals("test-sample-001", req.samples.get(0).alias);
        assertEquals(9606L, req.samples.get(0).sampleName.taxonId);
    }

    @Test
    void experimentDtoWithNestedObjects() throws Exception {
        String json = """
            {
              "alias": "test-exp-001",
              "title": "WGS experiment",
              "studyRef": {"accession": "PRJDB12345"},
              "design": {
                "sampleDescriptor": {"accession": "SAMD00123"},
                "libraryDescriptor": {
                  "libraryStrategy": "WGS",
                  "librarySource": "GENOMIC"
                }
              },
              "platform": {
                "illumina": {"instrumentModel": "Illumina NovaSeq 6000"}
              }
            }
            """;

        ExperimentDto dto = mapper.readValue(json, ExperimentDto.class);
        assertEquals("test-exp-001", dto.alias);
        assertEquals("PRJDB12345", dto.studyRef.accession);
        assertNotNull(dto.design);
        assertNotNull(dto.platform);
    }

    @Test
    void runDtoWithDataBlock() throws Exception {
        String json = """
            {
              "alias": "test-run-001",
              "experimentRef": {"refname": "test-exp-001"},
              "dataBlock": {
                "files": [
                  {
                    "filename": "sample1_R1.fastq.gz",
                    "filetype": "fastq",
                    "checksumMethod": "MD5",
                    "checksum": "d41d8cd98f00b204e9800998ecf8427e"
                  }
                ]
              }
            }
            """;

        RunDto dto = mapper.readValue(json, RunDto.class);
        assertEquals("test-run-001", dto.alias);
        assertEquals("test-exp-001", dto.experimentRef.refname);
        assertNull(dto.experimentRef.accession);
        assertNotNull(dto.dataBlock);
    }

    @Test
    void analysisDtoWithFiles() throws Exception {
        String json = """
            {
              "alias": "test-analysis-001",
              "studyRef": {"accession": "PRJDB12345"},
              "sampleRef": {"accession": "SAMD00123"},
              "analysisType": {
                "sequenceAssembly": {
                  "name": "Test assembly",
                  "partial": false
                }
              },
              "files": [
                {
                  "filename": "assembly.fasta.gz",
                  "filetype": "fasta",
                  "checksumMethod": "MD5",
                  "checksum": "e99a18c428cb38d5f260853678922e03"
                }
              ]
            }
            """;

        AnalysisDto dto = mapper.readValue(json, AnalysisDto.class);
        assertEquals("test-analysis-001", dto.alias);
        assertEquals("PRJDB12345", dto.studyRef.accession);
        assertEquals(1, dto.files.size());
        assertEquals("assembly.fasta.gz", dto.files.get(0).filename);
    }

    @Test
    void submissionReceiptSerialization() throws Exception {
        SubmissionReceipt receipt = new SubmissionReceipt();
        receipt.success = true;
        receipt.receiptDate = "2026-03-20T10:00:00Z";
        receipt.submission = new ReceiptObject("my-sub", "DRA12345678", "SUBMITTED");
        receipt.projects = List.of(new ReceiptObject("my-study", null, "SUBMITTED"));
        receipt.samples = List.of(new ReceiptObject("my-sample", null, "SUBMITTED"));
        receipt.messages = new ReceiptMessages();
        receipt.messages.addInfo("Submission has been committed.");
        receipt.actions = List.of("ADD", "HOLD");

        String json = mapper.writeValueAsString(receipt);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"DRA12345678\""));
        assertTrue(json.contains("Submission has been committed."));

        // Deserialize back
        SubmissionReceipt parsed = mapper.readValue(json, SubmissionReceipt.class);
        assertTrue(parsed.success);
        assertEquals("DRA12345678", parsed.submission.accession);
    }

    @Test
    void asyncSubmissionResponse() throws Exception {
        AsyncSubmissionResponse resp = new AsyncSubmissionResponse(
            "DRA12345678", "testuser",
            "https://host/api/v1/submit/poll/DRA12345678");

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"submissionId\":\"DRA12345678\""));
        assertTrue(json.contains("\"_links\""));
        assertTrue(json.contains("\"poll\""));

        AsyncSubmissionResponse parsed = mapper.readValue(json, AsyncSubmissionResponse.class);
        assertEquals("DRA12345678", parsed.submissionId);
        assertEquals("testuser", parsed.submissionAccountId);
    }

    @Test
    void enaFullRequestParsesCorrectly() throws Exception {
        // Full ENA-style request from the design doc
        String json = """
            {
              "submission": {
                "alias": "my-submission-001",
                "actions": [
                  {"type": "ADD"},
                  {"type": "HOLD", "holdUntilDate": "2027-03-20"}
                ]
              },
              "projects": [{
                "alias": "my-study-001",
                "title": "Genome sequencing of Oryza sativa",
                "description": "Whole genome sequencing of rice cultivar Nipponbare",
                "submissionProject": {"sequencingProject": {}}
              }],
              "samples": [{
                "alias": "my-sample-001",
                "title": "Rice leaf sample",
                "sampleName": {"taxonId": 39947},
                "sampleAttributes": [
                  {"tag": "ENA-CHECKLIST", "value": "ERC000011"},
                  {"tag": "collection date", "value": "2026-01"},
                  {"tag": "geographic location (country and/or sea)", "value": "Japan"}
                ]
              }],
              "experiments": [{
                "alias": "my-experiment-001",
                "title": "WGS of rice",
                "studyRef": {"accession": "PRJDB12345"},
                "design": {
                  "sampleDescriptor": {"accession": "SAMD00123456"},
                  "libraryDescriptor": {
                    "libraryStrategy": "WGS",
                    "librarySource": "GENOMIC",
                    "librarySelection": "RANDOM",
                    "libraryLayout": {"paired": {"nominalLength": 250}}
                  }
                },
                "platform": {"illumina": {"instrumentModel": "Illumina NovaSeq 6000"}}
              }],
              "runs": [{
                "alias": "my-run-001",
                "experimentRef": {"refname": "my-experiment-001"},
                "dataBlock": {
                  "files": [
                    {"filename": "sample1_R1.fastq.gz", "filetype": "fastq",
                     "checksumMethod": "MD5", "checksum": "d41d8cd98f00b204e9800998ecf8427e"},
                    {"filename": "sample1_R2.fastq.gz", "filetype": "fastq",
                     "checksumMethod": "MD5", "checksum": "a87ff679a2f3e71d9181a67b7542122c"}
                  ]
                }
              }],
              "analyses": [{
                "alias": "my-analysis-001",
                "studyRef": {"accession": "PRJDB12345"},
                "sampleRef": {"accession": "SAMD00123456"},
                "analysisType": {
                  "sequenceAssembly": {
                    "name": "Rice genome assembly",
                    "partial": false,
                    "coverage": "50x",
                    "program": "SPAdes 3.15"
                  }
                },
                "files": [{
                  "filename": "assembly.fasta.gz",
                  "filetype": "fasta",
                  "checksumMethod": "MD5",
                  "checksum": "e99a18c428cb38d5f260853678922e03"
                }]
              }]
            }
            """;

        SubmissionRequest req = mapper.readValue(json, SubmissionRequest.class);
        assertEquals("my-submission-001", req.submission.alias);
        assertEquals(1, req.projects.size());
        assertEquals(1, req.samples.size());
        assertEquals(3, req.samples.get(0).sampleAttributes.size());
        assertEquals(1, req.experiments.size());
        assertEquals(1, req.runs.size());
        assertEquals(1, req.analyses.size());
        assertEquals(1, req.analyses.get(0).files.size());
    }
}
