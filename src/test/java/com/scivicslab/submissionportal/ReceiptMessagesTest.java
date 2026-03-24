package com.scivicslab.submissionportal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.scivicslab.submissionportal.dto.ReceiptMessages;

/**
 * Unit tests for ReceiptMessages DTO.
 */
class ReceiptMessagesTest {

    @Test
    void newInstanceHasEmptyLists() {
        ReceiptMessages msgs = new ReceiptMessages();
        assertNotNull(msgs.info);
        assertNotNull(msgs.error);
        assertTrue(msgs.info.isEmpty());
        assertTrue(msgs.error.isEmpty());
    }

    @Test
    void addInfoAppendsToInfoList() {
        ReceiptMessages msgs = new ReceiptMessages();
        msgs.addInfo("Submission committed.");
        msgs.addInfo("All objects valid.");
        assertEquals(2, msgs.info.size());
        assertEquals("Submission committed.", msgs.info.get(0));
    }

    @Test
    void addErrorAppendsToErrorList() {
        ReceiptMessages msgs = new ReceiptMessages();
        msgs.addError("Missing alias.");
        assertEquals(1, msgs.error.size());
        assertEquals("Missing alias.", msgs.error.get(0));
    }
}
