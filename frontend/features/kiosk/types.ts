export type Device = { id: string; label: string; expiresAt: string; revokedAt: string | null; version: number };
export type Provisioned = Device & { token: string };
export type KioskSession = { label: string; expiresAt: string; resetSeconds: number };
export type Group = { id: string; label: string; createdAt: string; members: { appointmentId: string; referenceNumber: string; status: string }[] };
export type GroupRequest = { requestId: string; label: string; type: string; hostEmployeeId: string; routingDepartmentId: string; slotStart: string; slotEnd: string; purpose: string; members: { visitorName: string; visitorEmail: string; visitorPhone: string; visitorCompany: string }[] };
export type Intake = { id: string; appointmentId: string; receivedAt: string; version: number };
export type Badge = { badgeNumber: string; referenceNumber: string; visitorName: string; companyName: string; expiresAt: string; qrCodeDataUrl: string; templateVersion: number };
