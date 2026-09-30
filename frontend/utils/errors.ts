import { ApiError } from "../services/brainserve-api";

/**
 * Raise an application-level validation failure outside the surrounding
 * try/catch block. Keeping the throw in this helper avoids locally-caught
 * exception warnings while preserving the existing error-handling behavior.
 */
export function fail(message: string): never {
    throw new Error(message);
}

/** Re-raise an unknown failure without triggering local-throw inspections. */
export function rethrow(reason: unknown): never {
    throw reason;
}

export function isConnectivityFailure(reason: unknown) {
    return !(reason instanceof ApiError)
        || reason.status >= 500
        || reason.status === 408
        || reason.status === 429;
}

