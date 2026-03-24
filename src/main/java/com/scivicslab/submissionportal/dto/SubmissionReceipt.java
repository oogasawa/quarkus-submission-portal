package com.scivicslab.submissionportal.dto;

import java.util.List;

/**
 * ENA-compatible submission receipt (response).
 */
public class SubmissionReceipt {
    public boolean success;
    public String receiptDate;
    public ReceiptObject submission;
    public List<ReceiptObject> projects;
    public List<ReceiptObject> samples;
    public List<ReceiptObject> experiments;
    public List<ReceiptObject> runs;
    public List<ReceiptObject> analyses;
    public ReceiptMessages messages;
    public List<String> actions;
}
