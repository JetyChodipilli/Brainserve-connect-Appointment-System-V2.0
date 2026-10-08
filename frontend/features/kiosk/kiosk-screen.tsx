 'use client';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, kioskRequest } from '../../lib/api-client';
import type { KioskSession } from './types';
import styles from './kiosk.module.css';

export function KioskScreen() {
  const [session, setSession] = useState<KioskSession | null>(null), [busy, setBusy] = useState(false), [message, setMessage] = useState(''), [error, setError] = useState('');
  const code = useRef<HTMLInputElement>(null), pass = useRef<HTMLInputElement>(null), token = useRef('');
  const generation = useRef(0), locked = useRef(false), request = useRef<AbortController | null>(null), idle = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reset = useCallback(() => {
    generation.current++;request.current?.abort();request.current=null;locked.current=false;
    if (idle.current) clearTimeout(idle.current);
    if (pass.current) pass.current.value=''; if (code.current) code.current.value='';
    setBusy(false);setMessage('');setError('');
  }, []);
  const disconnect = useCallback(() => { reset();token.current='';setSession(null); }, [reset]);
  const touch = useCallback(() => { if (idle.current) clearTimeout(idle.current);idle.current=setTimeout(reset,60_000); }, [reset]);
  useEffect(() => {
    if (location.search || location.hash) history.replaceState(null,'',location.pathname);
    const hide=()=> { if (document.hidden) reset(); };
    document.addEventListener('visibilitychange',hide);window.addEventListener('pagehide',disconnect);window.addEventListener('popstate',disconnect);
    return () => {disconnect();document.removeEventListener('visibilitychange',hide);window.removeEventListener('pagehide',disconnect);window.removeEventListener('popstate',disconnect);};
  }, [reset,disconnect]);
  useEffect(() => {
    if (!session) return;
    const expiry=setTimeout(disconnect,Math.max(0,Date.parse(session.expiresAt)-Date.now()));touch();
    return () => clearTimeout(expiry);
  }, [session,disconnect,touch]);
  const connect = async (event: FormEvent) => {
    event.preventDefault();if(locked.current) return;const value=code.current?.value.trim()??'';
    if(!/^[A-Za-z0-9_-]{43}$/.test(value)) {setError('Enter the device code supplied by your administrator.');return;}
    locked.current=true;setBusy(true);setError('');if(code.current)code.current.value='';
    const controller=new AbortController();request.current=controller;const current=generation.current;
    try {
      const result=await kioskRequest<KioskSession>('session',value,{},controller.signal);
      if(current!==generation.current || controller.signal.aborted)return;
      if(typeof result.label!=='string'||result.resetSeconds!==60||!Number.isFinite(Date.parse(result.expiresAt))||Date.parse(result.expiresAt)<=Date.now()) throw new Error('Invalid session');
      token.current=value;setSession(result);
    } catch { if(current===generation.current&&!controller.signal.aborted)setError('Device connection could not be confirmed. Ask your administrator.'); }
    finally {if(current===generation.current){locked.current=false;setBusy(false);request.current=null;}}
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();if(locked.current || !session)return;const value=pass.current?.value.trim()??'';if(!value)return;
    locked.current=true;setBusy(true);setError('');setMessage('');if(pass.current)pass.current.value='';touch();
    const controller=new AbortController();request.current=controller;const current=generation.current;
    try {const result=await kioskRequest<{accepted:boolean}>('intake',token.current,{token:value},controller.signal);
      if(current!==generation.current||controller.signal.aborted)return;
      if(result.accepted!==true)throw new Error('Unconfirmed');setMessage('Arrival request received. Please wait for Reception or Security.');
    } catch(reason) {if(current!==generation.current||controller.signal.aborted)return;if(reason instanceof ApiError&&reason.status===401){disconnect();setError('Device access expired. Ask your administrator to reconnect.');}else setError('Arrival could not be confirmed. Ask Reception or Security.');}
    finally {if(current===generation.current){locked.current=false;setBusy(false);request.current=null;pass.current?.focus();}}
  };
  return <main className={styles.screen}><section className={styles.card} aria-label="Visitor kiosk"><p>VISITOR ARRIVALS</p><h1>{session?'Welcome. Scan your visitor pass.':'Connect this visitor device'}</h1><p>{session?'Reception or Security will verify your arrival before check-in.':'Ask your administrator for a device code. It stays active for up to eight hours.'}</p>
    {session ? <form className={styles.form} onSubmit={submit}><label>Scanned QR content<input ref={pass} maxLength={500} autoComplete="off" autoFocus onInput={touch} required disabled={busy}/></label><button className="button button-primary" disabled={busy}>{busy?'Submitting…':'Request arrival'}</button><div className={styles.actions}><button type="button" className="button button-secondary" onClick={()=>{reset();pass.current?.focus();}}>Start again</button><button type="button" className="button button-secondary" onClick={disconnect}>Disconnect device</button></div><small>Visitor details clear after 60 seconds of inactivity.</small></form>
    : <form className={styles.form} onSubmit={connect}><label>Device code<input ref={code} type="password" minLength={43} maxLength={43} autoComplete="off" spellCheck={false} required disabled={busy}/></label><button className="button button-primary" disabled={busy}>{busy?'Connecting…':'Connect device'}</button></form>}
    {message&&<p role="status">{message}</p>}{error&&<p className={styles.error} role="alert">{error}</p>}</section></main>;
}
