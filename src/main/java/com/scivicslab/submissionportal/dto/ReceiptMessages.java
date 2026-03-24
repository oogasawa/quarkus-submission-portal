package com.scivicslab.submissionportal.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages block in a submission receipt (info/error/warning).
 */
public class ReceiptMessages {
    public List<String> info;
    public List<String> error;

    public ReceiptMessages() {
        this.info = new ArrayList<>();
        this.error = new ArrayList<>();
    }

    public void addInfo(String msg) {
        info.add(msg);
    }

    public void addError(String msg) {
        error.add(msg);
    }
}
