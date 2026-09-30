package com.brainserve.appointment.compensation.infrastructure;

import com.brainserve.appointment.compensation.domain.CompensationPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface CompensationRepository
        extends JpaRepository<CompensationPackage, UUID> {

    @Query("""
            select c
              from CompensationPackage c
             where c.employeeId = :employeeId
               and c.effectiveFrom <= :today
               and (
                    c.effectiveTo is null
                    or c.effectiveTo >= :today
               )
             order by c.effectiveFrom desc
            """)
    List<CompensationPackage> findCurrent(
            @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today
    );

    List<CompensationPackage>
    findByEmployeeIdOrderByEffectiveFromDesc(UUID employeeId);

    /*
     * Keeps the existing service-layer method contract.
     *
     * Separate queries are used because PostgreSQL cannot determine
     * the type of a null parameter used as:
     *
     *     :toDate is null
     */
    default boolean overlaps(
            UUID employeeId,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        Objects.requireNonNull(
                employeeId,
                "employeeId is required"
        );
        Objects.requireNonNull(
                fromDate,
                "fromDate is required"
        );

        if (toDate == null) {
            return overlapsOpenEnded(employeeId, fromDate);
        }

        return overlapsBounded(employeeId, fromDate, toDate);
    }

    /*
     * New period: [fromDate, no end date]
     *
     * It overlaps any existing record that has not ended
     * before the new start date.
     */
    @Query("""
            select case when count(c) > 0
                        then true
                        else false
                   end
              from CompensationPackage c
             where c.employeeId = :employeeId
               and (
                    c.effectiveTo is null
                    or c.effectiveTo >= :fromDate
               )
            """)
    boolean overlapsOpenEnded(
            @Param("employeeId") UUID employeeId,
            @Param("fromDate") LocalDate fromDate
    );

    /*
     * Two inclusive periods overlap when:
     *
     * existing.start <= new.end
     * and
     * existing.end is null or existing.end >= new.start
     */
    @Query("""
            select case when count(c) > 0
                        then true
                        else false
                   end
              from CompensationPackage c
             where c.employeeId = :employeeId
               and c.effectiveFrom <= :toDate
               and (
                    c.effectiveTo is null
                    or c.effectiveTo >= :fromDate
               )
            """)
    boolean overlapsBounded(
            @Param("employeeId") UUID employeeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );
}