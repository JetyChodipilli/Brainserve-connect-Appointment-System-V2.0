package com.brainserve.appointment.compensation.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "compensation_package")
public class CompensationPackage extends AuditableEntity {
    private static final int SCALE = 2;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;
    @Column(name = "basic_salary", nullable = false, precision = 19, scale = 2)
    private BigDecimal basicSalary;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal hra;
    @Column(name = "transport_allowance", nullable = false, precision = 19, scale = 2)
    private BigDecimal transportAllowance;
    @Column(name = "medical_allowance", nullable = false, precision = 19, scale = 2)
    private BigDecimal medicalAllowance;
    @Column(name = "special_allowance", nullable = false, precision = 19, scale = 2)
    private BigDecimal specialAllowance;
    @Column(name = "other_allowance", nullable = false, precision = 19, scale = 2)
    private BigDecimal otherAllowance;
    @Column(name = "pf_deduction", nullable = false, precision = 19, scale = 2)
    private BigDecimal providentFundDeduction;
    @Column(name = "professional_tax", nullable = false, precision = 19, scale = 2)
    private BigDecimal professionalTax;
    @Column(name = "income_tax_estimate", nullable = false, precision = 19, scale = 2)
    private BigDecimal incomeTaxEstimate;
    @Column(name = "other_deductions", nullable = false, precision = 19, scale = 2)
    private BigDecimal otherDeductions;
    @Column(name = "gross_salary", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossSalary;
    @Column(name = "total_deductions", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDeductions;
    @Column(name = "net_salary", nullable = false, precision = 19, scale = 2)
    private BigDecimal netSalary;
    @Column(name = "annual_ctc", nullable = false, precision = 19, scale = 2)
    private BigDecimal annualCtc;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected CompensationPackage() {}
    public CompensationPackage(UUID employeeId, Components values, String currency, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.employeeId = employeeId;
        this.basicSalary = money(values.basicSalary()); this.hra = money(values.hra());
        this.transportAllowance = money(values.transportAllowance()); this.medicalAllowance = money(values.medicalAllowance());
        this.specialAllowance = money(values.specialAllowance()); this.otherAllowance = money(values.otherAllowance());
        this.providentFundDeduction = money(values.providentFundDeduction()); this.professionalTax = money(values.professionalTax());
        this.incomeTaxEstimate = money(values.incomeTaxEstimate()); this.otherDeductions = money(values.otherDeductions());
        this.grossSalary = basicSalary.add(hra).add(transportAllowance).add(medicalAllowance).add(specialAllowance).add(otherAllowance);
        this.totalDeductions = providentFundDeduction.add(professionalTax).add(incomeTaxEstimate).add(otherDeductions);
        this.netSalary = grossSalary.subtract(totalDeductions);
        this.annualCtc = grossSalary.multiply(BigDecimal.valueOf(12)).setScale(SCALE, RoundingMode.HALF_UP);
        this.currency = currency.toUpperCase(); this.effectiveFrom = effectiveFrom; this.effectiveTo = effectiveTo;
    }
    private BigDecimal money(BigDecimal value) { return value.setScale(SCALE, RoundingMode.HALF_UP); }
    public UUID getEmployeeId() { return employeeId; }
    public BigDecimal getBasicSalary() { return basicSalary; }
    public BigDecimal getHra() { return hra; }
    public BigDecimal getTransportAllowance() { return transportAllowance; }
    public BigDecimal getMedicalAllowance() { return medicalAllowance; }
    public BigDecimal getSpecialAllowance() { return specialAllowance; }
    public BigDecimal getOtherAllowance() { return otherAllowance; }
    public BigDecimal getProvidentFundDeduction() { return providentFundDeduction; }
    public BigDecimal getProfessionalTax() { return professionalTax; }
    public BigDecimal getIncomeTaxEstimate() { return incomeTaxEstimate; }
    public BigDecimal getOtherDeductions() { return otherDeductions; }
    public BigDecimal getGrossSalary() { return grossSalary; }
    public BigDecimal getTotalDeductions() { return totalDeductions; }
    public BigDecimal getNetSalary() { return netSalary; }
    public BigDecimal getAnnualCtc() { return annualCtc; }
    public String getCurrency() { return currency; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }

    public record Components(BigDecimal basicSalary, BigDecimal hra, BigDecimal transportAllowance,
                             BigDecimal medicalAllowance, BigDecimal specialAllowance, BigDecimal otherAllowance,
                             BigDecimal providentFundDeduction, BigDecimal professionalTax,
                             BigDecimal incomeTaxEstimate, BigDecimal otherDeductions) {}
}
