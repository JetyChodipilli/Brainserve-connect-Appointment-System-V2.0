import { apiRequest } from '../../../lib/api-client';
import type { Badge, Device, Group, GroupRequest, Intake, Provisioned } from '../types';
const read = <T>(path: string, signal: AbortSignal) => apiRequest<T>(path, { signal, cache: 'no-store' });
const write = <T>(path: string, body: unknown, signal: AbortSignal) => apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body), signal }, false);
export const kioskApi = {
  devices: (signal: AbortSignal) => read<Device[]>('/admin/kiosks', signal),
  provision: (label: string, signal: AbortSignal) => write<Provisioned>('/admin/kiosks', { label }, signal),
  revoke: (device: Device, signal: AbortSignal) => write('/admin/kiosks/'+encodeURIComponent(device.id)+'/revoke', { version: device.version }, signal),
  groups: (signal: AbortSignal) => read<Group[]>('/appointments/groups', signal),
  register: (value: GroupRequest, signal: AbortSignal) => write<Group>('/appointments/groups', value, signal),
  pending: (signal: AbortSignal) => read<Intake[]>('/reception/kiosk-intakes', signal),
  resolve: (value: Intake, signal: AbortSignal) => write('/reception/kiosk-intakes/'+encodeURIComponent(value.id)+'/resolve', { version: value.version }, signal),
  badge: (id: string, signal: AbortSignal) => read<Badge>('/reception/badges/'+encodeURIComponent(id), signal)
};
