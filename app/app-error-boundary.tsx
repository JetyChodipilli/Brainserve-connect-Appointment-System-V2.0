"use client";

import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RefreshCcw } from "lucide-react";

type AppErrorBoundaryProps = { children: ReactNode };
type AppErrorBoundaryState = { failed: boolean; reference: string };

export default class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
    state: AppErrorBoundaryState = { failed: false, reference: "" };

    static getDerivedStateFromError(): AppErrorBoundaryState {
        return {
            failed: true,
            reference: `UI-${Date.now().toString(36).toUpperCase()}`,
        };
    }

    componentDidCatch(error: Error, details: ErrorInfo) {
        console.error("BrainServe Connect workspace render failed", error, details.componentStack);
    }

    private reload = () => window.location.reload();

    render() {
        if (!this.state.failed) return this.props.children;

        return (
            <main className="app-error-page">
                <section className="app-error-card glass-panel" aria-labelledby="app-error-title" role="alert">
                    <span className="app-error-icon" aria-hidden="true"><AlertTriangle size={28} /></span>
                    <span className="eyebrow">WORKSPACE RECOVERY</span>
                    <h1 id="app-error-title">This view could not be displayed.</h1>
                    <p>
                        Your saved records were not changed. Reload the secure workspace to restore the latest
                        PostgreSQL-backed data.
                    </p>
                    <small>Support reference: {this.state.reference}</small>
                    <button type="button" className="button button-primary button-large" onClick={this.reload}>
                        <RefreshCcw size={17} aria-hidden="true" /> Reload workspace
                    </button>
                </section>
            </main>
        );
    }
}
