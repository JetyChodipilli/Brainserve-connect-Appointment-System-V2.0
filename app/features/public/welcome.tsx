"use client";

import { brainServeApi, type CompanyProfile, isBackendConfigured } from "../../lib/api";
import { dateCard, officeToday } from "../../lib/appointments";
import { Logo } from "../../shared/components/logo";
import { type Screen } from "../../shared/types/workspace";
import { ArrowRight, BadgeCheck, Bell, CheckCircle2, LogIn, QrCode, Search, ShieldCheck, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";

export function Welcome({ onNavigate }: { onNavigate: (screen: Screen) => void }) {
    const featuredDate = dateCard(officeToday());
    const [profile, setProfile] = useState<CompanyProfile>(() => isBackendConfigured
        ? { name: "", emailDomain: "", hqAddress: "", supportEmail: "", consentVersion: "" }
        : { name: "BrainServe Connect", emailDomain: "brainserve.in", hqAddress: "Hyderabad, Telangana, India",
            supportEmail: "support@brainserve.in", consentVersion: "2026.1" });
    const [leadership, setLeadership] = useState(() => isBackendConfigured
        ? { ceo: "", hr: "" } : { ceo: "Chief Executive Officer", hr: "HR Admin" });
    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        Promise.all([brainServeApi.companyProfile(), brainServeApi.publicHosts()]).then(([value, hosts]) => {
            if (!active) return; setProfile(value);
            setLeadership({ ceo: hosts.find((host) => host.category === "CEO")?.displayName ?? "Chief Executive Officer",
                hr: hosts.find((host) => host.category === "HR")?.displayName ?? "HR Admin" });
        }).catch(() => undefined);
        return () => { active = false; };
    }, []);
    return (
        <main className="welcome-page">
            <div className="ambient ambient-one" />
            <div className="ambient ambient-two" />
            <header className="public-header glass-panel">
                <Logo productName="BrainServe Connect" />
                <nav aria-label="Public navigation">
                    <button className="text-button" onClick={() => onNavigate("track")}>Track appointment</button>
                    <button className="button button-quiet" onClick={() => onNavigate("login")}><LogIn size={17} /> Staff login</button>
                </nav>
            </header>

            <section className="welcome-hero">
                <div className="eyebrow"><Sparkles size={14} /> Welcome to {profile.name || "your organization"}</div>
                <h1>A thoughtful welcome,<br /><span>before you arrive.</span></h1>
                <p>Book a secure appointment with our employees, HR team or leadership at {profile.hqAddress || "our office"}. We’ll guide your visit from approval to check-out.</p>
                <div className="hero-actions">
                    <button className="button button-primary button-large" onClick={() => onNavigate("book")}>Book an appointment <ArrowRight size={18} /></button>
                    <button className="button button-secondary button-large" onClick={() => onNavigate("track")}><Search size={18} /> Track your visit</button>
                </div>
                <div className="trust-row">
                    <span><ShieldCheck size={17} /> Privacy protected</span>
                    <span><BadgeCheck size={17} /> Verified check-in</span>
                    <span><Bell size={17} /> Real-time updates</span>
                </div>
            </section>

            <aside className="arrival-card glass-panel">
                <div className="arrival-top"><span>YOUR VISIT</span><QrCode size={22} /></div>
                <div className="mini-date"><strong>{featuredDate.date}</strong><span>{featuredDate.month.toUpperCase()}<br />TODAY</span></div>
                <div className="arrival-details">
                    <span>Appointment with</span>
                    <strong>{leadership.ceo || "Leadership"}</strong>
                    <small>Leadership visit · coordinated through Reception</small>
                </div>
                <div className="arrival-timeline"><span className="done" /><i /><span className="done" /><i /><span /></div>
                <div className="arrival-stages"><span>Requested</span><span>Approved</span><span>Arrive</span></div>
                <div className="arrival-footer"><CheckCircle2 size={17} /><span><strong>You’re approved</strong><small>Present your QR code at reception</small></span></div>
            </aside>

            <section className="how-it-works">
                <div><span>01</span><strong>Choose your host</strong><small>Find the right person or team.</small></div>
                <div><span>02</span><strong>Pick a suitable time</strong><small>See only genuinely available slots.</small></div>
                <div><span>03</span><strong>Receive your pass</strong><small>Get approval, updates and a secure QR.</small></div>
            </section>
        </main>
    );
}

