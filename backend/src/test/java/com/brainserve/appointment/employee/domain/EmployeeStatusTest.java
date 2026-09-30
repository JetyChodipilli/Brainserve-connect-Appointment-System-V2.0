package com.brainserve.appointment.employee.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeStatusTest {
    private final Employee employee = new Employee("BSPL-IT-0001", "Ishaan", "Verma", "ishaan@brainserve.in",
            "+919876543210", UUID.randomUUID(), "Software Engineer", LocalDate.now());

    @Test
    void onboardingEmployeeCanBeActivated() {
        assertThat(employee.isAvailableAsHost()).isFalse();
        employee.transitionTo(EmployeeStatus.ACTIVE);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(employee.isAvailableAsHost()).isTrue();
    }

    @Test
    void onboardingEmployeeCannotSkipDirectlyToResigned() {
        assertThatThrownBy(() -> employee.transitionTo(EmployeeStatus.RESIGNED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ONBOARDING");
    }

    @Test
    void employeeWithASingleNameKeepsThatNameWhenHrAssignsADepartment() {
        Employee singleName = new Employee("BSPL-IT-0002", "Keerthi", "", "keerthi@brainserve.in",
                null, UUID.randomUUID(), "Java Backend Developer", LocalDate.now());

        assertThat(singleName.getFirstName()).isEqualTo("Keerthi");
        assertThat(singleName.getLastName()).isEmpty();
        assertThat(singleName.getDisplayName()).isEqualTo("Keerthi");
    }
}
