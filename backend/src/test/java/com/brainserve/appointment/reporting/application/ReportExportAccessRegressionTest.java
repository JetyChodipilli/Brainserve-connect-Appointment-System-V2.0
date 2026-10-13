package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.reporting.api.HistoryDataset;
import com.brainserve.appointment.reporting.domain.ReportExportJob;
import com.brainserve.appointment.reporting.infrastructure.ReportExportJobRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportExportAccessRegressionTest {
    private final UUID actor = UUID.randomUUID(), jobId = UUID.randomUUID(), department = UUID.randomUUID();
    private final ReportExportJobRepository jobs = mock(ReportExportJobRepository.class);
    private final RoleDataScopeService scopes = new RoleDataScopeService(
            mock(com.brainserve.appointment.iam.api.StaffCommunicationDirectory.class),
            mock(com.brainserve.appointment.employee.api.EmployeeDirectory.class));
    private final RoleDataScopeService scopeReads = spy(scopes);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final ReportExportService service = new ReportExportService(jobs, mock(RoleAwareHistoryQueryService.class),
            scopeReads, mock(ApplicationEventPublisher.class), mock(InternalNotificationGateway.class), mock(AuditService.class),
            mock(S3Client.class), presigner, new ObjectMapper(), mock(ObjectStorageDeletionService.class),
            mock(GovernanceLedgerService.class), mock(DataGovernanceService.class), mock(PlatformTransactionManager.class), "synthetic", 7, 1000000);

    // Regression: AUD-05 — stored exports must respect the requester's current role and department.
    // Found by /qa on 2026-10-13
    // Report: docs/SECURITY_FUNCTIONAL_CAPACITY_AUDIT.md
    @Test void aFormerHrCannotDownloadTheirDepartmentWideVisitReportAsAnEmployee() throws Exception {
        arrange("ROLE_EMPLOYEE", department);
        assertThatThrownBy(() -> service.download(actor, jobId)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(presigner);
    }

    @Test void hrCannotDownloadTheirOldDepartmentReportAfterATransfer() throws Exception {
        arrange("ROLE_HR_ADMIN", UUID.randomUUID());
        assertThatThrownBy(() -> service.download(actor, jobId)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(presigner);
    }

    @Test void theSameHrScopeCanStillDownloadTheirCompletedReport() throws Exception {
        arrange("ROLE_HR_ADMIN", department);
        assertThat(service.download(actor, jobId).url()).isEqualTo("https://storage.audit.invalid/synthetic");
    }

    @Test void theCurrentSystemAdministratorCanStillDownloadAReport() throws Exception {
        arrange("ROLE_SYSTEM_ADMIN", null);
        assertThat(service.download(actor, jobId).url()).isEqualTo("https://storage.audit.invalid/synthetic");
    }

    private void arrange(String role, UUID currentDepartment) throws Exception {
        var job = new ReportExportJob(actor, "ROLE_HR_ADMIN", HistoryDataset.VISITS, ReportExportJob.ExportFormat.CSV,
                Instant.now().minusSeconds(86400), Instant.now(), department, null, null);
        job.complete("synthetic.csv", "synthetic.csv", 1, 1, Instant.now().plusSeconds(3600));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        doReturn(new RoleDataScopeService.RoleDataScope(actor, UUID.randomUUID(), currentDepartment, role,
                role.equals("ROLE_SYSTEM_ADMIN"))).when(scopeReads).resolve(actor);
        var signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(URI.create("https://storage.audit.invalid/synthetic").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);
    }
}
