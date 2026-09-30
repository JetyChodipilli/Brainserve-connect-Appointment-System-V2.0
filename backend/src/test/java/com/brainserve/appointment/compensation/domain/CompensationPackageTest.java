package com.brainserve.appointment.compensation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationPackageTest {
    @Test
    void derivesGrossDeductionsNetAndAnnualCtcOnTheServer() {
        CompensationPackage.Components components = new CompensationPackage.Components(
                amount("50000"), amount("20000"), amount("3000"), amount("2000"), amount("10000"), amount("5000"),
                amount("6000"), amount("200"), amount("5000"), amount("800"));
        CompensationPackage value = new CompensationPackage(UUID.randomUUID(), components, "INR", LocalDate.now(), null);

        assertThat(value.getGrossSalary()).isEqualByComparingTo("90000.00");
        assertThat(value.getTotalDeductions()).isEqualByComparingTo("12000.00");
        assertThat(value.getNetSalary()).isEqualByComparingTo("78000.00");
        assertThat(value.getAnnualCtc()).isEqualByComparingTo("1080000.00");
    }

    private BigDecimal amount(String value) { return new BigDecimal(value); }
}
