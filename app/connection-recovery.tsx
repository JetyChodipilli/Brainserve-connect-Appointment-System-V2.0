"use client";

import { useId, useRef, useState, useSyncExternalStore } from "react";
import { ArrowRight, ChevronDown, Pause, Play, RotateCcw } from "lucide-react";
import styles from "./connection-recovery.module.css";

type ConnectionRecoveryProps = {
    mode?: "unavailable" | "restoring" | "redirecting";
    title?: string;
    busy?: boolean;
    onRetry?: () => void | Promise<void>;
    assetUrls?: {
        source?: string;
        room?: string;
    };
};

const subscribeToHydration = () => () => {};

/** Shared presentation for connection and session checks. Auth stays with the caller. */
export default function ConnectionRecovery({
                                               mode = "unavailable",
                                               title,
                                               busy = false,
                                               onRetry,
                                               assetUrls,
                                           }: ConnectionRecoveryProps) {
    const hydrated = useSyncExternalStore(subscribeToHydration, () => true, () => false);
    const [paused, setPaused] = useState(false);
    const [retrying, setRetrying] = useState(false);
    const retryInFlight = useRef(false);
    const [retryError, setRetryError] = useState("");
    const id = useId().replaceAll(":", "");
    const restoring = mode === "restoring";
    const redirecting = mode === "redirecting";
    const checking = retrying || busy || restoring || redirecting;
    const sourceImage = assetUrls?.source ?? "/connection-recovery/modular-bridge-source.png";
    const roomImage = assetUrls?.room ?? "/connection-recovery/modular-bridge-room.png";

    async function retryConnection() {
        if (checking || retryInFlight.current) return;
        retryInFlight.current = true;
        setRetrying(true);
        setRetryError("");
        try {
            if (onRetry) {
                await onRetry();
            } else {
                // Reload through the existing backend and session guards; never bypass sign-in.
                window.location.reload();
            }
        } catch {
            setRetryError("Connection is still unavailable. Please try again.");
        } finally {
            retryInFlight.current = false;
            setRetrying(false);
        }
    }

    return (
        <main className={styles.page} data-motion={paused ? "paused" : "playing"} data-connection-mode={mode}>
            <header className={styles.brand} aria-label="BrainServe Connect — Workplace operations">
        <span className={styles.brandMark} aria-hidden="true">
          <PhotoCrop src={sourceImage} x={92} y={49} width={61} height={61} />
        </span>
                <div>
                    <span className={styles.brandName}>BrainServe Connect</span>
                    <span className={styles.brandCaption}>WORKPLACE OPERATIONS</span>
                </div>
            </header>

            <section className={styles.content} aria-labelledby={`${id}-title`}>
                <div className={styles.copy}>
                    <p className={styles.status} role="status" aria-live="polite">
                        <span className={styles.statusDot} aria-hidden="true" />
                        {redirecting ? "Session verified" : restoring ? "Connecting securely" : checking ? "Checking connection…" : "Connection unavailable"}
                    </p>
                    <h1 className={styles.title} id={`${id}-title`}>
                        {title ?? "Reconnecting to your workspace"}
                    </h1>
                    <p className={styles.description}>
                        {redirecting ? "Your session is verified. Opening your authorized workspace." : restoring
                            ? "Verifying your current role and permissions. Please keep this tab open."
                            : "We couldn’t connect. Please try again in a moment."}
                    </p>
                    <button className={styles.retry} type="button" onClick={retryConnection} disabled={!hydrated || checking} data-action="retry">
                        {redirecting ? "Opening workspace…" : checking ? "Checking connection…" : "Retry connection"}
                        {checking ? <RotateCcw size={19} aria-hidden="true" /> : <ArrowRight size={19} aria-hidden="true" />}
                    </button>
                    {retryError && <p role="alert">{retryError}</p>}
                    <details className={styles.help}>
                        <summary>Connection help <ChevronDown size={16} aria-hidden="true" /></summary>
                        <div className={styles.helpContent}>
                            <p>Check your internet connection, then try again.</p>
                            <p>If your connection is working, wait a moment before retrying. If this continues, contact your company administrator.</p>
                        </div>
                    </details>
                </div>
            </section>
            <ModularBridge sourceImage={sourceImage} roomImage={roomImage} />

            <footer className={styles.footer}>
                <span className={styles.signature}>BrainServe Connect</span>
                <button className={styles.motionControl} type="button" onClick={() => setPaused(!paused)} aria-pressed={paused} disabled={!hydrated} data-action="motion">
                    {paused ? <Play size={14} aria-hidden="true" /> : <Pause size={14} aria-hidden="true" />}
                    {paused ? "Resume animation" : "Pause animation"}
                </button>
            </footer>
        </main>
    );
}

// Real source pixels preserve the selected concept's rounded edges and materials.
// Keep each photographed pair together so moving it cannot reveal an occluded face.
function PhotoCrop({ src, x, y, width, height }: {
    src: string;
    x: number; y: number; width: number; height: number;
}) {
    return <img
        className={styles.sourcePhoto}
        src={src}
        width={1672}
        height={941}
        alt=""
        draggable={false}
        decoding="async"
        style={{
            width: `${1672 / width * 100}%`,
            left: `${-x / width * 100}%`,
            top: `${-y / height * 100}%`,
        }}
    />;
}

function ModularBridge({ sourceImage, roomImage }: {
    sourceImage: string;
    roomImage: string;
}) {
    return (
        <div className={styles.sceneViewport} aria-hidden="true">
            <div className={styles.scene}>
                <img className={styles.room} src={roomImage} width={1672} height={941} alt="" draggable={false} fetchPriority="high" />
                <div className={`${styles.module} ${styles.leftModule}`}>
                    <div className={styles.leftMask}>
                        <PhotoCrop src={sourceImage} x={738} y={377} width={350} height={180} />
                    </div>
                </div>
                <div className={`${styles.module} ${styles.rightModule}`}>
                    <div className={styles.rightMask}>
                        <PhotoCrop src={sourceImage} x={1240} y={407} width={385} height={207} />
                    </div>
                </div>
                <div className={`${styles.module} ${styles.bridgeModule}`}>
                    <div className={styles.bridgeMask}>
                        <PhotoCrop src={sourceImage} x={1038} y={291} width={263} height={134} />
                    </div>
                </div>
            </div>
        </div>
    );
}
