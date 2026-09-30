import { type ProvisioningAccount } from "../../lib/api";
import { type Role } from "../../shared/types/workspace";

export function accountVisibleToApprover(account: ProvisioningAccount, role: Role) {
    if (role === "System Admin") {
        return account.status === "PENDING_APPROVAL"
            && account.role === "ROLE_CEO";
    }
    if (role === "CEO") {
        return account.status === "PENDING_APPROVAL"
            && ["ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(account.role);
    }
    if (role === "HR Admin") {
        return account.status === "PENDING_HR_APPROVAL"
            && ["ROLE_EMPLOYEE", "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(account.role);
    }
    return false;
}

