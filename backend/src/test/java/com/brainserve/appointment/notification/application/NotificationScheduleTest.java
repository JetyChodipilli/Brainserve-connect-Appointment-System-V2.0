package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.domain.InternalCallNotification;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationScheduleTest {
    private Instant due(String created,String cadence,String zone,boolean quiet,String start,String end) {
        return NotificationSchedule.due(Instant.parse(created),cadence,ZoneId.of(zone),quiet,LocalTime.parse(start),LocalTime.parse(end));
    }
    @Test void overnightAndSameDayQuietHoursIncludeStartAndExcludeEnd() {
        assertThat(due("2026-10-07T17:00:00Z","IMMEDIATE","Asia/Kolkata",true,"22:00","08:00")).isEqualTo("2026-10-08T02:30:00Z");
        assertThat(due("2026-10-08T02:30:00Z","IMMEDIATE","Asia/Kolkata",true,"22:00","08:00")).isEqualTo("2026-10-08T02:30:00Z");
        assertThat(due("2026-10-07T11:00:00Z","IMMEDIATE","UTC",true,"10:00","14:00")).isEqualTo("2026-10-07T14:00:00Z");
    }
    @Test void digestCadenceIsAnchoredToCreationInsteadOfAdvancingOnEveryRetry() {
        assertThat(due("2026-10-07T12:31:10Z","HOURLY","UTC",false,"22:00","08:00")).isEqualTo("2026-10-07T13:00:00Z");
        assertThat(due("2026-10-07T12:31:10Z","DAILY","Asia/Kolkata",false,"22:00","08:00")).isEqualTo("2026-10-08T03:30:00Z");
        assertThat(due("2026-10-07T02:31:10Z","DAILY","Asia/Kolkata",false,"22:00","08:00")).isEqualTo("2026-10-07T03:30:00Z");
        assertThat(due("2026-10-07T12:31:10Z","HOURLY","UTC",true,"13:00","15:00")).isEqualTo("2026-10-07T15:00:00Z");
    }
    @Test void daylightSavingGapAndOverlapReleaseAtARealEndBoundary() {
        assertThat(due("2024-03-10T06:15:00Z","IMMEDIATE","America/New_York",true,"01:00","02:30")).isEqualTo("2024-03-10T07:30:00Z");
        assertThat(due("2024-11-03T05:15:00Z","IMMEDIATE","America/New_York",true,"00:30","01:30")).isEqualTo("2024-11-03T06:30:00Z");
        assertThat(due("2024-11-03T06:15:00Z","IMMEDIATE","America/New_York",true,"00:30","01:30")).isEqualTo("2024-11-03T06:30:00Z");
        assertThat(due("2024-11-03T05:45:00Z","IMMEDIATE","America/New_York",true,"00:30","01:30")).isEqualTo("2024-11-03T06:30:00Z");
    }
    @Test void mandatoryAccountSecurityAndReviewMessagesAreCentrallyClassified() {
        for(var category : java.util.List.of(InternalCallNotification.MessageCategory.SECURITY,InternalCallNotification.MessageCategory.APPROVAL,
                InternalCallNotification.MessageCategory.ESCALATION,InternalCallNotification.MessageCategory.ACTION_REQUIRED,InternalCallNotification.MessageCategory.VISITOR))
            assertThat(message(category,InternalCallNotification.MessagePriority.NORMAL).isMandatory()).isTrue();
        assertThat(message(InternalCallNotification.MessageCategory.WORK,InternalCallNotification.MessagePriority.HIGH).isMandatory()).isTrue();
        assertThat(message(InternalCallNotification.MessageCategory.GENERAL,InternalCallNotification.MessagePriority.URGENT).isMandatory()).isTrue();
        assertThat(message(InternalCallNotification.MessageCategory.WORK,InternalCallNotification.MessagePriority.NORMAL).isMandatory()).isFalse();
    }
    private InternalCallNotification message(InternalCallNotification.MessageCategory category,InternalCallNotification.MessagePriority priority) {
        return new InternalCallNotification(UUID.randomUUID(),UUID.randomUUID(),"Sender","Recipient","Policy test",priority,category);
    }
}
