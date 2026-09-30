package com.brainserve.appointment.visitor.api;

import java.util.UUID;

public interface VisitorDirectory {
    void requireVisitor(UUID visitorId);
}
