import { apiRequest } from "../../../lib/api-client";
import type { MyProfile } from "../../../types/api";

export const profileApi = {
myProfile() {
    return apiRequest<MyProfile>("/profile/me");
  },
uploadMyProfilePhoto(file: File) {
    const body = new FormData();
    body.append("file", file);
    return apiRequest<MyProfile>("/profile/me/photo", { method: "POST", body });
  },
};
