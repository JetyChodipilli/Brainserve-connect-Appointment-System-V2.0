export const agreementStatuses = ['UNCONFIGURED', 'PILOT', 'ACTIVE', 'PAUSED', 'CANCELLED'] as const;
export type AgreementStatus = typeof agreementStatuses[number];
export type ReleaseProfile = { status: AgreementStatus; reference: string; startsOn: string | null; renewsOn: string | null; supportOwner: string; supportEmail: string; supportHours: string };
export type ReleaseSnapshot = { version: number; profile: ReleaseProfile; officeZone: string; officeDate: string; renewalDue: boolean };
