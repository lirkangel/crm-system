/**
 * Reads the personal link token from the URL fragment (`/#<token>`) and drops the fragment from the address bar so
 * it is not left in history, screenshots or a copied URL. The token is never persisted (no storage, no cookie).
 */
export function readToken(location: Location): string | null {
  const token = location.hash.replace(/^#/, "");
  if (location.hash !== "") {
    window.history.replaceState(null, "", location.pathname + location.search);
  }
  return token === "" ? null : token;
}
