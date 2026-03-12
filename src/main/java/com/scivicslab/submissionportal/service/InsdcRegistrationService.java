package com.scivicslab.submissionportal.service;

import java.time.Instant;
import java.util.List;

import com.scivicslab.submissionportal.model.InsdcRegistration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class InsdcRegistrationService {

    public List<InsdcRegistration> getUserRegistrations(String userId) {
        return InsdcRegistration.findByUserId(userId);
    }

    @Transactional
    public InsdcRegistration createRegistration(String userId, String accession,
                                                  String submissionType, String title) {
        InsdcRegistration reg = new InsdcRegistration();
        reg.userId = userId;
        reg.accession = accession;
        reg.submissionType = submissionType;
        reg.title = title;
        reg.status = "SUBMITTED";
        reg.submittedAt = Instant.now();
        reg.persist();
        return reg;
    }

    @Transactional
    public void updateStatus(Long id, String status) {
        InsdcRegistration reg = InsdcRegistration.findById(id);
        if (reg != null) {
            reg.status = status;
            reg.updatedAt = Instant.now();
            reg.persist();
        }
    }
}
