"use client";

import { type WorkspaceSetting } from "../../../services/brainserve-api";
import { useState } from "react";

export function SettingControl({ setting, disabled, onSave }: { setting: WorkspaceSetting; disabled: boolean; onSave: (key: string, value: string) => Promise<void> }) {
    const [value, setValue] = useState(setting.value);
    if (setting.type === "BOOLEAN") return <label className="setting-toggle"><span><strong>{setting.description}</strong><small>{setting.key}</small></span><input type="checkbox" checked={value === "true"} disabled={disabled} onChange={(event) => { const next = String(event.target.checked); setValue(next); void onSave(setting.key, next); }} /><i /></label>;
    return <div className="setting-row"><span><strong>{setting.description}</strong><small>{setting.key}</small></span><input type={setting.type === "INTEGER" ? "number" : "text"} value={value} disabled={disabled} min={setting.type === "INTEGER" ? 0 : undefined} onChange={(event) => setValue(event.target.value)} /><button className="button button-secondary" disabled={disabled || value === setting.value || !value.trim()} onClick={() => void onSave(setting.key, value)}>Save</button></div>;
}

