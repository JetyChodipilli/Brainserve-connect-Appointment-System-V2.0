package com.brainserve.appointment.workinsight.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.*;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.worktask.application.DepartmentWorkTaskService;
import com.brainserve.appointment.worktask.domain.*;
import com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkboardMutationVersionTest {
    final UUID user=UUID.randomUUID(), employee=UUID.randomUUID(), department=UUID.randomUUID(), lead=UUID.randomUUID(), taskId=UUID.randomUUID();
    final DepartmentWorkTaskRepository tasks=mock(DepartmentWorkTaskRepository.class);
    final EmployeeDirectory employees=mock(EmployeeDirectory.class);
    final StaffCommunicationDirectory staff=mock(StaffCommunicationDirectory.class);
    final TeamLeadDirectory leads=mock(TeamLeadDirectory.class);
    final CurrentAccountAuthority authority=mock(CurrentAccountAuthority.class);
    final EntityManager em=mock(EntityManager.class);
    DepartmentWorkTask task; DepartmentWorkTaskService service;
    @BeforeEach void setup() {
        task=new DepartmentWorkTask(department,employee,lead,lead,"TEAM_LEAD","EMPLOYEE","Task","Full task instructions","Department",LocalDate.now());
        ReflectionTestUtils.setField(task,"id",taskId); ReflectionTestUtils.setField(task,"version",4L);
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        when(authority.requireActive(user)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE",employee,Set.of("WORK_TASK_PROGRESS")));
        when(authority.requireWorkScope(any(UUID.class))).thenAnswer(invocation -> new CurrentAccountAuthority.WorkScope(authority.requireActive(invocation.getArgument(0)),department,null,0,0,0));
        when(staff.requireActive(user)).thenReturn(new StaffCommunicationDirectory.StaffMember(user,employee,"Person","person@test",Set.of("ROLE_EMPLOYEE")));
        when(employees.departmentIdForEmployee(employee)).thenReturn(department);
        service=new DepartmentWorkTaskService(tasks,employees,leads,mock(OrganizationDirectory.class),staff,mock(ApplicationEventPublisher.class),mock(AuditService.class),mock(DepartmentHrDirectory.class),mock(ManagerDirectory.class),authority,em);
    }
    @Test void staleObservedRevisionRejectsBeforeAnyStateOrEvidenceChanges() {
        assertThatThrownBy(()->service.complete(user,employee,taskId,"Do not lose my note",3L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_VERSION_CONFLICT");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.ASSIGNED);assertThat(task.getEmployeeUpdate()).isNull();assertThat(task.getSubmissionVersion()).isNull();
        verify(em).refresh(task,LockModeType.PESSIMISTIC_WRITE);verify(tasks,never()).flush();
    }
    @Test void guessedForeignTaskDoesNotDiscloseItsObservedRevision() {
        ReflectionTestUtils.setField(task,"employeeId",UUID.randomUUID());
        assertThatThrownBy(()->service.complete(user,employee,taskId,"private note",3L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_EMPLOYEE_SCOPE_DENIED");
        verify(em,never()).refresh(any(),any(LockModeType.class));
    }
    @Test void matchingRevisionAndLegacyOmissionUseTheSameBusinessWriter() {
        service.start(user,employee,taskId,"Started",4L);
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.IN_PROGRESS);assertThat(task.getSubmissionVersion()).isNull();
        service.complete(user,employee,taskId,"Delivered");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);assertThat(task.getSubmissionVersion()).isEqualTo(1L);
        task.requestChanges("Correct this section");task.complete("Corrected");task.reviseEmployeeReworkSubmission("More detail");
        assertThat(task.getSubmissionVersion()).isEqualTo(3L);
    }
    @Test void staleJwtEmployeeClaimNeverOverridesCurrentAccountLink() {
        UUID former=UUID.randomUUID();
        service.complete(user,former,taskId,"Current employee submission",4L);
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);
        when(authority.requireActive(user)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE",former,Set.of("WORK_TASK_PROGRESS")));
        assertThatThrownBy(()->service.reviseEmployeeRework(user,employee,taskId,"Old claim",4L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_EMPLOYEE_SCOPE_DENIED");
    }
    @Test void authorityRevokedAfterPageCannotMutate() {
        when(authority.requireActive(user)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE",employee,Set.of()));
        assertThatThrownBy(()->service.start(user,employee,taskId,"start",4L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_PERMISSION_DENIED");
        verify(em,never()).refresh(any(),any(LockModeType.class));
    }
    @Test void permissionOrAssignmentChangedWhileWaitingForLockCannotMutateOrExposeRevision() {
        var initial=new CurrentAccountAuthority.WorkScope(authority.requireActive(user),department,null,0,0,0);
        doReturn(initial).when(authority).requireWorkScope(user);
        doAnswer(invocation -> {
            doReturn(new CurrentAccountAuthority.WorkScope(
                    new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE",employee,Set.of()),department,null,0,0,0)).when(authority).requireWorkScope(user);
            return null;
        }).when(em).refresh(task,LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(()->service.complete(user,employee,taskId,"Keep note",3L)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("WORK_TASK_PERMISSION_DENIED");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.ASSIGNED);assertThat(task.getEmployeeUpdate()).isNull();
    }
    @Test void auditOnlyGuardAdvancesTaskRevisionUnderTheSameLock() {
        service.requireTaskForMutation(taskId,4L);
        var order=inOrder(tasks,em);order.verify(tasks).findById(taskId);order.verify(em).refresh(task,LockModeType.PESSIMISTIC_WRITE);order.verify(em).lock(task,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        assertThat(task.getSubmissionVersion()).isNull();
    }
    @Test void teamLeadCannotReviewTheirOwnWorksheetEvenAfterRoleRelink() {
        when(authority.requireActive(lead)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_TEAM_LEAD",employee,Set.of("WORK_TASK_REVIEW")));
        when(leads.requireForUser(lead)).thenReturn(new TeamLeadDirectory.Assignment(UUID.randomUUID(),department,lead,employee,"Lead","lead@test"));
        task.complete("Done");
        assertThatThrownBy(()->service.approve(lead,taskId,"self approve",4L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_SELF_REVIEW_NOT_ALLOWED");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);
    }
}
