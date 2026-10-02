package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.MfaCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/** Mutations are serialized by the owning iam_user_account row, including first enrollment. */
public interface MfaCredentialRepository extends JpaRepository<MfaCredential, UUID> {
    boolean existsByUserIdAndEnrolledAtIsNotNull(UUID userId);
}
