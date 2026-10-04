"use client";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { searchApi } from "./search-api";
import { createSearchRequestGuard, INITIAL_SEARCH_PAGES, normalizedSearchQuery, searchRequestKey, validSearchQuery } from "./search-model";
import type { SearchItem, SearchOpenRecord, SearchPages, SearchResponse, SearchType } from "./types";
type ResultState = { key: string; result?: SearchResponse; error?: string; loading: boolean };
export function useUnifiedSearch(identityKey: string, onOpen: (record: SearchOpenRecord) => void, disabled = false) {
  const [query, setQueryValue] = useState("");
  const [pages, setPages] = useState<SearchPages>({ ...INITIAL_SEARCH_PAGES });
  const [state, setState] = useState<ResultState>({ key: "", loading: false });
  const [openState, setOpenState] = useState<{ key: string; id?: string; error?: string }>({ key: "" });
  const guard = useRef(createSearchRequestGuard());
  const openController = useRef<AbortController | null>(null);
  const key = searchRequestKey(identityKey, query, pages);
  const currentKey = useRef(key);
  // Commit the new identity before any asynchronous completion can publish or open a record.
  useLayoutEffect(() => {
    currentKey.current = disabled ? `${key}:disabled` : key;
    guard.current.invalidate(); openController.current?.abort();
  }, [key, disabled]);
  useEffect(() => {
    const requestGuard = guard.current;
    requestGuard.invalidate(); openController.current?.abort();
    const controller = new AbortController();
    if (disabled || !identityKey || !validSearchQuery(query)) return () => controller.abort();
    const observed = guard.current.current();
    const debounce = setTimeout(() => {
      setState({ key, loading: true }); setOpenState({ key });
      void searchApi.query(query, pages, controller.signal).then(result => {
        if (!controller.signal.aborted && guard.current.accepts(observed) && currentKey.current === key) setState({ key, result, loading: false });
      }).catch(error => {
        if (!controller.signal.aborted && guard.current.accepts(observed) && currentKey.current === key) setState({ key, error: error instanceof Error ? error.message : "Search is temporarily unavailable. Try again.", loading: false });
      });
    }, 220);
    return () => { clearTimeout(debounce); controller.abort(); requestGuard.invalidate(); openController.current?.abort(); };
  }, [key, query, pages, identityKey, disabled]);
  function setQuery(value: string) {
    guard.current.invalidate(); openController.current?.abort();
    setQueryValue(value); setPages({ ...INITIAL_SEARCH_PAGES });
  }
  function changePage(type: SearchType, page: number) {
    if (page < 0 || page > 999) return;
    guard.current.invalidate(); openController.current?.abort();
    setPages(previous => ({ ...previous, [type]: page }));
  }
  async function open(type: SearchType, item: SearchItem) {
    if (disabled || !identityKey || !validSearchQuery(query)) return;
    openController.current?.abort(); const controller = new AbortController(); openController.current = controller;
    const observed = guard.current.current(); const requestKey = key;
    setOpenState({ key, id: item.id });
    try {
      const record = await searchApi.open(type, item.id, controller.signal);
      if (!controller.signal.aborted && guard.current.accepts(observed) && currentKey.current === requestKey) { setOpenState({ key }); onOpen(record); }
    } catch (error) {
      if (!controller.signal.aborted && guard.current.accepts(observed) && currentKey.current === requestKey) {
        // Results can lose eligibility between search and open. Remove them rather than retaining revoked names.
        setState({ key, error: "This result is unavailable in your current workspace. Search again.", loading: false });
        setOpenState({ key, error: error instanceof Error ? error.message : "Unable to open this record" });
      }
    }
  }
  const valid = validSearchQuery(query);
  const visible: ResultState = disabled || !identityKey || !valid ? { key, loading: false } : state.key === key ? state : { key, loading: true };
  return { query, setQuery, pages, changePage, open, valid, normalizedQuery: normalizedSearchQuery(query),
    result: visible.result, loading: visible.loading, error: visible.error,
    opening: openState.key === key ? openState.id : undefined };
}
