"use client";

import { X } from "lucide-react";
import { AppointmentTimeline } from "../activity/components/appointment-timeline";
import { WorkActivity } from "../workboard/components/work-activity";
import { WorkDialog } from "../workboard/components/work-dialog";
import type { SearchOpenRecord } from "../search/types";
import styles from "./record-details.module.css";

const labels = { appointments: "Appointment", visitors: "Visitor", employees: "Employee", worksheets: "Worksheet" };

export function RecordDetailDialog({ record, onClose }: { record: SearchOpenRecord; onClose: () => void }) {
    return <WorkDialog titleId="search-record-title" className={styles.dialog} onClose={onClose}>
        <header className={styles.header}>
            <div><small>{labels[record.type]}</small><h2 id="search-record-title">{record.title}</h2><p>{record.subtitle}</p></div>
            <button type="button" className="icon-button" data-initial-focus aria-label="Close record details" onClick={onClose}><X size={20} /></button>
        </header>
        <div className={styles.content}>
            <p className={styles.status}>{record.status.replaceAll("_", " ").toLowerCase()}</p>
            <dl className={styles.facts}>{Object.entries(record.detail).filter(([, value]) => value != null).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
            {record.type === "appointments" && <AppointmentTimeline appointmentId={record.id} />}
            {record.type === "worksheets" && <WorkActivity taskId={record.id} readOnly />}
        </div>
    </WorkDialog>;
}

export function AppointmentTimelineDialog({ appointmentId, title, onClose }: { appointmentId: string; title: string; onClose: () => void }) {
    return <WorkDialog titleId="appointment-history-title" className={styles.dialog} onClose={onClose}>
        <header className={styles.header}><div><small>Appointment history</small><h2 id="appointment-history-title">{title}</h2></div><button type="button" className="icon-button" data-initial-focus aria-label="Close appointment history" onClick={onClose}><X size={20} /></button></header>
        <div className={styles.content}><AppointmentTimeline appointmentId={appointmentId} /></div>
    </WorkDialog>;
}
