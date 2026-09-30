import { apiRequest } from "../../../lib/api-client";
import type { CompanyProfile } from "../../../types/api";

export const publicApi = {
companyProfile() {
    return apiRequest<CompanyProfile>("/public/company-profile");
  },
};
