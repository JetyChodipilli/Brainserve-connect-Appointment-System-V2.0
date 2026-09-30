package com.brainserve.appointment.iam.api;

import java.util.List;
import java.util.UUID;

public interface StaffDirectory {
    List<String> hrApprovalRecipients();
    List<String> hrApprovalRecipients(UUID hostEmployeeId);
    List<String> ceoApprovalRecipients();
}
