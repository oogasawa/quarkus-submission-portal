package com.scivicslab.submissionportal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.scivicslab.submissionportal.dto.ReceiptObject;

/**
 * Unit tests for ReceiptObject DTO.
 */
class ReceiptObjectTest {

    @Test
    void constructorSetsFields() {
        ReceiptObject obj = new ReceiptObject("my-alias", "PRJDB12345", "SUBMITTED");
        assertEquals("my-alias", obj.alias);
        assertEquals("PRJDB12345", obj.accession);
        assertEquals("SUBMITTED", obj.status);
    }

    @Test
    void noArgConstructorCreatesEmptyObject() {
        ReceiptObject obj = new ReceiptObject();
        assertNull(obj.alias);
        assertNull(obj.accession);
        assertNull(obj.status);
    }

    @Test
    void accessionCanBeNull() {
        // DDBJ workflow: accession is null until curator approves
        ReceiptObject obj = new ReceiptObject("my-alias", null, "SUBMITTED");
        assertEquals("my-alias", obj.alias);
        assertNull(obj.accession);
    }
}
