import { type Department } from "../../types/workspace";

export const DEPARTMENT_LOGO_ALIASES: Record<string, string> = {
    EXEC: "executive-leadership",
    EXECUTIVE: "executive-leadership",
    EXECUTIVE_OFFICE: "executive-leadership",
    LEADERSHIP: "executive-leadership",
    EXECUTIVE_LEADERSHIP: "executive-leadership",
    HR: "human-resources",
    HUMAN_RESOURCES: "human-resources",
    FIN: "finance-accounting",
    FINANCE: "finance-accounting",
    ACCOUNTS: "finance-accounting",
    ACCOUNTING: "finance-accounting",
    FINANCE_ACCOUNTING: "finance-accounting",
    PRO: "operations-delivery",
    OPS: "operations-delivery",
    OPERATIONS: "operations-delivery",
    DELIVERY: "operations-delivery",
    PRODUCTION: "operations-delivery",
    PRODUCTION_DEPARTMENT: "operations-delivery",
    OPERATIONS_DELIVERY: "operations-delivery",
    SALES: "sales",
    MKT: "marketing",
    MARKETING: "marketing",
    RD: "research-development",
    R_D: "research-development",
    RND: "research-development",
    RESEARCH_DEVELOPMENT: "research-development",
    IT: "information-technology",
    TECHNOLOGY: "information-technology",
    IT_TECHNOLOGY: "information-technology",
    CS: "customer-support",
    SUPPORT: "customer-support",
    CUSTOMER_SERVICE: "customer-support",
    CUSTOMER_SUPPORT: "customer-support",
    CUSTOMER_SERVICE_SUPPORT: "customer-support",
    LEGAL: "legal-compliance",
    COMPLIANCE: "legal-compliance",
    LEGAL_COMPLIANCE: "legal-compliance",
    PROC: "purchasing-procurement",
    PROCUREMENT: "purchasing-procurement",
    PURCHASING: "purchasing-procurement",
    PURCHASING_PROCUREMENT: "purchasing-procurement",
    ADMIN: "administration",
    ADMINISTRATION: "administration",
    PD: "product-development",
    PRODUCT: "product-development",
    PRODUCT_DEPT: "product-development",
    PRODUCT_DEV: "product-development",
    PRODUCT_DEPARTMENT: "product-development",
    PRODUCT_ENGINEERING: "product-development",
    PRODUCT_MANAGEMENT: "product-development",
    PRODUCT_DEVELOPMENT: "product-development",
};

export const PREFERRED_DEPARTMENT_CODES: Record<string, string> = {
    EXECUTIVE: "EXEC",
    EXECUTIVE_OFFICE: "EXEC",
    EXECUTIVE_LEADERSHIP: "EXEC",
    HUMAN_RESOURCES: "HR",
    FINANCE: "FIN",
    FINANCE_ACCOUNTING: "FIN",
    OPERATIONS: "OPS",
    OPERATIONS_DELIVERY: "OPS",
    PRODUCTION: "PRO",
    PRODUCTION_DEPARTMENT: "PRO",
    SALES: "SALES",
    MARKETING: "MKT",
    RESEARCH_DEVELOPMENT: "RND",
    INFORMATION_TECHNOLOGY: "IT",
    IT_TECHNOLOGY: "IT",
    CUSTOMER_SERVICE: "SUPPORT",
    CUSTOMER_SUPPORT: "SUPPORT",
    CUSTOMER_SERVICE_SUPPORT: "SUPPORT",
    LEGAL_COMPLIANCE: "LEGAL",
    PURCHASING_PROCUREMENT: "PROC",
    ADMINISTRATION: "ADMIN",
    PRODUCT: "PRODUCT",
    PRODUCT_DEPT: "PRODUCT",
    PRODUCT_DEPARTMENT: "PRODUCT",
    PRODUCT_ENGINEERING: "PRODUCT",
    PRODUCT_MANAGEMENT: "PRODUCT",
    PRODUCT_DEVELOPMENT: "PRODUCT",
};

export function normalizeDepartmentLogoToken(value: string) {
    return value.trim().toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

export function normalizeDepartmentCode(value: string) {
    return normalizeDepartmentLogoToken(value).slice(0, 20);
}

export function normalizeDepartmentName(value: string) {
    return value.trim().replace(/\s+/g, " ");
}

export function suggestDepartmentCode(name: string) {
    const normalizedName = normalizeDepartmentLogoToken(name);
    if (!normalizedName) return "";
    const preferred = PREFERRED_DEPARTMENT_CODES[normalizedName];
    if (preferred) return preferred;
    const words = normalizedName.split("_").filter(Boolean);
    return (words.length > 1 ? words.map((word) => word[0]).join("") : words[0]).slice(0, 20);
}

export function departmentLogoKey(department: Department) {
    const code = normalizeDepartmentLogoToken(department.code);
    const name = normalizeDepartmentLogoToken(department.name);
    return DEPARTMENT_LOGO_ALIASES[name]
        ?? DEPARTMENT_LOGO_ALIASES[code]
        ?? code.toLowerCase().replaceAll("_", "-");
}

