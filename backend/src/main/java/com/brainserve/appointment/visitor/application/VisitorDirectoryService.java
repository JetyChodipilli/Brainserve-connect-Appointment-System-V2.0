package com.brainserve.appointment.visitor.application;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.visitor.api.VisitorDirectory;
import com.brainserve.appointment.visitor.infrastructure.VisitorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class VisitorDirectoryService implements VisitorDirectory {
    private final VisitorRepository visitors;

    public VisitorDirectoryService(VisitorRepository visitors) {
        this.visitors = visitors;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireVisitor(UUID visitorId) {
        if (!visitors.existsById(visitorId)) {
            throw new BusinessException("VISITOR_NOT_FOUND", "Visitor was not found", HttpStatus.NOT_FOUND);
        }
    }
}
