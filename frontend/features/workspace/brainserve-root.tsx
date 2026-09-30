"use client";

import ConnectionRecovery from "../../components/shared/connection-recovery";
import { BookingFlow } from "../appointments/booking-flow";
import { TrackAppointment } from "../appointments/track-appointment";
import { AccountRecovery } from "../auth/account-recovery";
import { AccountRegistration } from "../auth/account-registration";
import { ForcedPasswordChange } from "../auth/forced-password-change";
import { Login } from "../auth/login";
import { Welcome } from "../public/welcome";
import { ApiError, brainServeApi, hasAuthSession, isBackendConfigured, onAuthSessionExpired, setAccessToken } from "../../services/brainserve-api";
import { readDemoAccounts } from "../../preview/accounts";
import { readPreviewWorkspaceSession, writePreviewWorkspaceSession } from "../../preview/session";
import { primaryRoleFromAuthorities, roleFromAuthority } from "../../config/roles";
import { type Role, type Screen } from "../../types/workspace";
import { isConnectivityFailure } from "../../utils/errors";
import { DashboardApp } from "../../components/layouts/workspace-layout";
import { useEffect, useState } from "react";

export function BackendBrainServeApp({ browserPreviewEnabled = false }: {
    browserPreviewEnabled?: boolean;
}) {
    const [screen, setScreen] = useState<Screen>("welcome");
    const [role, setRole] = useState<Role | null>(() => isBackendConfigured ? null : "HR Admin");
    const [userEmail, setUserEmail] = useState(() => isBackendConfigured ? "" : "hr.admin@brainserve.in");
    const [restoringSession, setRestoringSession] = useState(true);
    const [sessionConnectionFailure, setSessionConnectionFailure] = useState(false);
    const [sessionRetry, setSessionRetry] = useState(0);
    const [redirecting, setRedirecting] = useState(false);
    useEffect(() => {
        if (!redirecting) return;
        const timer = window.setTimeout(() => setRedirecting(false), 400);
        return () => window.clearTimeout(timer);
    }, [redirecting]);
    const [mustChangePassword, setMustChangePassword] = useState(false);
    const [currentPassword, setCurrentPassword] = useState("");
    const [sessionMessage, setSessionMessage] = useState("");
    useEffect(
        () =>
            onAuthSessionExpired(() => {
                writePreviewWorkspaceSession(null);
                setMustChangePassword(false);
                setCurrentPassword("");
                setSessionMessage(
                    "Your login changed, expired or was revoked. Sign in again to load the current role and permissions.",
                );
                setScreen("login");
            }),
        [],
    );
    useEffect(() => {
        let active = true;
        const restore = async () => {
            if (isBackendConfigured && hasAuthSession()) {
                try {
                    const profile = await brainServeApi.me();
                    if (!active) return;
                    const restoredRole = primaryRoleFromAuthorities(profile.roles);
                    if (!restoredRole) throw new ApiError(403, { detail: "Unsupported role" });
                    setSessionConnectionFailure(false);
                    setRedirecting(true);
                    setRole(restoredRole);
                    setUserEmail(profile.email);
                    setMustChangePassword(profile.forcePasswordChange);
                    setScreen("app");
                } catch (reason) {
                    if (!active) return;
                    if (isConnectivityFailure(reason)) {
                        setSessionConnectionFailure(true);
                    } else {
                        setSessionConnectionFailure(false);
                        setAccessToken(null);
                        setScreen("login");
                    }
                }
            } else if (!isBackendConfigured) {
                const previewSession = readPreviewWorkspaceSession();
                if (active && previewSession) {
                    const currentAccount = readDemoAccounts().find((account) =>
                        account.status === "ACTIVE"
                        && account.email.toLowerCase() === previewSession.email.toLowerCase()
                        && roleFromAuthority(account.role) === previewSession.role);
                    if (currentAccount) {
                        setRole(previewSession.role);
                        setUserEmail(currentAccount.email);
                        setScreen("app");
                    } else {
                        writePreviewWorkspaceSession(null);
                        setSessionMessage("This account is no longer active or its role changed. Sign in again.");
                        setScreen("login");
                    }
                }
            }
            if (active) setRestoringSession(false);
        };
        void restore();
        return () => {
            active = false;
        };
    }, [sessionRetry]);
    if (sessionConnectionFailure)
        return <ConnectionRecovery mode="unavailable" busy={restoringSession} onRetry={() => {
            setRestoringSession(true);
            setSessionRetry((attempt) => attempt + 1);
        }} />;
    if (restoringSession)
        return <ConnectionRecovery mode="restoring" title="Restoring your session…" />;
    if (redirecting)
        return <ConnectionRecovery mode="redirecting" title="Opening your workspace…" />;
    if (mustChangePassword)
        return (
            <ForcedPasswordChange
                email={userEmail}
                currentPassword={currentPassword}
                onComplete={() => {
                    setAccessToken(null);
                    writePreviewWorkspaceSession(null);
                    setMustChangePassword(false);
                    setCurrentPassword("");
                    setScreen("login");
                }}
                onLogout={() => {
                    setAccessToken(null);
                    writePreviewWorkspaceSession(null);
                    setMustChangePassword(false);
                    setCurrentPassword("");
                    setScreen("login");
                }}
            />
        );
    if (screen === "welcome") return <Welcome onNavigate={setScreen} />;
    if (screen === "book") return <BookingFlow onNavigate={setScreen} />;
    if (screen === "track") return <TrackAppointment onNavigate={setScreen} />;
    if (screen === "register")
        return <AccountRegistration onNavigate={setScreen} />;
    if (screen === "forgot-password")
        return <AccountRecovery type="PASSWORD" onNavigate={setScreen} />;
    if (screen === "forgot-email")
        return <AccountRecovery type="EMAIL" onNavigate={setScreen} />;
    if (screen === "login" || !role || !userEmail)
        return (
            <Login
                browserPreviewEnabled={browserPreviewEnabled}
                sessionMessage={sessionMessage}
                onLogin={(nextRole, email, forcePasswordChange, password) => {
                    setSessionMessage("");
                    setRedirecting(true);
                    if (!isBackendConfigured && !forcePasswordChange)
                        writePreviewWorkspaceSession({ role: nextRole, email });
                    else writePreviewWorkspaceSession(null);
                    setRole(nextRole);
                    setUserEmail(email);
                    setMustChangePassword(forcePasswordChange);
                    setCurrentPassword(forcePasswordChange ? password : "");
                    setScreen("app");
                }}
                onNavigate={setScreen}
            />
        );
    return (
        <DashboardApp
            role={role}
            userEmail={userEmail}
            onLogout={async () => {
                try {
                    await brainServeApi.logout();
                } finally {
                    writePreviewWorkspaceSession(null);
                    setScreen("welcome");
                }
            }}
        />
    );
}

