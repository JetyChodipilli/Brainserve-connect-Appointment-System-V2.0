import type { ProblemResponse, RealtimeConnectionState, SpringPage, WorkspaceUpdateCoordinationMessage, WorkspaceUpdateLease, WorkspaceUpdateLockManager } from "../types/api";

const configuredApiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL?.trim().replace(/\/+$/, "");

export const isBackendConfigured = Boolean(configuredApiBaseUrl);

const API_BASE_URL = configuredApiBaseUrl ?? "";

const API_REQUEST_TIMEOUT_MS = 20_000;

const AUTH_SESSION_EXPIRED_EVENT = "brainserve:auth-session-expired";
const AUTH_SESSION_CHANGED_EVENT = "brainserve:auth-session-changed";

const inFlightGetRequests = new Map<string, Promise<unknown>>();

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly problem: ProblemResponse) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }
}

const ACCESS_TOKEN_KEY = "brainserve.connect.access-token";

const REFRESH_TOKEN_KEY = "brainserve.connect.refresh-token";

const sessionValue = (key: string) => typeof window === "undefined" ? null : window.sessionStorage.getItem(key);

let accessToken: string | null = sessionValue(ACCESS_TOKEN_KEY);

let refreshToken: string | null = sessionValue(REFRESH_TOKEN_KEY);

let refreshPromise: Promise<boolean> | null = null;

// Login/logout changes invalidate pending work; token renewal keeps the same session.
let authSessionGeneration = 0;

function invalidatePendingSessionWork() {
  authSessionGeneration += 1;
  inFlightGetRequests.clear();
  refreshPromise = null;
}

function requireCurrentSession(generation: number) {
  if (generation !== authSessionGeneration) {
    throw new Error("The active session changed. Please retry in the current workspace.");
  }
}

export function setAccessToken(token: string | null) {
  const changed = token !== accessToken || (token === null && refreshToken !== null);
  if (changed) invalidatePendingSessionWork();
  accessToken = token;
  if (typeof window !== "undefined") {
    if (token) window.sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
    else window.sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  }
  if (token === null) {
    refreshToken = null;
    if (typeof window !== "undefined") window.sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  }
  if (changed && typeof window !== "undefined") window.dispatchEvent(new CustomEvent(AUTH_SESSION_CHANGED_EVENT));
}

export function setAuthTokens(access: string, refresh: string) {
  const changed = access !== accessToken || refresh !== refreshToken;
  if (changed) invalidatePendingSessionWork();
  storeAuthTokens(access, refresh);
  if (changed && typeof window !== "undefined") window.dispatchEvent(new CustomEvent(AUTH_SESSION_CHANGED_EVENT));
}

function storeAuthTokens(access: string, refresh: string) {
  accessToken = access;
  refreshToken = refresh;
  if (typeof window !== "undefined") {
    window.sessionStorage.setItem(ACCESS_TOKEN_KEY, access);
    window.sessionStorage.setItem(REFRESH_TOKEN_KEY, refresh);
  }
}

export function hasAuthSession() {
  return Boolean(accessToken || refreshToken);
}

function expireAuthSession() {
  setAccessToken(null);
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent(AUTH_SESSION_EXPIRED_EVENT));
}

export function onAuthSessionExpired(listener: () => void) {
  if (typeof window === "undefined") return () => undefined;
  window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, listener);
  return () => window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, listener);
}

async function refreshAccessToken() {
  if (!API_BASE_URL) return false;
  if (!refreshToken) return false;
  if (!refreshPromise) {
    const token = refreshToken;
    const generation = authSessionGeneration;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), API_REQUEST_TIMEOUT_MS);
    const request: Promise<boolean> = fetch(`${API_BASE_URL}/auth/refresh`, {
      method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ refreshToken: token }), credentials: "omit", signal: controller.signal,
    }).then(async (response) => {
      requireCurrentSession(generation);
      if (!response.ok) {
        if (response.status === 400 || response.status === 401 || response.status === 403) {
          expireAuthSession();
          throw new ApiError(401, { detail: "Your session expired or was revoked. Please sign in again." });
        }
        // Surface a temporary refresh failure, not the original expired-access-token 401.
        // Session restoration can then retry without discarding the refresh credential.
        throw new ApiError(response.status, { detail: "Session verification is temporarily unavailable. Please retry." });
      }
      const tokens = await response.json() as { accessToken: string; refreshToken: string };
      requireCurrentSession(generation);
      storeAuthTokens(tokens.accessToken, tokens.refreshToken);
      return true;
    }).finally(() => {
      clearTimeout(timeout);
      if (refreshPromise === request) refreshPromise = null;
    });
    refreshPromise = request;
  }
  return refreshPromise;
}

export function apiRequest<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  if (method !== "GET" || init.signal) return performApiRequest<T>(path, init, retry);

  const requestKey = `${authSessionGeneration}:${path}`;
  const existing = inFlightGetRequests.get(requestKey);
  if (existing) return existing as Promise<T>;

  const request = performApiRequest<T>(path, init, retry);
  inFlightGetRequests.set(requestKey, request);
  const clearRequest = () => {
    if (inFlightGetRequests.get(requestKey) === request) inFlightGetRequests.delete(requestKey);
  };
  void request.then(clearRequest, clearRequest);
  return request;
}

async function performApiRequest<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const generation = authSessionGeneration;
  if (!API_BASE_URL) {
    throw new Error("BrainServe Connect is not connected to its secure backend.");
  }
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const controller = new AbortController();
  let timedOut = false;
  const abortFromCaller = () => controller.abort(init.signal?.reason);
  if (init.signal?.aborted) abortFromCaller();
  else init.signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, API_REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...init, headers, credentials: "omit", signal: controller.signal,
    });
    requireCurrentSession(generation);
    if (response.status === 401) {
      const problem = (await response.clone().json().catch(() => ({}))) as ProblemResponse;
      requireCurrentSession(generation);
      if (["ACCOUNT_AUTHORITY_CHANGED", "ACCOUNT_NOT_ACTIVE", "PASSWORD_CHANGE_REQUIRED"].includes(problem.errorCode ?? "")) {
        expireAuthSession();
        throw new ApiError(401, problem);
      }
    }
    if (response.status === 401 && retry && refreshToken && !path.endsWith("/auth/refresh") && !path.endsWith("/auth/login")) {
      if (await refreshAccessToken()) {
        requireCurrentSession(generation);
        return performApiRequest<T>(path, init, false);
      }
    }
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as ProblemResponse;
      requireCurrentSession(generation);
      if (problem.errorCode === "MFA_STEP_UP_REQUIRED") problem.detail = "Verify your identity in My profile → Account security, then retry this action.";
      if (problem.errorCode === "MFA_REQUIRED" && !path.startsWith("/auth/")) expireAuthSession();
      throw new ApiError(response.status, problem);
    }
    const contentType = response.headers.get("Content-Type")?.toLowerCase() ?? "";
    if (response.status === 204 || !contentType.includes("json")) return undefined as T;
    const value = await response.json() as T;
    requireCurrentSession(generation);
    return value;
  } catch (reason) {
    if (timedOut) throw new Error("The BrainServe Connect service did not respond within 20 seconds. Please try again.");
    throw reason;
  } finally {
    clearTimeout(timeout);
    init.signal?.removeEventListener("abort", abortFromCaller);
  }
}

export function normalizeSpringPage<T>(result: SpringPage<T>): SpringPage<T> {
  const number = result.number ?? result.page?.number;
  const size = result.size ?? result.page?.size;
  const totalElements = result.totalElements ?? result.page?.totalElements;
  const totalPages = result.totalPages ?? result.page?.totalPages;

  return {
    ...result,
    number,
    size,
    totalElements,
    totalPages,
    last: result.last ?? (number !== undefined && totalPages !== undefined
        ? number + 1 >= totalPages
        : undefined),
  };
}

export async function requestSpringPage<T>(path: string, init: RequestInit = {}): Promise<SpringPage<T>> {
  return normalizeSpringPage(await apiRequest<SpringPage<T>>(path, init));
}

export async function allSpringPageContent<T>(path: string, pageSize = 200): Promise<{ content: T[] }> {
  const generation = authSessionGeneration;
  const separator = path.includes("?") ? "&" : "?";
  const content: T[] = [];
  let page = 0;
  while (true) {
    const result = await requestSpringPage<T>(
        `${path}${separator}page=${page}&size=${pageSize}`,
        { cache: "no-store" },
    );
    requireCurrentSession(generation);
    content.push(...result.content);
    const isLast = result.last ?? (result.totalPages !== undefined
        ? page + 1 >= result.totalPages
        : result.content.length < pageSize);
    if (isLast) return { content };
    page += 1;
  }
}

function subscribeDirectlyToWorkspaceUpdates(
    onUpdate: () => void,
    onStateChange: (state: RealtimeConnectionState) => void,
) {
  const generation = authSessionGeneration;
  let stopped = false;
  let controller: AbortController | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let reconnectAttempt = 0;

  const scheduleReconnect = () => {
    if (stopped || generation !== authSessionGeneration) return;
    onStateChange("reconnecting");
    const baseDelay = Math.min(30_000, 3_000 * (2 ** reconnectAttempt));
    const jitter = Math.round(Math.random() * 750);
    reconnectAttempt = Math.min(reconnectAttempt + 1, 4);
    reconnectTimer = setTimeout(() => void connect(), baseDelay + jitter);
  };

  const consume = async (response: Response) => {
    if (!response.body) throw new Error("The live update stream is unavailable.");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!stopped) {
      const { value, done } = await reader.read();
      if (done || stopped || generation !== authSessionGeneration) break;
      buffer += decoder.decode(value, { stream: true }).replaceAll("\r\n", "\n");
      const frames = buffer.split("\n\n");
      buffer = frames.pop() ?? "";
      frames.forEach((frame) => {
        const eventName = frame.split("\n").find((line) => line.startsWith("event:"))?.slice(6).trim();
        if (eventName === "workspace-refresh") onUpdate();
      });
    }
  };

  const connect = async () => {
    if (stopped || generation !== authSessionGeneration || !accessToken) { onStateChange("offline"); return; }
    controller = new AbortController();
    onStateChange("connecting");
    let handshakeTimedOut = false;
    const handshakeTimeout = setTimeout(() => {
      handshakeTimedOut = true;
      controller?.abort();
    }, API_REQUEST_TIMEOUT_MS);
    try {
      let response = await fetch(`${API_BASE_URL}/realtime/stream`, {
        method: "GET",
        headers: { Accept: "text/event-stream", Authorization: `Bearer ${accessToken}` },
        credentials: "omit",
        cache: "no-store",
        signal: controller.signal,
      });
      requireCurrentSession(generation);
      if (response.status === 401 && await refreshAccessToken() && accessToken) {
        requireCurrentSession(generation);
        response = await fetch(`${API_BASE_URL}/realtime/stream`, {
          method: "GET",
          headers: { Accept: "text/event-stream", Authorization: `Bearer ${accessToken}` },
          credentials: "omit",
          cache: "no-store",
          signal: controller.signal,
        });
      }
      requireCurrentSession(generation);
      clearTimeout(handshakeTimeout);
      if (!response.ok) throw new Error(`Live update connection failed (${response.status}).`);
      reconnectAttempt = 0;
      onStateChange("live");
      await consume(response);
      if (!stopped) scheduleReconnect();
    } catch (reason) {
      clearTimeout(handshakeTimeout);
      if (!stopped && generation === authSessionGeneration && (handshakeTimedOut
          || !(reason instanceof DOMException && reason.name === "AbortError"))) scheduleReconnect();
    }
  };

  void connect();
  return () => {
    stopped = true;
    controller?.abort();
    if (reconnectTimer) clearTimeout(reconnectTimer);
  };
}

const WORKSPACE_UPDATE_CHANNEL = "brainserve.connect.workspace-updates.v1";

const WORKSPACE_UPDATE_LOCK = "brainserve.connect.workspace-update-leader.v1";

const WORKSPACE_UPDATE_LEASE_KEY = "brainserve.connect.workspace-update-lease.v1";

const WORKSPACE_UPDATE_MESSAGE_KEY = "brainserve.connect.workspace-update-message.v1";

const WORKSPACE_UPDATE_HEARTBEAT_MS = 4_000;

const WORKSPACE_UPDATE_LEASE_MS = 15_000;

let workspaceUpdateLeader = false;

export function isWorkspaceUpdateLeader() {
  return workspaceUpdateLeader;
}

export function subscribeToWorkspaceUpdates(
    onUpdate: () => void,
    onStateChange: (state: RealtimeConnectionState) => void,
) {
  if (typeof window === "undefined") return () => undefined;
  let cleanup = accessToken ? openWorkspaceUpdateSubscription(onUpdate, onStateChange) : () => undefined;
  const changeSession = () => {
    cleanup();
    onStateChange("offline");
    cleanup = accessToken ? openWorkspaceUpdateSubscription(onUpdate, onStateChange) : () => undefined;
  };
  window.addEventListener(AUTH_SESSION_CHANGED_EVENT, changeSession);
  return () => { window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, changeSession); cleanup(); };
}

function openWorkspaceUpdateSubscription(
    onUpdate: () => void,
    onStateChange: (state: RealtimeConnectionState) => void,
) {
  const generation = authSessionGeneration;
  // Only an account identifier scopes coordination. Credentials and record data never leave this tab.
  let subject = "unknown";
  try {
    const claims = JSON.parse(atob((accessToken ?? "").split(".")[1].replaceAll("-", "+").replaceAll("_", "/"))) as { sub?: unknown };
    if (typeof claims.sub === "string") subject = claims.sub;
  } catch { /* Invalid tokens are still rejected by the backend. */ }
  const scope = encodeURIComponent(`${API_BASE_URL}:${subject}`);
  const channelName = `${WORKSPACE_UPDATE_CHANNEL}:${scope}`;
  const lockName = `${WORKSPACE_UPDATE_LOCK}:${scope}`;
  const leaseKey = `${WORKSPACE_UPDATE_LEASE_KEY}:${scope}`;
  const messageKey = `${WORKSPACE_UPDATE_MESSAGE_KEY}:${scope}`;

  const realtimeStates: RealtimeConnectionState[] = ["connecting", "live", "reconnecting", "offline"];
  const runtimeCrypto = globalThis.crypto as (Crypto & { randomUUID?: () => string }) | undefined;
  const tabId = typeof runtimeCrypto?.randomUUID === "function"
      ? runtimeCrypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  let stopped = false;
  let leader = false;
  let leaderState: RealtimeConnectionState = "connecting";
  let directUnsubscribe: (() => void) | null = null;
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  let fallbackElectionTimer: ReturnType<typeof setInterval> | null = null;
  let fallbackClaimTimer: ReturnType<typeof setTimeout> | null = null;
  let usingLeaseFallback = false;
  let lockAbortController: AbortController | null = null;
  let releaseWebLock: (() => void) | null = null;
  let channel: BroadcastChannel | null = null;

  try {
    if ("BroadcastChannel" in window) channel = new BroadcastChannel(channelName);
  } catch {
    channel = null;
  }

  const publish = (message: WorkspaceUpdateCoordinationMessage) => {
    if (stopped) return;
    if (channel) {
      channel.postMessage(message);
      return;
    }
    try {
      window.localStorage.setItem(messageKey, JSON.stringify({
        ...message,
        nonce: `${message.sentAt}-${Math.random().toString(36).slice(2)}`,
      }));
    } catch {
      // If browser storage is blocked, leader election falls back to one stream per tab.
    }
  };

  const readLease = (): WorkspaceUpdateLease | null => {
    try {
      const stored = window.localStorage.getItem(leaseKey);
      if (!stored) return null;
      const candidate = JSON.parse(stored) as Partial<WorkspaceUpdateLease>;
      return typeof candidate.leaderId === "string" && typeof candidate.expiresAt === "number"
          ? { leaderId: candidate.leaderId, expiresAt: candidate.expiresAt }
          : null;
    } catch {
      return null;
    }
  };

  const writeLease = () => {
    try {
      window.localStorage.setItem(leaseKey, JSON.stringify({
        leaderId: tabId,
        expiresAt: Date.now() + WORKSPACE_UPDATE_LEASE_MS,
      } satisfies WorkspaceUpdateLease));
      return readLease()?.leaderId === tabId;
    } catch {
      return false;
    }
  };

  const releaseOwnedLease = () => {
    try {
      if (readLease()?.leaderId === tabId) window.localStorage.removeItem(leaseKey);
    } catch {
      // The lease expires automatically if storage becomes unavailable during cleanup.
    }
  };

  const publishLeaderState = () => publish({
    type: "leader-state",
    senderId: tabId,
    sentAt: Date.now(),
    state: leaderState,
  });

  const stopLeading = (announce: boolean) => {
    if (!leader) return;
    leader = false;
    workspaceUpdateLeader = false;
    directUnsubscribe?.();
    directUnsubscribe = null;
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = null;
    if (usingLeaseFallback) releaseOwnedLease();
    if (announce && !stopped) publish({ type: "leader-released", senderId: tabId, sentAt: Date.now() });
  };

  const startLeading = () => {
    if (stopped || leader || document.visibilityState !== "visible") return;
    leader = true;
    workspaceUpdateLeader = true;
    leaderState = "connecting";
    onStateChange(leaderState);
    publishLeaderState();
    directUnsubscribe = subscribeDirectlyToWorkspaceUpdates(
        () => {
          if (stopped || !leader) return;
          onUpdate();
          publish({ type: "refresh", senderId: tabId, sentAt: Date.now() });
        },
        (state) => {
          if (stopped || !leader) return;
          leaderState = state;
          onStateChange(state);
          publishLeaderState();
        },
    );
    heartbeatTimer = setInterval(() => {
      if (stopped || !leader) return;
      if (usingLeaseFallback) {
        const lease = readLease();
        if (lease && lease.leaderId !== tabId && lease.expiresAt > Date.now()) {
          stopLeading(false);
          onStateChange("reconnecting");
          return;
        }
        if (!writeLease()) {
          stopLeading(false);
          onStateChange("reconnecting");
          return;
        }
      }
      publishLeaderState();
    }, WORKSPACE_UPDATE_HEARTBEAT_MS);
  };

  const acceptCoordinationMessage = (value: unknown) => {
    if (stopped || generation !== authSessionGeneration || !value || typeof value !== "object") return;
    const message = value as Partial<WorkspaceUpdateCoordinationMessage>;
    if (message.senderId === tabId || typeof message.senderId !== "string") return;
    if (typeof message.sentAt !== "number" || !Number.isFinite(message.sentAt)
        || Math.abs(Date.now() - message.sentAt) > WORKSPACE_UPDATE_LEASE_MS) return;
    if (message.type === "refresh") {
      onUpdate();
      return;
    }
    if (message.type === "leader-state" && message.state
        && realtimeStates.includes(message.state)) {
      if (!leader) onStateChange(message.state);
      return;
    }
    if (message.type === "leader-released" && !leader) onStateChange("reconnecting");
  };

  if (channel) channel.onmessage = (event: MessageEvent<unknown>) => acceptCoordinationMessage(event.data);

  const evaluateFallbackLeadership = () => {
    if (stopped || !usingLeaseFallback || document.visibilityState !== "visible") return;
    const lease = readLease();
    if (leader) {
      if (lease && lease.leaderId !== tabId && lease.expiresAt > Date.now()) {
        stopLeading(false);
        onStateChange("reconnecting");
      }
      return;
    }
    if (lease && lease.expiresAt > Date.now()) return;
    if (fallbackClaimTimer) return;
    fallbackClaimTimer = setTimeout(() => {
      fallbackClaimTimer = null;
      if (stopped || leader || document.visibilityState !== "visible") return;
      const current = readLease();
      if (current && current.expiresAt > Date.now()) return;
      if (writeLease()) startLeading();
    }, 100 + Math.round(Math.random() * 400));
  };

  const startLeaseFallback = () => {
    if (stopped || usingLeaseFallback) return;
    try {
      const probeKey = `${leaseKey}.probe.${tabId}`;
      window.localStorage.setItem(probeKey, "1");
      window.localStorage.removeItem(probeKey);
    } catch {
      // Browser storage can be disabled by privacy policy. Preserve live updates
      // by falling back to a direct stream in this tab.
      startLeading();
      return;
    }
    usingLeaseFallback = true;
    evaluateFallbackLeadership();
    fallbackElectionTimer = setInterval(evaluateFallbackLeadership, WORKSPACE_UPDATE_HEARTBEAT_MS);
  };

  const handleStorage = (event: StorageEvent) => {
    if (event.key === messageKey && event.newValue) {
      try { acceptCoordinationMessage(JSON.parse(event.newValue)); }
      catch { /* Ignore malformed messages from unrelated or older clients. */ }
      return;
    }
    if (event.key === leaseKey && usingLeaseFallback) evaluateFallbackLeadership();
  };
  window.addEventListener("storage", handleStorage);
  onStateChange("connecting");

  const lockManager = typeof navigator !== "undefined"
      ? (navigator as Navigator & { locks?: WorkspaceUpdateLockManager }).locks
      : undefined;
  const requestWebLock = () => {
    if (!lockManager?.request || stopped || lockAbortController || usingLeaseFallback
        || document.visibilityState !== "visible") return;
    const pendingController = new AbortController();
    lockAbortController = pendingController;
    void lockManager.request(
        lockName,
        { mode: "exclusive", signal: pendingController.signal },
        async () => {
          if (stopped) return;
          startLeading();
          await new Promise<void>((resolve) => { releaseWebLock = resolve; });
          releaseWebLock = null;
          stopLeading(false);
        },
    ).catch((reason: unknown) => {
      if (stopped || (reason instanceof DOMException && reason.name === "AbortError")) return;
      startLeaseFallback();
    }).finally(() => {
      if (lockAbortController === pendingController) lockAbortController = null;
      if (!stopped && !usingLeaseFallback && document.visibilityState === "visible") requestWebLock();
    });
  };
  const suspendLeadership = () => {
    stopLeading(true);
    lockAbortController?.abort();
    releaseWebLock?.();
  };
  const handleVisibility = () => {
    if (document.visibilityState !== "visible") { suspendLeadership(); return; }
    onUpdate();
    if (lockManager?.request) requestWebLock(); else { startLeaseFallback(); evaluateFallbackLeadership(); }
  };
  document.addEventListener("visibilitychange", handleVisibility);
  window.addEventListener("pagehide", suspendLeadership);
  window.addEventListener("pageshow", handleVisibility);
  if (lockManager?.request) requestWebLock(); else startLeaseFallback();

  return () => {
    stopped = true;
    window.removeEventListener("storage", handleStorage);
    document.removeEventListener("visibilitychange", handleVisibility);
    window.removeEventListener("pagehide", suspendLeadership);
    window.removeEventListener("pageshow", handleVisibility);
    lockAbortController?.abort();
    releaseWebLock?.();
    releaseWebLock = null;
    stopLeading(false);
    if (fallbackElectionTimer) clearInterval(fallbackElectionTimer);
    if (fallbackClaimTimer) clearTimeout(fallbackClaimTimer);
    releaseOwnedLease();
    channel?.close();
  };
}

export const authApi = {
login(email: string, password: string) {
    return apiRequest<{ accessToken: string; refreshToken: string; forcePasswordChange: boolean; mfaRequired?: boolean; mfaEnrolled?: boolean }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
  },
async logout() {
    const token = refreshToken;
    setAccessToken(null);
    if (!token) return;
    try {
      await apiRequest<void>("/auth/logout", {
        method: "POST", body: JSON.stringify({ refreshToken: token }), keepalive: true,
      }, false);
    } catch {
      // Local credentials are already cleared. A disconnected backend must not
      // prevent the user from ending the browser session.
    }
  },
requestPasswordChangeOtp(currentPassword: string) {
    return apiRequest<void>("/auth/change-password/request-otp", {
      method: "POST",
      body: JSON.stringify({ currentPassword }),
    });
  },
confirmPasswordChange(otp: string, newPassword: string) {
    return apiRequest<void>("/auth/change-password/confirm", {
      method: "POST",
      body: JSON.stringify({ otp, newPassword }),
    });
  },
registerAccount(fullName: string, email: string, password: string, role: string) {
    return apiRequest<{ id: string; email: string; status: string; message: string }>("/register", {
      method: "POST",
      body: JSON.stringify({ fullName, email, password, role }),
    });
  },
requestAccountRecovery(identifier: string, role: string, type: "PASSWORD" | "EMAIL") {
    return apiRequest<{ message: string }>("/auth/recovery/requests", {
      method: "POST", body: JSON.stringify({ identifier, role, type }),
    });
  },
recoverPassword(code: string, newPassword: string, confirmPassword: string) {
    return apiRequest<void>("/auth/recovery/password", {
      method: "POST", body: JSON.stringify({ code, newPassword, confirmPassword }),
    });
  },
recoverEmail(code: string, newEmail: string, confirmEmail: string) {
    return apiRequest<void>("/auth/recovery/email", {
      method: "POST", body: JSON.stringify({ code, newEmail, confirmEmail }),
    });
  },
me() {
    return apiRequest<{ userId: string; employeeId: string | null; email: string; roles: string[]; permissions: string[]; forcePasswordChange: boolean }>("/auth/me");
  },
changeMyEmail(currentPassword: string, newEmail: string) {
    return apiRequest<void>("/auth/change-email", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newEmail }),
    });
  },
};
