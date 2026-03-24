package com.scivicslab.submissionportal.dto;

/**
 * A single object entry in a submission receipt.
 */
public class ReceiptObject {
    public String alias;
    public String accession;
    public String status;
    public String holdUntilDate;

    public ReceiptObject() {}

    public ReceiptObject(String alias, String accession, String status) {
        this.alias = alias;
        this.accession = accession;
        this.status = status;
    }
}
