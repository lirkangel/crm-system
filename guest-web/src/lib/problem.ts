export interface ProblemLike {
  code?: string;
  status: number;
}

/** Maps a server problem to an i18n key. */
export function messageKey(problem: ProblemLike): string {
  switch (problem.code) {
    case "HOTEL_SCAN_UNREADABLE":
      return "scan.unreadable";
    case "HOTEL_SCAN_BUSY":
      return "common.busy";
    case "HOTEL_CHECKIN_CLOSED":
      return "closed";
    case "HOTEL_TOO_MANY_GUESTS":
      return "adults.full";
    case "HOTEL_SCAN_MISMATCH":
      return "scan.mismatch";
    case "HOTEL_SCAN_LIMIT":
      return "scan.limit";
    case "HOTEL_INVALID_ID_DOC":
      return "identity.invalidDoc";
    case "VALIDATION_ERROR":
      return "identity.invalid";
  }
  if (problem.status === 429) return "common.busy";
  if (problem.status === 404) return "invalid";
  return "common.error";
}
