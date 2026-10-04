import { apiRequest } from "../../lib/api-client";
import { searchQueryString } from "./search-model";
import type { SearchOpenRecord, SearchPages, SearchResponse, SearchType } from "./types";
export const searchApi = {
  query(query: string, pages: SearchPages, signal: AbortSignal) {
    return apiRequest<SearchResponse>(`/search?${searchQueryString(query, pages)}`, { signal, cache: "no-store" });
  },
  open(type: SearchType, id: string, signal: AbortSignal) {
    return apiRequest<SearchOpenRecord>(`/search/${encodeURIComponent(type)}/${encodeURIComponent(id)}/open`, { signal, cache: "no-store" });
  },
};
