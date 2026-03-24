package com.scivicslab.submissionportal.dto;

/**
 * ENA-compatible submission action.
 * Types: ADD, MODIFY, HOLD, RELEASE, CANCEL, VALIDATE
 */
public class ActionDto {
    public String type;
    public String holdUntilDate;
}
