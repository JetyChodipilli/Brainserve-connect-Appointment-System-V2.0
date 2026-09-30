export type NotificationSoundKind = "message" | "visitor" | "appointment" | "approval" | "action";

const SOUND_ENABLED_KEY = "brainserve.notification.sound.enabled.v1";
const SOUND_CHANGE_EVENT = "brainserve:notification-sound-change";
let audioContext: AudioContext | null = null;
let lastPlayedAt = 0;

export function notificationSoundEnabled() {
  if (typeof window === "undefined") return true;
  return window.localStorage.getItem(SOUND_ENABLED_KEY) !== "false";
}

export function setNotificationSoundEnabled(enabled: boolean) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(SOUND_ENABLED_KEY, String(enabled));
  window.dispatchEvent(new CustomEvent(SOUND_CHANGE_EVENT, { detail: enabled }));
  if (enabled) void playNotificationSound("action", true);
}

export function onNotificationSoundChange(listener: (enabled: boolean) => void) {
  if (typeof window === "undefined") return () => undefined;
  const receive = (event: Event) => listener((event as CustomEvent<boolean>).detail);
  window.addEventListener(SOUND_CHANGE_EVENT, receive);
  return () => window.removeEventListener(SOUND_CHANGE_EVENT, receive);
}

const patterns: Record<NotificationSoundKind, Array<[number, number, number]>> = {
  message: [[660, 0, .09], [880, .11, .12]],
  visitor: [[523, 0, .08], [659, .1, .08], [784, .2, .16]],
  appointment: [[440, 0, .11], [554, .13, .11]],
  approval: [[587, 0, .08], [740, .1, .08], [988, .2, .18]],
  action: [[494, 0, .08], [622, .1, .1]],
};

/** Plays short synthesized cues so the application needs no downloadable audio assets. */
export async function playNotificationSound(kind: NotificationSoundKind, force = false) {
  if (typeof window === "undefined" || (!force && !notificationSoundEnabled())) return false;
  if (document.visibilityState === "hidden" && !force) return false;
  const now = Date.now();
  if (!force && now - lastPlayedAt < 900) return false;
  try {
    audioContext ??= new AudioContext();
    if (audioContext.state === "suspended") await audioContext.resume();
    const start = audioContext.currentTime + .015;
    patterns[kind].forEach(([frequency, offset, duration]) => {
      const oscillator = audioContext!.createOscillator();
      const gain = audioContext!.createGain();
      oscillator.type = "sine";
      oscillator.frequency.value = frequency;
      gain.gain.setValueAtTime(.0001, start + offset);
      gain.gain.exponentialRampToValueAtTime(.12, start + offset + .012);
      gain.gain.exponentialRampToValueAtTime(.0001, start + offset + duration);
      oscillator.connect(gain).connect(audioContext!.destination);
      oscillator.start(start + offset);
      oscillator.stop(start + offset + duration + .02);
    });
    lastPlayedAt = now;
    return true;
  } catch {
    // Browsers can block audio until the user interacts with the page.
    return false;
  }
}
