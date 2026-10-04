package com.brainserve.appointment.document;

import com.brainserve.appointment.document.application.DocumentService;
import com.brainserve.appointment.document.domain.*;
import com.brainserve.appointment.document.infrastructure.*;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.security.MessageDigest;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class Sprint6DocumentStoreTest {
    StoredDocumentRepository repository = mock(StoredDocumentRepository.class);
    ClamAvScanner scanner = mock(ClamAvScanner.class);
    S3Client s3 = mock(S3Client.class);
    DocumentService service = new DocumentService(repository, scanner, s3, null,
            mock(com.brainserve.appointment.audit.api.AuditService.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            mock(com.brainserve.appointment.iam.api.StaffCommunicationDirectory.class), null, null, null,
            mock(com.brainserve.appointment.audit.api.RejectedSecurityAuditService.class), "private", 32, 5);
    UUID task = UUID.randomUUID(), id = UUID.randomUUID();
    byte[] pdf = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    StoredDocument document(String owner, UUID ownerId, long size, String digest) {
        return new StoredDocument(owner, ownerId, "TASK_EVIDENCE", "private-key", "file.pdf", "application/pdf", size, digest, DocumentStatus.CLEAN);
    }
    String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    void stream(byte[] bytes) { when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)))); }
    @AfterEach void resetTransaction() { TransactionSynchronizationManager.clear(); }
    @Test void validBoundDownload() throws Exception {
        when(repository.findById(id)).thenReturn(Optional.of(document("WORK_TASK",task,pdf.length,digest(pdf)))); stream(pdf);
        assertArrayEquals(pdf,service.download(task,id).bytes());
    }
    @Test void mismatchedOwnerCannotReadStorage() throws Exception {
        when(repository.findById(id)).thenReturn(Optional.of(document("WORK_TASK",UUID.randomUUID(),pdf.length,digest(pdf))));
        assertThrows(BusinessException.class, () -> service.download(task,id)); verifyNoInteractions(s3);
    }
    @Test void genericDocumentCannotBecomeEvidence() throws Exception {
        when(repository.findById(id)).thenReturn(Optional.of(document("EMPLOYEE",task,pdf.length,digest(pdf))));
        assertThrows(BusinessException.class, () -> service.download(task,id)); verifyNoInteractions(s3);
    }
    @Test void rejectsChecksumMismatch() throws Exception {
        when(repository.findById(id)).thenReturn(Optional.of(document("WORK_TASK",task,pdf.length,"0".repeat(64)))); stream(pdf);
        assertThrows(BusinessException.class, () -> service.download(task,id));
    }
    @Test void fraudulentSmallMetadataCannotBypassReadLimit() throws Exception {
        when(repository.findById(id)).thenReturn(Optional.of(document("WORK_TASK",task,1,digest(pdf)))); stream(new byte[100]);
        assertThrows(BusinessException.class, () -> service.download(task,id));
    }
    @Test void outerRollbackDeletesStoredObject() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.store(task,new MockMultipartFile("file","../../unsafe\r\n.pdf","application/pdf",pdf));
        verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void committedUploadSurvivesAndSanitizesName() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        assertEquals("unsafe__.pdf",service.store(task,new MockMultipartFile("file","../../unsafe\r\n.pdf","application/pdf",pdf)).filename());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void scanFailureNeverWritesStorage() {
        doThrow(new BusinessException("MALWARE_SCAN_FAILED","Unavailable",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(scanner).assertClean(any());
        assertThrows(BusinessException.class,() -> service.store(task,new MockMultipartFile("file","file.pdf","application/pdf",pdf)));
        verifyNoInteractions(s3,repository);
    }
    @Test void invalidMagicNeverScansOrWrites() {
        assertThrows(BusinessException.class,() -> service.store(task,new MockMultipartFile("file","file.pdf","application/pdf",new byte[]{1,2,3})));
        verifyNoInteractions(scanner,s3,repository);
    }
    @Test void genericDownloadAndDeleteRemainClosedForWorkTasks() throws Exception {
        when(repository.findById(id)).thenReturn(Optional.of(document("WORK_TASK",task,pdf.length,digest(pdf))));
        // Existing owner switch rejects WORK_TASK before any generic storage access.
        assertEquals("DOCUMENT_NOT_FOUND", assertThrows(BusinessException.class,() -> service.createDownloadUrl(UUID.randomUUID(),id)).getErrorCode());
        assertEquals("DOCUMENT_NOT_FOUND", assertThrows(BusinessException.class,() -> service.delete(UUID.randomUUID(),id)).getErrorCode());
        verifyNoInteractions(s3);
    }
}
