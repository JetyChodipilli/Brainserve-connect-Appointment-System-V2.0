"use client";
import { useEffect, useId, useRef, useState } from "react";
import { Search, X, LoaderCircle, ArrowRight } from "lucide-react";
import { useUnifiedSearch } from "./use-unified-search";
import type { SearchOpenRecord } from "./types";
import styles from "./unified-search.module.css";

export function UnifiedSearch({ identityKey, onOpen, disabled = false }: {
  identityKey: string; onOpen: (record: SearchOpenRecord) => void; disabled?: boolean;
}) {
  const id = useId(); const wrapper = useRef<HTMLDivElement>(null); const input = useRef<HTMLInputElement>(null);
  const [expansion, setExpansion] = useState({ identityKey, open: false });
  const expanded = expansion.identityKey === identityKey && expansion.open;
  const setExpanded = (open: boolean) => setExpansion({ identityKey, open });
  const search = useUnifiedSearch(identityKey, record => { setExpanded(false); input.current?.focus(); onOpen(record); }, disabled);
  useEffect(() => {
    const outside = (event: PointerEvent) => { if (!wrapper.current?.contains(event.target as Node)) setExpansion(previous => ({ ...previous, open: false })); };
    document.addEventListener("pointerdown", outside); return () => document.removeEventListener("pointerdown", outside);
  }, []);
  const shownGroups = search.result?.groups.filter(group => group.available) ?? [];
  const total = shownGroups.reduce((sum, group) => sum + group.totalElements, 0);
  return <div className={styles.root} ref={wrapper} onKeyDown={event => {
    if (event.key === "Escape") { setExpanded(false); input.current?.focus(); }
    if (event.key === "ArrowDown" && event.target === input.current && search.result) {
      event.preventDefault(); wrapper.current?.querySelector<HTMLButtonElement>("[data-search-result]")?.focus();
    }
  }}>
    <div className={styles.field}>
      <Search size={17} aria-hidden="true" />
      <input ref={input} type="search" aria-label="Workspace search" placeholder="Search your workspace" maxLength={100}
        value={search.query} aria-controls={expanded ? id : undefined} autoComplete="off"
        onFocus={() => setExpanded(true)} onChange={event => { search.setQuery(event.target.value); setExpanded(true); }} />
      {search.query && <button type="button" aria-label="Clear workspace search" onClick={() => { search.setQuery(""); input.current?.focus(); }}><X size={16} /></button>}
    </div>
    {expanded && <section className={styles.panel} id={id} aria-label="Workspace search results">
      <header><strong>Search your workspace</strong><button type="button" aria-label="Close search results" onClick={() => setExpanded(false)}><X size={17} /></button></header>
      {disabled ? <p role="status">Connect to the backend to search your authorized records.</p> : !search.valid ? <p role="status">Enter 2–100 characters to find appointments, visitors, employees and worksheets.</p>
        : search.loading ? <p className={styles.status} role="status"><LoaderCircle size={16} className={styles.spin} /> Searching your current workspace…</p>
        : search.error ? <p className={styles.error} role="alert">{search.error} <button type="button" onClick={() => search.setQuery(search.query)}>Try again</button></p>
        : search.result ? <>
          <p className={styles.summary} role="status">{total ? `${total} accessible results for “${search.normalizedQuery}”` : `No accessible results for “${search.normalizedQuery}”`}</p>
          {shownGroups.map(group => <section className={styles.group} key={group.type} aria-label={group.label}>
            <div className={styles.groupHeading}><strong>{group.label}</strong><span>{group.totalElements}</span></div>
            <p className={styles.coverage}>{group.coverage}</p>
            {group.items.length ? <ul>{group.items.map(item => <li key={item.id}><button type="button" data-search-result
              disabled={Boolean(search.opening)} aria-label={`Open ${group.label.toLowerCase().replace(/s$/, "")}: ${item.title}`}
              onClick={() => void search.open(group.type, item)}>
              <span><strong>{item.title}</strong><small>{item.subtitle}</small></span>
              <span className={styles.resultMeta}><small>{item.status.replaceAll("_", " ").toLowerCase()}</small>{search.opening === item.id ? <LoaderCircle className={styles.spin} size={15} /> : <ArrowRight size={15} />}</span>
            </button></li>)}</ul> : <p className={styles.empty}>No matches in this group.</p>}
            {group.totalPages > 1 && <nav className={styles.pagination} aria-label={`${group.label} search pages`}>
              <button type="button" disabled={group.number === 0} onClick={() => search.changePage(group.type, group.number - 1)}>Previous</button>
              <span>Page {group.number + 1} of {group.totalPages}</span>
              <button type="button" disabled={group.number + 1 >= group.totalPages || group.number >= 999} onClick={() => search.changePage(group.type, group.number + 1)}>Next</button>
            </nav>}
          </section>)}
          {!shownGroups.length && <p>Your current permissions do not include searchable records.</p>}
        </> : null}
      <footer>Results use your current role and department. Opening a result checks access again.</footer>
    </section>}
  </div>;
}
