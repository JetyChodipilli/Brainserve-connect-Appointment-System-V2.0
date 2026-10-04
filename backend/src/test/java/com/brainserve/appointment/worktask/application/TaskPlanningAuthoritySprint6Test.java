package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.document.api.TaskEvidenceStore;
import com.brainserve.appointment.iam.api.*;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskPlanningAuthoritySprint6Test {
    UUID actor=UUID.randomUUID(), employee=UUID.randomUUID(),dept=UUID.randomUUID(),id=UUID.randomUUID();
    DepartmentWorkTaskRepository tasks=mock(DepartmentWorkTaskRepository.class);CurrentAccountAuthority authority=mock(CurrentAccountAuthority.class);
    EntityManager em=mock(EntityManager.class);TaskEvidenceStore store=mock(TaskEvidenceStore.class);JdbcTemplate jdbc=mock(JdbcTemplate.class);
    StaffCommunicationDirectory staff=mock(StaffCommunicationDirectory.class);
    TaskPlanningService service=new TaskPlanningService(tasks,authority,em,mock(AuditService.class),staff,store,jdbc,new ObjectMapper(),mock(ApplicationEventPublisher.class));
    DepartmentWorkTask task;
    CurrentAccountAuthority.Authority current;CurrentAccountAuthority.WorkScope scope;
    @BeforeEach void fixture() throws Exception {
        task=new DepartmentWorkTask(dept,employee,UUID.randomUUID(),UUID.randomUUID(),"TEAM_LEAD","EMPLOYEE","Delivery","Instructions","Department",LocalDate.now());
        var field=com.brainserve.appointment.shared.domain.AuditableEntity.class.getDeclaredField("id");field.setAccessible(true);field.set(task,id);
        current=new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE",employee,Set.of("WORK_TASK_READ","WORK_TASK_PROGRESS"));scope=new CurrentAccountAuthority.WorkScope(current,dept,null,0,0,0);
        when(authority.requireActive(actor)).thenReturn(current);when(authority.requireWorkScope(actor)).thenReturn(scope);when(tasks.findById(id)).thenReturn(Optional.of(task));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(id))).thenReturn(false);when(jdbc.queryForObject(anyString(),eq(Long.class),eq(id))).thenReturn(0L);
        when(staff.activeWithAnyRoleInDepartment(anySet(),eq(dept),eq(200))).thenReturn(List.of());
    }
    @Test void scopeChangeWhileWaitingForLockIsDeniedBeforeVersionAndNoDocumentWrites() {
        doAnswer(call->{when(authority.requireWorkScope(actor)).thenReturn(new CurrentAccountAuthority.WorkScope(current,UUID.randomUUID(),null,0,0,0));return null;}).when(em).refresh(task,LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(()->service.tick(actor,id,new TaskPlanningService.Tick(999L,List.of()))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_NOT_FOUND");verifyNoInteractions(store);
    }
    @Test void permissionRevocationWhileWaitingForLockFailsClosed() {
        doAnswer(call->{when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE",employee,Set.of("WORK_TASK_READ")));return null;}).when(em).refresh(task,LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(()->service.tick(actor,id,new TaskPlanningService.Tick(0L,List.of()))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_PERMISSION_DENIED");verifyNoInteractions(store);
    }
    @Test void concurrentTaskReassignmentInvalidatesLateReadBeforeItReturnsPlanning() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(id))).thenReturn(1L);
        assertThatThrownBy(()->service.get(actor,id)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_VERSION_CONFLICT");
    }
    @Test void unsupportedRoleDenialDoesNotDependOnTaskExistence() {
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN",null,Set.of("WORK_TASK_READ")));
        assertThatThrownBy(()->service.get(actor,id)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_PERMISSION_DENIED");
        assertThatThrownBy(()->service.get(actor,UUID.randomUUID())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_PERMISSION_DENIED");verifyNoInteractions(tasks);
    }
    @Test void foreignEvidenceIdentifierCannotInvokeStorageEvenWithStaleRevision() {
        when(authority.requireWorkScope(actor)).thenReturn(new CurrentAccountAuthority.WorkScope(current,UUID.randomUUID(),null,0,0,0));
        assertThatThrownBy(()->service.upload(actor,id,99,new org.springframework.mock.web.MockMultipartFile("file",new byte[]{1}))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_NOT_FOUND");verifyNoInteractions(store);
    }
}
