'use client';
import { useCallback, useEffect, useState } from 'react';
import type { AccessRecord, Appointment } from '../../types/workspace';
import { useAdminSession } from '../integrations/use-admin-session';
import { kioskApi } from './api/kiosk-api';
import type { Badge, Intake } from './types';
import styles from './kiosk.module.css';

const validBadge=(value:Badge)=>value.templateVersion===1&&Number.isFinite(Date.parse(value.expiresAt))&&Date.parse(value.expiresAt)>Date.now()&&/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(value.qrCodeDataUrl);
export function ArrivalTools({appointments,accessRecords}:{appointments:Appointment[];accessRecords:AccessRecord[]}) {
  const [queue,setQueue]=useState<Intake[]>([]),[badge,setBadge]=useState<Badge|null>(null),[record,setRecord]=useState(''),[error,setError]=useState('');
  const {begin,busy,sessionEnded}=useAdminSession(()=>{setQueue([]);setBadge(null);setRecord('');setError('');});
  const load=useCallback(async()=>{const op=begin();if(!op)return;setError('');try{const data=await kioskApi.pending(op.signal);if(op.current())setQueue(data);}catch{if(op.current())setError('Arrival requests could not be loaded.');}finally{op.finish();}},[begin]);
  useEffect(()=>{const timer=setTimeout(()=>void load(),0);return()=>clearTimeout(timer);},[load]);
  useEffect(()=>{if(!badge)return;const timer=setTimeout(()=>setBadge(null),Math.max(0,Date.parse(badge.expiresAt)-Date.now()));return()=>clearTimeout(timer);},[badge]);
  useEffect(()=>{const hide=()=>{if(document.hidden)setBadge(null);};document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide);},[]);
  const resolve=async(value:Intake)=>{const op=begin();if(!op)return;setError('');try{await kioskApi.resolve(value,op.signal);const data=await kioskApi.pending(op.signal);if(op.current())setQueue(data);}catch{if(op.current())setError('Resolution was not confirmed. Reload arrivals before retrying.');}finally{op.finish();}};
  const prepare=async(print:boolean)=>{const op=begin();if(!op)return;setBadge(null);setError('');try{const value=await kioskApi.badge(record,op.signal);if(!op.current())return;if(!validBadge(value))throw new Error('Invalid badge');setBadge(value);if(print)requestAnimationFrame(()=>{if(op.current())window.print();});}catch{if(op.current())setError('Badge could not be prepared. Reload the visitor record and check whether the visitor is still inside.');}finally{op.finish();}};
  const active=accessRecords.filter(value=>!value.checkedOutAt);
  return <section className={styles.panel} aria-label="Arrival requests and badges"><h2>Arrival requests and badges</h2><p>A kiosk request needs staff verification. Check-in and checkout use the existing visitor controls.</p><button className="button button-secondary" disabled={busy||sessionEnded} onClick={()=>void load()}>Reload arrivals</button><div className={styles.list}>{queue.map(value=><div className={styles.row} key={value.id}><span><strong>{appointments.find(visit=>visit.id===value.appointmentId)?.visitor??'Visitor arrival request'}</strong><small>{value.appointmentId} · {new Date(value.receivedAt).toLocaleString('en-IN')}</small></span><button className="button button-secondary" disabled={busy||sessionEnded} onClick={()=>void resolve(value)}>Mark request reviewed</button></div>)}{queue.length===0&&<p>No pending kiosk arrival requests.</p>}</div>
  <div className={styles.form}><label>Visitor badge<select value={record} onChange={e=>{setRecord(e.target.value);setBadge(null);}} disabled={busy||sessionEnded}><option value="">Select a visitor currently inside</option>{active.map(value=><option key={value.id} value={value.id}>{value.visitorName} · {value.badgeNumber}</option>)}</select></label><div className={styles.actions}><button className="button button-secondary" disabled={busy||sessionEnded||!active.some(value=>value.id===record)} onClick={()=>void prepare(false)}>Prepare badge</button><button className="button button-primary" disabled={busy||sessionEnded||!badge||!active.some(value=>value.id===record)} onClick={()=>void prepare(true)}>Print badge</button></div></div>
  {badge&&<div className={styles.preview}><article className={styles.badge} data-visitor-badge><div><strong>{badge.companyName}</strong><small>VISITOR · {badge.badgeNumber}</small><strong>{badge.visitorName}</strong><small>{badge.referenceNumber}</small><small>Expires {new Date(badge.expiresAt).toLocaleString('en-IN')}</small></div>{/* Signed PNG is returned by the backend and must print at its physical size. */}
{/* eslint-disable-next-line @next/next/no-img-element */}
<img src={badge.qrCodeDataUrl} alt="Signed visitor pass QR" width={320} height={320}/></article></div>}{error&&<p role="alert" className={styles.error}>{error}</p>}</section>;
}
