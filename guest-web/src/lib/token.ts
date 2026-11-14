export type LinkKind = "hotel" | "dental";
export interface Link {
  kind: LinkKind;
  token: string;
}

const DENTAL_PREFIX = "d/";

/**
 * Reads the personal link token from the URL fragment and drops the fragment from the address bar so it is not left
 * in history, screenshots or a copied URL. A fragment `#d/<token>` is a dental pre-visit link; any other fragment
 * is a hotel check-in token, as before. The token is never persisted (no storage, no cookie).
 */
export function readToken(location: Location): Link | null {
  const hash = location.hash.replace(/^#/, "");
  if (location.hash !== "") {
    window.history.replaceState(null, "", location.pathname + location.search);
  }
  if (hash.startsWith(DENTAL_PREFIX)) {
    const token = hash.slice(DENTAL_PREFIX.length);
    return token === "" ? null : { kind: "dental", token };
  }
  return hash === "" ? null : { kind: "hotel", token: hash };
}

// The link lives only in this module variable: the fragment is gone after the first read.
let held: Link | null = null;

/** Takes the link from the URL fragment the first time; later calls keep the one already held. */
export function claimLink(location: Location): Link | null {
  if (held) return held;
  held = readToken(location);
  return held;
}

export function resetLink(): void {
  held = null;
}
