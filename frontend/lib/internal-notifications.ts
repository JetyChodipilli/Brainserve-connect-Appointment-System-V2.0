export function allowedRecipientAuthorities(role: string) {
  const matrix: Record<string, string[]> = {
    CEO: ["ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD", "ROLE_RECEPTIONIST"],
    Manager: ["ROLE_CEO", "ROLE_HR_ADMIN", "ROLE_RECEPTIONIST"],
    "HR Admin": ["ROLE_CEO", "ROLE_TEAM_LEAD", "ROLE_EMPLOYEE", "ROLE_RECEPTIONIST"],
    "Team Lead": ["ROLE_HR_ADMIN", "ROLE_RECEPTIONIST"],
    Employee: ["ROLE_HR_ADMIN"],
    Reception: ["ROLE_CEO", "ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD"],
  };
  return matrix[role] ?? [];
}

export function canSendInternalNotification(senderRole: string, recipientAuthority: string) {
  return allowedRecipientAuthorities(senderRole).includes(recipientAuthority);
}

export function readableNotificationRole(authority: string) {
  return authority.replace("ROLE_", "").replaceAll("_", " ").toLowerCase()
    .replace(/(^|\s)\S/g, (value) => value.toUpperCase());
}

export type NotificationDirectoryEntry = {
  userId: string;
  fullName: string;
  email: string;
  roles: string[];
};

export function currentNotificationRecipients(
  allowedAuthorities: string[],
  currentAccounts: NotificationDirectoryEntry[],
  fallbackAccounts: NotificationDirectoryEntry[],
  senderUserId: string | undefined,
  senderEmail: string,
) {
  const normalizedSenderEmail = senderEmail.trim().toLowerCase();
  const isSender = (account: NotificationDirectoryEntry) =>
    Boolean(senderUserId && account.userId === senderUserId)
      || account.email.trim().toLowerCase() === normalizedSenderEmail;
  const currentAuthorities = new Set(currentAccounts.flatMap((account) => account.roles));
  const current = currentAccounts.filter((account) => !isSender(account)
    && account.roles.some((authority) => allowedAuthorities.includes(authority)));
  const fallbacks = fallbackAccounts.filter((account) => !isSender(account)
    && account.roles.some((authority) => allowedAuthorities.includes(authority)
      && !currentAuthorities.has(authority)));
  return [...current, ...fallbacks].filter((account, index, values) =>
    values.findIndex((candidate) => candidate.userId === account.userId
      || candidate.email.trim().toLowerCase() === account.email.trim().toLowerCase()) === index);
}
