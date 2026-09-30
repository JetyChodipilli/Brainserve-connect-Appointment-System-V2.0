"use client";

import { useModalDialog } from "../../../hooks/use-modal-dialog";
import { type Appointment, type SecurityIntakeInput } from "../../../types/workspace";
import { Send, X } from "lucide-react";
import { type FormEvent, useState } from "react";

export function SecurityIntakeModal({
                                 appointment,
                                 onClose,
                                 onSubmit,
                             }: {
    appointment: Appointment;
    onClose: () => void;
    onSubmit: (
        id: string,
        input: SecurityIntakeInput,
    ) => Promise<void>;
}) {
    useModalDialog(onClose);

    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");

    const submit = async (
        event: FormEvent<HTMLFormElement>,
    ) => {
        event.preventDefault();
        setBusy(true);
        setError("");

        const data = new FormData(event.currentTarget);
        const lastFour = String(
            data.get("identityDocumentLastFour"),
        )
            .trim()
            .toUpperCase();

        try {
            await onSubmit(appointment.id, {
                visitorName: String(
                    data.get("visitorName"),
                ).trim(),
                purpose: String(
                    data.get("purpose"),
                ).trim(),
                identityDocumentType:
                    String(
                        data.get("identityDocumentType"),
                    ).trim() || null,
                identityDocumentLastFour:
                    lastFour || null,
                notes:
                    String(data.get("notes")).trim() ||
                    null,
            });
        } catch (reason) {
            setError(
                reason instanceof Error
                    ? reason.message
                    : "Security intake could not be saved.",
            );
        } finally {
            setBusy(false);
        }
    };

    return (
        <div
            className="modal-backdrop"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
                    onClose();
                }
            }}
        >
            <section
                className="modal glass-panel visit-modal"
                role="dialog"
                aria-modal="true"
                aria-labelledby="security-intake-title"
            >
                <header>
                    <div>
                        <span>SECURITY ARRIVAL INTAKE</span>

                        <h2 id="security-intake-title">
                            Record who has arrived
                        </h2>

                        <p>
                            {appointment.referenceNumber ??
                                appointment.id}{" "}
                            · booked for {appointment.host}.
                            Saving this sends Reception a
                            BrainServe Internal Calls update.
                        </p>
                    </div>

                    <button
                        type="button"
                        className="icon-button"
                        onClick={onClose}
                        aria-label="Close security intake"
                    >
                        <X size={19} />
                    </button>
                </header>

                <form onSubmit={submit}>
                    <div className="visit-modal-body">
                        <div className="modal-form-grid">
                            <label>
                                Visitor name at gate

                                <input
                                    name="visitorName"
                                    required
                                    minLength={2}
                                    maxLength={170}
                                    defaultValue={
                                        appointment.visitor
                                    }
                                />
                            </label>

                            <label>
                                Identity document

                                <select
                                    name="identityDocumentType"
                                    defaultValue=""
                                >
                                    <option value="">
                                        Not provided
                                    </option>
                                    <option>Passport</option>
                                    <option>Aadhaar</option>
                                    <option>
                                        Driving licence
                                    </option>
                                    <option>Company ID</option>
                                    <option>
                                        Other government ID
                                    </option>
                                </select>
                            </label>

                            <label>
                                Last four characters

                                <input
                                    name="identityDocumentLastFour"
                                    minLength={4}
                                    maxLength={4}
                                    pattern="[A-Za-z0-9]{4}"
                                    placeholder="A123"
                                    autoComplete="off"
                                />
                            </label>

                            <label className="full-field">
                                Purpose confirmed at gate

                                <textarea
                                    name="purpose"
                                    required
                                    minLength={5}
                                    maxLength={1000}
                                    defaultValue={
                                        appointment.purpose
                                    }
                                />
                            </label>

                            <label className="full-field">
                                Security notes

                                <textarea
                                    name="notes"
                                    maxLength={500}
                                    placeholder="Photo identity matched, vehicle number, accessibility assistance…"
                                />
                            </label>
                        </div>

                        {error && (
                            <div
                                className="login-error"
                                role="alert"
                            >
                                {error}
                            </div>
                        )}
                    </div>

                    <div className="modal-actions">
                        <button
                            type="button"
                            className="button button-secondary"
                            onClick={onClose}
                        >
                            Cancel
                        </button>

                        <button
                            type="submit"
                            className="button button-primary"
                            disabled={busy}
                        >
                            <Send size={17} />

                            {busy
                                ? "Recording…"
                                : "Record & notify Reception"}
                        </button>
                    </div>
                </form>
            </section>
        </div>
    );
}

