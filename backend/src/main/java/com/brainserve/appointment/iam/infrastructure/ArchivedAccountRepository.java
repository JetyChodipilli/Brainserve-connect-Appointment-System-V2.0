package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.ArchivedAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArchivedAccountRepository
        extends JpaRepository<ArchivedAccount, UUID> {

    Optional<ArchivedAccount> findFirstByOriginalUserIdAndRecoveredAtIsNull(
            UUID originalUserId
    );

    List<ArchivedAccount> findTop500ByOrderByArchivedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
              from ArchivedAccount account
             where account.id = :id
            """)
    Optional<ArchivedAccount> findForUpdateById(@Param("id") UUID id);

    @Query("""
            select account
              from ArchivedAccount account
             where account.recoveredAt is null
               and (cast(:query as String) is null
                    or lower(account.fullNameSnapshot) like lower(concat('%', cast(:query as String), '%'))
                    or lower(account.emailSnapshot) like lower(concat('%', cast(:query as String), '%'))
                    or lower(account.roleSnapshot) like lower(concat('%', cast(:query as String), '%'))
                    or lower(account.departmentNameSnapshot) like lower(concat('%', cast(:query as String), '%')))
            """)
    Page<ArchivedAccount> search(
            @Param("query") String query,
            Pageable pageable
    );
}