const GUEST_ID_KEY = "loomframe-guest-id";

export function getGuestId(): string {
  if (typeof window === "undefined") return "";
  let id = localStorage.getItem(GUEST_ID_KEY);
  if (!id) {
    id = generateGuestId();
    localStorage.setItem(GUEST_ID_KEY, id);
  }
  return id;
}

export function setGuestId(id: string): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(GUEST_ID_KEY, id);
}

function generateGuestId(): string {
  const ts = Date.now().toString(36);
  const rand = Math.random().toString(36).substring(2, 10);
  return `g_${ts}${rand}`;
}
