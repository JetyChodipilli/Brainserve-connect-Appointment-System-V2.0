'use client';
import { ActivityTimeline } from './activity-timeline';
export function AppointmentTimeline({ appointmentId }: { appointmentId: string }) { return <ActivityTimeline kind="appointment" recordId={appointmentId} />; }
