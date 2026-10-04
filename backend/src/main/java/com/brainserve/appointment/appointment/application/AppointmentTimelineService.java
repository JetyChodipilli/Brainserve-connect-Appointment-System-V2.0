package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.api.AppointmentSearch;
import com.brainserve.appointment.audit.api.ActivityHistory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AppointmentTimelineService {
    private final AppointmentSearch appointments;
    private final ActivityHistory history;
    public AppointmentTimelineService(AppointmentSearch appointments,ActivityHistory history){this.appointments=appointments;this.history=history;}
    @Transactional(readOnly=true)
    public ActivityHistory.Page timeline(UUID actor,UUID id,int page,int size) {
        var before=appointments.visible(actor,id);var result=history.appointment(id,page,size);
        if(!before.equals(appointments.visible(actor,id)))throw new BusinessException("APPOINTMENT_VERSION_CONFLICT","This visit changed. Reload its activity",HttpStatus.CONFLICT);
        return result;
    }
}
