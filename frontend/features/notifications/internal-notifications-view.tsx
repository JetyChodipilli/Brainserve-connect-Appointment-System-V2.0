"use client";

import {
    brainServeApi,
    type InternalNotification,
    type InternalNotificationRecipient,
    isBackendConfigured,
    isWorkspaceUpdateLeader,
} from "../../services/brainserve-api";
import { formatOfficeDate, officeToday } from "../../lib/appointments";
import { readableNotificationRole } from "../../lib/internal-notifications";
import { readDemoEssentialLogs, writeDemoEssentialLogs } from "../../preview/governance";
import {
    demoInternalRecipients,
    demoSenderName,
    readDemoInternalNotifications,
    writeDemoInternalNotifications,
} from "../../preview/notifications";
import { DEMO_ACCOUNTS_KEY, DEMO_INTERNAL_NOTIFICATIONS_KEY } from "../../preview/storage-keys";
import { type DemoInternalNotification } from "../../preview/types";
import { PageTitle } from "../../components/ui/page-title";
import { type Role } from "../../types/workspace";
import { newClientId } from "../../utils/ids";
import { visitorInitials } from "../appointments/appointment-utils";
import { ResourceDiscussionWorkspace } from "../discussions/resource-discussion-workspace";
import { LeaveWorkspace } from "../leave/leave-workspace";
import { NotificationPreferencesPanel } from './components/notification-preferences-panel';
import { ApprovalQueuePanel } from './components/approval-queue-panel';
import { Archive, Bell, CalendarDays, CheckCircle2, Inbox, MessageSquare, RotateCcw, Search, Send, Trash2 } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";

export function InternalNotificationsView({ role, userEmail, onUnreadChange }: {
    role: Role; userEmail: string; onUnreadChange: (count: number) => void;
}) {
    const [recipients, setRecipients] = useState<InternalNotificationRecipient[]>([]);
    const [inbox, setInbox] = useState<InternalNotification[]>([]);
    const [sent, setSent] = useState<InternalNotification[]>([]);
    const [archive, setArchive] = useState<InternalNotification[]>([]);
    const [archivePage, setArchivePage] = useState(0);
    const [archiveHasMore, setArchiveHasMore] = useState(false);
    const [archiveSort, setArchiveSort] = useState<"NEWEST" | "OLDEST" | "PRIORITY">("NEWEST");
    const [recipientId, setRecipientId] = useState("");
    const [draft, setDraft] = useState("");
    const [priority, setPriority] = useState<NonNullable<InternalNotification["priority"]>>("NORMAL");
    const [category, setCategory] = useState<NonNullable<InternalNotification["category"]>>("GENERAL");
    const [tab, setTab] = useState<"priority" | "conversations" | "sent" | "archive">("priority");
    const [filter, setFilter] = useState<"ALL" | "UNREAD" | "URGENT" | "HIGH">("ALL");
    const [query, setQuery] = useState("");
    const [selectedConversation, setSelectedConversation] = useState("");
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [loadError, setLoadError] = useState("");
    const [refreshWarning, setRefreshWarning] = useState("");
    const [policyPanel, setPolicyPanel] = useState<'preferences' | 'approvals' | null>(null);
    const [recipientLoadFailed, setRecipientLoadFailed] = useState(false);
    const notificationLoadInFlightRef = useRef(false);
    const notificationsLoadedRef = useRef(false);

    const loadData = useCallback(async (includeArchive = false) => {
        if (notificationLoadInFlightRef.current) return;
        notificationLoadInFlightRef.current = true;
        try {
            if (!isBackendConfigured) {
                const all = readDemoInternalNotifications();
                const today = officeToday();
                const isToday = (item: InternalNotification) => officeToday(new Date(item.sentAt)) === today;
                const nextRecipients = demoInternalRecipients(role, userEmail);
                const nextInbox = all.filter((item) => item.recipientEmail === userEmail && isToday(item));
                const nextSent = all.filter((item) => item.senderEmail === userEmail && isToday(item));
                const nextArchive = all.filter((item) => (item.senderEmail === userEmail || item.recipientEmail === userEmail) && !isToday(item));
                setRecipients(nextRecipients); setInbox(nextInbox); setSent(nextSent); setArchive(nextArchive);
                setArchivePage(0); setArchiveHasMore(false);
                setRecipientId((current) => nextRecipients.some((item) => item.userId === current)
                    ? current : nextRecipients[0]?.userId ?? "");
                onUnreadChange(nextInbox.filter((item) => !item.readAt).length);
                setRecipientLoadFailed(false);
                notificationsLoadedRef.current = true;
                setLoadError(""); setRefreshWarning("");
                return;
            }

            const [recipientResult] = await Promise.allSettled([
                brainServeApi.internalNotificationRecipients(),
            ] as const);
            const [inboxResult, sentResult, archiveResult] = await Promise.allSettled([
                brainServeApi.internalNotificationInbox(),
                brainServeApi.internalNotificationSent(),
                includeArchive ? brainServeApi.internalNotificationArchive() : Promise.resolve(null),
            ] as const);
            const failures: string[] = [];
            let successfulSections = 0;

            if (recipientResult.status === "fulfilled") {
                const nextRecipients = recipientResult.value;
                setRecipients(nextRecipients);
                setRecipientId((current) => nextRecipients.some((item) => item.userId === current)
                    ? current : nextRecipients[0]?.userId ?? "");
                setRecipientLoadFailed(false);
                successfulSections += 1;
            } else {
                setRecipientLoadFailed(true);
                failures.push("recipients");
            }

            if (inboxResult.status === "fulfilled") {
                setInbox(inboxResult.value);
                onUnreadChange(inboxResult.value.filter((item) => !item.readAt).length);
                successfulSections += 1;
            } else failures.push("inbox");

            if (sentResult.status === "fulfilled") {
                setSent(sentResult.value);
                successfulSections += 1;
            } else failures.push("sent messages");

            if (archiveResult.status === "fulfilled" && archiveResult.value !== null) {
                setArchive(archiveResult.value);
                setArchivePage(0); setArchiveHasMore(archiveResult.value.length === 50);
                successfulSections += 1;
            } else if (includeArchive && archiveResult.status === "rejected") failures.push("archive");

            if (successfulSections > 0) {
                notificationsLoadedRef.current = true;
                setLoadError("");
                setRefreshWarning(failures.length
                    ? `Some sections could not refresh (${failures.join(", ")}). Other message data remains available.` : "");
            } else {
                const fallback = "Notifications are waiting for the backend service to recover.";
                if (notificationsLoadedRef.current) setRefreshWarning(`${fallback} The last successful message data remains visible.`);
                else setLoadError(fallback);
            }
        } catch (reason) {
            const detail = reason instanceof Error ? reason.message : "Notifications could not be refreshed.";
            if (notificationsLoadedRef.current) setRefreshWarning(`${detail} The last successful message data remains visible.`);
            else setLoadError(detail);
        } finally {
            notificationLoadInFlightRef.current = false;
        }
    }, [onUnreadChange, role, userEmail]);

    useEffect(() => {
        const initialLoad = window.setTimeout(() => void loadData(true), 0);
        const refresh = () => {
            if (document.visibilityState === "visible") void loadData(false);
        };
        const timer = window.setInterval(() => {
            if (isWorkspaceUpdateLeader()) refresh();
        }, 30000);
        const refreshFromStorage = (event: StorageEvent) => {
            if (!event.key || [DEMO_ACCOUNTS_KEY, DEMO_INTERNAL_NOTIFICATIONS_KEY].includes(event.key)) refresh();
        };
        const refreshWhenVisible = () => { if (document.visibilityState === "visible") refresh(); };
        window.addEventListener("focus", refresh);
        window.addEventListener("storage", refreshFromStorage);
        window.addEventListener("brainserve:demo-accounts-updated", refresh);
        window.addEventListener("brainserve:demo-internal-notifications-updated", refresh);
        document.addEventListener("visibilitychange", refreshWhenVisible);
        return () => {
            window.clearTimeout(initialLoad); window.clearInterval(timer);
            window.removeEventListener("focus", refresh);
            window.removeEventListener("storage", refreshFromStorage);
            window.removeEventListener("brainserve:demo-accounts-updated", refresh);
            window.removeEventListener("brainserve:demo-internal-notifications-updated", refresh);
            document.removeEventListener("visibilitychange", refreshWhenVisible);
        };
    }, [loadData]);

    const sendCall = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(true); setError(""); setMessage("");
        const recipient = recipients.find((item) => item.userId === recipientId);
        if (!recipient || draft.trim().length < 2) { setError("Choose a recipient and enter a message."); setBusy(false); return; }
        try {
            let created: InternalNotification;
            if (isBackendConfigured) created = await brainServeApi.sendInternalNotification(recipient.userId, draft.trim(), priority, category);
            else {
                const now = new Date().toISOString();
                const demo: DemoInternalNotification = {
                    id: newClientId(), senderUserId: userEmail, recipientUserId: recipient.userId,
                    senderName: demoSenderName(role, userEmail), recipientName: recipient.fullName,
                    message: draft.trim().replace(/\s+/g, " "), priority, category,
                    conversationKey: [userEmail, recipient.userId].sort().join(":"), deliveryStatus: "DELIVERED", sentAt: now,
                    deliveredAt: now, readAt: null, senderEmail: userEmail, recipientEmail: recipient.email,
                };
                writeDemoInternalNotifications([demo, ...readDemoInternalNotifications()]);
                created = demo;
            }
            setSent((items) => [created, ...items]); setDraft(""); setTab("conversations");
            setSelectedConversation(created.conversationKey ?? [created.senderUserId, created.recipientUserId].sort().join(":"));
            setMessage(`${priority === "URGENT" ? "Urgent" : priority === "HIGH" ? "High-priority" : "Message"} call sent to ${recipient.fullName}.`);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The internal call could not be sent."); }
        finally { setBusy(false); }
    };

    const markRead = async (notification: InternalNotification) => {
        if (notification.readAt) return;
        try {
            const updated = isBackendConfigured
                ? await brainServeApi.markInternalNotificationRead(notification.id)
                : { ...notification, deliveryStatus: "DELIVERED" as const, readAt: new Date().toISOString() };
            if (!isBackendConfigured) {
                writeDemoInternalNotifications(readDemoInternalNotifications().map((item) => item.id === updated.id
                    ? { ...item, ...updated, senderEmail: item.senderEmail, recipientEmail: item.recipientEmail } : item));
            }
            setInbox((items) => items.map((item) => item.id === updated.id ? updated : item));
            onUnreadChange(inbox.filter((item) => item.id !== updated.id && !item.readAt).length);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The notification could not be marked as read."); }
    };

    const deleteArchived = async (notification: InternalNotification) => {
        if (officeToday(new Date(notification.sentAt)) === officeToday()) {
            setError("Today's messages cannot be deleted."); return;
        }
        if (!window.confirm("Delete this archived message? System Admin will retain an immutable deletion log.")) return;
        try {
            if (isBackendConfigured) await brainServeApi.deleteInternalNotification(notification.id);
            else {
                writeDemoInternalNotifications(readDemoInternalNotifications().filter((item) => item.id !== notification.id));
                writeDemoEssentialLogs([{ id: newClientId(), category: "INTERNAL_COMMUNICATION", eventType: "ARCHIVED_MESSAGE_DELETED",
                    subjectType: "INTERNAL_NOTIFICATION", subjectId: notification.id, referenceId: notification.conversationKey ?? null,
                    actorUserId: userEmail, approverUserId: null, status: "DELETED", title: "Archived internal message deleted",
                    detail: `${notification.senderName} → ${notification.recipientName} · ${notification.sentAt} · ${notification.message}`,
                    occurredAt: new Date().toISOString() }, ...readDemoEssentialLogs()]);
            }
            setArchive((items) => items.filter((item) => item.id !== notification.id));
            setMessage("Archived message deleted. An immutable System Admin log was created."); setError("");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The archived message could not be deleted."); }
    };

    const loadMoreArchive = async () => {
        if (!isBackendConfigured || busy) return;
        setBusy(true); setError("");
        try {
            const nextPage = archivePage + 1; const nextItems = await brainServeApi.internalNotificationArchive(nextPage, 50);
            setArchive((items) => [...items, ...nextItems.filter((next) => !items.some((item) => item.id === next.id))]);
            setArchivePage(nextPage); setArchiveHasMore(nextItems.length === 50);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "More archived messages could not be loaded."); }
        finally { setBusy(false); }
    };

    const selectedRecipient = recipients.find((item) => item.userId === recipientId);
    const replyingToCeo = role === "HR Admin" && selectedRecipient?.roles.includes("ROLE_CEO");
    const quickMessages = role === "CEO" ? ["Please come to my cabin.", "Please meet me when you are free."]
        : replyingToCeo ? ["I’m coming.", "I’ll be there shortly."]
            : role === "HR Admin" ? ["Please come to the HR cabin.", "Please report to reception."]
                : role === "Reception" ? ["Acknowledged. Reception will coordinate this.", "The visitor has arrived at Reception.", "I’ll notify you when they are ready."]
                    : ["Could you please meet me in HR?", "I need to discuss an HR matter."];

    const priorityRank = (value?: InternalNotification["priority"]) => value === "URGENT" ? 3 : value === "HIGH" ? 2 : 1;
    const messageKey = (item: InternalNotification) => item.conversationKey
        ?? [item.senderUserId, item.recipientUserId].sort().join(":");
    const incomingIds = useMemo(() => new Set(inbox.map((item) => item.id)), [inbox]);
    const sortedInbox = useMemo(() => [...inbox].sort((left, right) => {
        if (Boolean(left.readAt) !== Boolean(right.readAt)) return left.readAt ? 1 : -1;
        const priorityDifference = priorityRank(right.priority) - priorityRank(left.priority);
        return priorityDifference || new Date(right.sentAt).getTime() - new Date(left.sentAt).getTime();
    }), [inbox]);
    const priorityInbox = useMemo(() => sortedInbox.filter((item) => {
        const matchesQuery = !query.trim() || `${item.senderName} ${item.message} ${item.category ?? "GENERAL"}`
            .toLowerCase().includes(query.trim().toLowerCase());
        const matchesFilter = filter === "ALL" || (filter === "UNREAD" && !item.readAt)
            || item.priority === filter;
        return matchesQuery && matchesFilter;
    }), [filter, query, sortedInbox]);
    const allMessages = useMemo(() => [...inbox, ...sent]
        .sort((left, right) => new Date(left.sentAt).getTime() - new Date(right.sentAt).getTime()), [inbox, sent]);
    const conversations = useMemo(() => {
        const grouped = new Map<string, { key: string; name: string; latest: InternalNotification;
            roles: string[]; unread: number; priority: number }>();
        allMessages.forEach((item) => {
            const key = messageKey(item); const incoming = incomingIds.has(item.id);
            const current = grouped.get(key); const nextRank = priorityRank(item.priority);
            grouped.set(key, { key, name: incoming ? item.senderName : item.recipientName, latest: item,
                roles: incoming ? item.senderRoles ?? [] : item.recipientRoles ?? [],
                unread: (current?.unread ?? 0) + (incoming && !item.readAt ? 1 : 0),
                priority: Math.max(current?.priority ?? 0, nextRank) });
        });
        return [...grouped.values()].filter((item) => !query.trim()
            || `${item.name} ${item.latest.message}`.toLowerCase().includes(query.trim().toLowerCase()))
            .sort((left, right) => right.unread - left.unread || right.priority - left.priority
                || new Date(right.latest.sentAt).getTime() - new Date(left.latest.sentAt).getTime());
    }, [allMessages, incomingIds, query]);
    const activeConversation = selectedConversation || conversations[0]?.key || "";
    const thread = allMessages.filter((item) => messageKey(item) === activeConversation);
    const sentVisible = [...sent].filter((item) => !query.trim()
        || `${item.recipientName} ${item.message}`.toLowerCase().includes(query.trim().toLowerCase()))
        .sort((left, right) => new Date(right.sentAt).getTime() - new Date(left.sentAt).getTime());
    const archivedVisible = [...archive].filter((item) => !query.trim()
        || `${item.senderName} ${item.recipientName} ${item.message} ${item.category ?? "GENERAL"}`.toLowerCase().includes(query.trim().toLowerCase()))
        .sort((left, right) => archiveSort === "OLDEST"
            ? new Date(left.sentAt).getTime() - new Date(right.sentAt).getTime()
            : archiveSort === "PRIORITY"
                ? priorityRank(right.priority) - priorityRank(left.priority) || new Date(right.sentAt).getTime() - new Date(left.sentAt).getTime()
                : new Date(right.sentAt).getTime() - new Date(left.sentAt).getTime());
    const reply = (item: InternalNotification) => {
        const recipient = recipients.find((value) => value.userId === item.senderUserId);
        if (!recipient) return;
        setRecipientId(recipient.userId); setPriority(item.priority ?? "NORMAL");
        setCategory(item.category ?? "GENERAL"); setDraft("");
        document.getElementById("internal-message-composer")?.scrollIntoView({ behavior: "smooth", block: "start" });
    };
    const renderMessage = (item: InternalNotification, incoming: boolean, archived = false) => {
        const participantRoles = incoming ? item.senderRoles ?? [] : item.recipientRoles ?? [];
        const participantRole = participantRoles.map(readableNotificationRole).join(", ");
        const categoryLabel = (item.category ?? "GENERAL").replaceAll("_", " ");
        return <article
            className={`${!item.readAt && incoming ? "internal-message unread" : "internal-message"} priority-${(item.priority ?? "NORMAL").toLowerCase()}`} key={item.id}>
            <span className="avatar">{visitorInitials(incoming ? item.senderName : item.recipientName)}</span>
            <div><header><span><strong>{incoming ? item.senderName : item.recipientName}</strong><small>{participantRole ? `${participantRole} · ${categoryLabel}` : categoryLabel}</small></span><time>{new Date(item.sentAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</time></header>
                <p>{item.message}</p><footer><span className={`message-priority priority-badge-${(item.priority ?? "NORMAL").toLowerCase()}`}>{item.priority ?? "NORMAL"}</span><span className={`delivery-${item.deliveryStatus.toLowerCase()}`}>{item.deliveryStatus.toLowerCase()}</span>
                    <span className="message-actions">{incoming && !archived && <button type="button" onClick={() => reply(item)}>Reply</button>}{incoming && !item.readAt && !archived && <button type="button" onClick={() => void markRead(item)}>Mark read</button>}{archived && <button type="button" className="message-delete" aria-label={`Delete archived message from ${item.senderName}`} title="Delete this old message" onClick={() => void deleteArchived(item)}><Trash2 size={14} /> Delete old message</button>}</span></footer></div>
        </article>;
    };

    return <section className="internal-notifications-page">
        <PageTitle eyebrow="BRAINSERVE INTERNAL DELIVERY" title="Priority calls & conversations"
                   detail="Today’s urgent and unread requests rise to the top. Earlier messages are stored safely in Archive." />
        <div className='notification-policy-actions'><button type='button' className='button button-secondary' aria-expanded={policyPanel === 'preferences'} onClick={() => setPolicyPanel(p => p === 'preferences' ? null : 'preferences')}>Delivery preferences</button><button type='button' className='button button-secondary' aria-expanded={policyPanel === 'approvals'} onClick={() => setPolicyPanel(p => p === 'approvals' ? null : 'approvals')}>Approval deadlines & delegation</button></div>
        {policyPanel === 'preferences' && <NotificationPreferencesPanel key={`${role}:${userEmail}`} />}
        {policyPanel === 'approvals' && <ApprovalQueuePanel key={`${role}:${userEmail}`} role={role} />}
        {(loadError || refreshWarning) && <div className={`work-refresh-state ${loadError ? "is-error" : "is-warning"}`} role={loadError ? "alert" : "status"}><RotateCcw size={17} aria-hidden="true" /><span><strong>{loadError ? "Message service is reconnecting" : "Some message data is temporarily stale"}</strong><small>{loadError || refreshWarning} This view refreshes automatically.</small></span></div>}
        <div className="notification-attention glass-panel"><div><strong>{inbox.filter((item) => !item.readAt).length}</strong><span>Unread</span><small>Needs acknowledgement</small></div><i /><div><strong>{inbox.filter((item) => item.priority === "URGENT" && !item.readAt).length}</strong><span>Urgent</span><small>Handle first</small></div><i /><div><strong>{conversations.length}</strong><span>Conversations</span><small>Grouped by person</small></div></div>
        <div className="notification-workspace">
            <article className="panel glass-panel notification-composer" id="internal-message-composer">
                <div className="panel-heading"><div><span>NEW INTERNAL DELIVERY</span><h2>Send a prioritized call</h2><p>Add purpose and urgency so the recipient knows what to handle first.</p></div><Send size={21} /></div>
                {recipients.length ? <form onSubmit={sendCall}>
                    <label>Recipient<select value={recipientId} onChange={(event) => setRecipientId(event.target.value)} required>
                        {recipients.map((recipient) => <option value={recipient.userId} key={recipient.userId}>{recipient.fullName} · {recipient.roles.map(readableNotificationRole).join(", ")}</option>)}
                    </select></label>
                    <div className="notification-routing-fields"><label>Priority<select value={priority} onChange={(event) => setPriority(event.target.value as NonNullable<InternalNotification["priority"]>)}><option value="NORMAL">Normal</option><option value="HIGH">High</option><option value="URGENT">Urgent</option></select></label><label>Purpose<select value={category} onChange={(event) => setCategory(event.target.value as NonNullable<InternalNotification["category"]>)}><option value="GENERAL">General</option><option value="ACTION_REQUIRED">Action required</option><option value="VISITOR">Visitor coordination</option><option value="WORK">Work update</option><option value="INSIGHT">Insight review</option><option value="LEAVE">Leave</option></select></label></div>
                    <label>Message<textarea value={draft} onChange={(event) => setDraft(event.target.value)} minLength={2} maxLength={500}
                                            placeholder="For example: Please come to my cabin." required /></label>
                    <div className="quick-message-list">{quickMessages.map((value) => <button type="button" key={value} onClick={() => setDraft(value)}>{value}</button>)}</div>
                    <div className="message-limit"><span>{priority === "URGENT" ? "Urgent messages are placed first" : "BrainServe Connect real-time delivery"}</span><span>{draft.length}/500</span></div>
                    <button className="button button-primary" disabled={busy}><Send size={16} />{busy ? "Sending…" : "Send internal call"}</button>
                </form> : recipientLoadFailed
                    ? <div className="empty-state notification-policy-empty" role="status"><RotateCcw size={28} /><strong>Recipient directory is reconnecting</strong><small>Your permitted recipients will return automatically; no role access has been removed.</small></div>
                    : <div className="empty-state notification-policy-empty"><MessageSquare size={28} /><strong>No sending route for this role</strong><small>No active permitted recipients are available for your role.</small></div>}
            </article>

            <article className="panel glass-panel notification-inbox-panel">
                <div className="notification-day-banner"><CalendarDays size={16} /><span><strong>Today · {formatOfficeDate(new Date().toISOString())}</strong><small>Only today’s messages appear in the operational inbox.</small></span><button type="button" className="archive-shortcut" onClick={() => { setTab("archive"); void loadData(true); }}><Archive size={14} />Old messages{archive.length > 0 && <b>{archive.length}{archiveHasMore ? "+" : ""}</b>}</button></div>
                <div className="notification-tabs"><button className={tab === "priority" ? "active" : ""} onClick={() => setTab("priority")}><Bell size={16} />Priority inbox{inbox.filter((item) => !item.readAt).length > 0 && <b>{inbox.filter((item) => !item.readAt).length}</b>}</button><button className={tab === "conversations" ? "active" : ""} onClick={() => setTab("conversations")}><MessageSquare size={16} />Today’s conversations</button><button className={tab === "sent" ? "active" : ""} onClick={() => setTab("sent")}><Send size={16} />Sent today</button><button className={tab === "archive" ? "active" : ""} onClick={() => { setTab("archive"); void loadData(true); }}><Archive size={16} />Archive</button></div>
                <div className="notification-search"><Search size={15} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search people or message text" />{tab === "priority" && <select value={filter} onChange={(event) => setFilter(event.target.value as typeof filter)}><option value="ALL">All priorities</option><option value="UNREAD">Unread only</option><option value="URGENT">Urgent</option><option value="HIGH">High</option></select>}{tab === "archive" && <select value={archiveSort} onChange={(event) => setArchiveSort(event.target.value as typeof archiveSort)}><option value="NEWEST">Newest first</option><option value="OLDEST">Oldest first</option><option value="PRIORITY">Priority first</option></select>}</div>
                {tab === "priority" && <div className="internal-message-list">{priorityInbox.map((item) => renderMessage(item, true))}{priorityInbox.length === 0 && <div className="empty-state"><CheckCircle2 size={28} /><strong>Your priority queue is clear</strong><small>Try another filter or wait for a new internal delivery.</small></div>}</div>}
                {tab === "sent" && <div className="internal-message-list">{sentVisible.map((item) => renderMessage(item, false))}{sentVisible.length === 0 && <div className="empty-state"><Send size={28} /><strong>No sent calls found</strong><small>New internal calls remain searchable here.</small></div>}</div>}
                {tab === "archive" && <div className="internal-message-list"><div className="archive-policy-note"><Archive size={16} /><span><strong>Previous messages · {archive.length}{archiveHasMore ? "+" : ""}</strong><small>Use Delete on any old message. Today’s messages are protected and cannot be deleted.</small></span></div>{archivedVisible.map((item) => renderMessage(item, false, true))}{archive.length > 0 && archivedVisible.length === 0 && <div className="empty-state"><Search size={28} /><strong>No archived messages match your search</strong><small>Clear the search or choose another sorting option.</small></div>}{archive.length === 0 && <div className="empty-state"><Archive size={28} /><strong>No archived messages</strong><small>Messages move here automatically after the office day ends.</small></div>}{archiveHasMore && <button type="button" className="button button-secondary archive-load-more" disabled={busy} onClick={() => void loadMoreArchive()}>{busy ? "Loading…" : "Load 50 more messages"}</button>}</div>}
                {tab === "conversations" && <div className="conversation-layout"><div className="conversation-list">{conversations.map((conversation) => <button className={activeConversation === conversation.key ? "active" : ""} key={conversation.key} onClick={() => setSelectedConversation(conversation.key)}><span className="avatar">{visitorInitials(conversation.name)}</span><span><strong>{conversation.name}</strong><small>{conversation.roles.length ? `${conversation.roles.map(readableNotificationRole).join(", ")} · ${conversation.latest.message}` : conversation.latest.message}</small></span>{conversation.unread > 0 && <b>{conversation.unread}</b>}</button>)}{conversations.length === 0 && <div className="empty-state"><MessageSquare size={26} /><strong>No conversations</strong></div>}</div><div className="conversation-thread">{thread.map((item) => renderMessage(item, incomingIds.has(item.id)))}{thread.length === 0 && <div className="empty-state"><Inbox size={28} /><strong>Select a conversation</strong><small>The complete message history will appear here.</small></div>}</div></div>}
            </article>
        </div>
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
        {(["Team Lead", "HR Admin", "CEO"] as Role[]).includes(role)
            && <ResourceDiscussionWorkspace role={role} recipients={recipients} />}
        {(["Employee", "HR Admin"] as Role[]).includes(role) && <LeaveWorkspace role={role} />}
    </section>;
}
