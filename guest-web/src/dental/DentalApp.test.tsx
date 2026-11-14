import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import i18n from "@/i18n";
import App from "@/App";
import { resetVisitToken } from "@/api/visit";

const mockToJpeg = vi.fn();
vi.mock("@/lib/photo", async (orig) => ({
  ...(await orig<typeof import("@/lib/photo")>()),
  toUploadJpeg: (...a: unknown[]) => mockToJpeg(...a),
}));

const BASE = "/api/v1/plugins/dental/public/visit";

const summary = {
  clinicName: "Nha khoa Nụ Cười",
  startsAt: "2026-11-20T09:30:00+07:00",
  durationMinutes: 30,
  dentist: "BS. Lan",
  maskedName: "N***** V** A*",
  open: true,
  submitted: false,
  returning: "NONE",
  child: false,
};

const previous = {
  allergies: { none: false, items: ["PENICILLIN", "OTHER"], otherText: "Hạt điều" },
  medicines: { none: false, text: "Metformin 500mg" },
  reason: { code: "PAIN", text: "Đau răng hàm" },
  fromCurrentAppointment: false,
};

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ success: true, code: "SUCCESS", message: "success", data }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return new Response(JSON.stringify({ status, code, title: code, ...extra }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}
const created = () => new Response(null, { status: 201 });

let fetchMock: ReturnType<typeof vi.fn>;
type Handler = (init?: RequestInit) => Response | Promise<Response>;
function serve(routes: { get?: Handler; form?: Handler; previous?: Handler; scan?: Handler }) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    const u = String(url);
    const method = init?.method ?? "GET";
    if (u === BASE && method === "GET" && routes.get) return routes.get(init);
    if (u === BASE + "/form" && method === "POST" && routes.form) return routes.form(init);
    if (u === BASE + "/previous" && method === "POST" && routes.previous) return routes.previous(init);
    if (u === BASE + "/id-scans" && method === "POST" && routes.scan) return routes.scan(init);
    throw new Error("unexpected " + method + " " + u);
  });
}
const calls = (suffix: string) => fetchMock.mock.calls.filter(([url]) => String(url) === BASE + suffix);
const sentForm = () => JSON.parse(calls("/form")[0][1].body as string);

const t = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const button = (key: string) => screen.findByRole("button", { name: t(key) });
const click = async (key: string) => userEvent.click(await button(key));

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  resetVisitToken();
  window.history.replaceState(null, "", "/#d/tok-123");
  mockToJpeg.mockReset();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

async function openSummary(s: object = summary) {
  serve({ get: () => ok(s) });
  render(<App />);
  await screen.findByText(summary.maskedName);
}

async function fillIdentity() {
  await userEvent.type(screen.getByLabelText(t("field.idNumber")), "079090001234");
  await userEvent.type(screen.getByLabelText(t("field.fullName")), "NGUYEN VAN A");
  await userEvent.type(screen.getByLabelText(t("field.dateOfBirth")), "1990-05-17");
  await userEvent.type(screen.getByLabelText(t("field.address")), "12 Le Loi, Q1");
}

/** From the summary to the consent step with a complete adult form (none/none, CHECKUP). */
async function fillToConsent() {
  await click("dental.start");
  await fillIdentity();
  await click("dental.next");
  await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
  await click("dental.next");
  await userEvent.click(await screen.findByLabelText(t("dental.allergies.none")));
  await click("dental.next");
  await userEvent.click(await screen.findByLabelText(t("dental.medicines.none")));
  await click("dental.next");
  await userEvent.click(await screen.findByLabelText(t("dental.reason.CHECKUP")));
  await click("dental.next");
}

describe("DentalApp", () => {
  it("dental_link_routes_to_dental_flow", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    serve({ get: () => ok(summary) });
    render(<App />);
    expect(await screen.findByText(summary.maskedName)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(summary.clinicName);
    expect(document.title).toBe("Khai báo trước khi khám");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(BASE);
    expect(init.headers["X-Visit-Token"]).toBe("tok-123");
    expect(init.credentials).toBe("omit");
    expect(init.cache).toBe("no-store");
    expect(window.location.hash).toBe("");
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  it("unknown_link_shows_invalid_and_closed_shows_closed", async () => {
    serve({ get: () => problem(404, "DENTAL_NOT_FOUND") });
    const first = render(<App />);
    expect(await screen.findByText(t("dental.invalid"))).toBeInTheDocument();
    first.unmount();

    resetVisitToken();
    window.history.replaceState(null, "", "/#d/tok-123");
    serve({ get: () => ok({ ...summary, open: false }) });
    render(<App />);
    expect(await screen.findByText(t("dental.closed"))).toBeInTheDocument();
  });

  it("allergies_requires_none_or_items", async () => {
    await openSummary();
    await click("dental.start");
    await fillIdentity();
    await click("dental.next");
    await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
    await click("dental.next");

    const next = await button("dental.next");
    expect(next).toBeDisabled(); // nothing chosen yet: a blank is never "none"

    await userEvent.click(screen.getByLabelText(t("dental.allergies.some")));
    expect(next).toBeDisabled(); // "some" with no item ticked
    await userEvent.click(screen.getByLabelText(t("dental.allergy.OTHER")));
    expect(next).toBeDisabled(); // OTHER needs a description
    await userEvent.type(screen.getByLabelText(t("dental.allergy.otherText")), "Hạt điều");
    expect(next).toBeEnabled();

    await userEvent.click(screen.getByLabelText(t("dental.allergies.none")));
    expect(next).toBeEnabled();
    expect(screen.queryByLabelText(t("dental.allergy.PENICILLIN"))).not.toBeInTheDocument();
  });

  it("medicines_requires_none_or_text", async () => {
    await openSummary();
    await click("dental.start");
    await fillIdentity();
    await click("dental.next");
    await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.allergies.none")));
    await click("dental.next");

    const next = await button("dental.next");
    expect(next).toBeDisabled();
    await userEvent.click(screen.getByLabelText(t("dental.medicines.some")));
    expect(next).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t("dental.medicines.text")), "   ");
    expect(next).toBeDisabled(); // blank text is not an answer
    await userEvent.type(screen.getByLabelText(t("dental.medicines.text")), "Metformin");
    expect(next).toBeEnabled();
  });

  it("send_disabled_until_consent_and_sends_none_answers", async () => {
    serve({ get: () => ok(summary), form: () => created() });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await fillToConsent();

    const send = await button("action.send");
    expect(send).toBeDisabled();
    expect(t("dental.consent.text")).toMatch(/30/);
    expect(t("dental.consent.text")).toMatch(/sức khỏe/);
    await userEvent.click(screen.getByLabelText(t("dental.consent.label")));
    expect(send).toBeEnabled();
    await userEvent.click(send);

    await screen.findByText(t("dental.done.title"));
    const body = sentForm();
    expect(body).toEqual({
      identity: {
        idType: "CCCD",
        idNumber: "079090001234",
        fullName: "NGUYEN VAN A",
        dateOfBirth: "1990-05-17",
        nationality: "VN",
        address: "12 Le Loi, Q1",
      },
      phone: "0912345678",
      allergies: { none: true, items: [], otherText: null },
      medicines: { none: true, text: null },
      reason: { code: "CHECKUP", text: null },
      consent: true,
      idScanId: null,
    });
    expect(body).not.toHaveProperty("parentName");
    expect(body).not.toHaveProperty("parentPhone");
    const [, init] = calls("/form")[0];
    expect(init.headers["X-Visit-Token"]).toBe("tok-123");
    expect(init.credentials).toBe("omit");
  });

  it("free_text_newlines_are_sent_as_typed", async () => {
    serve({ get: () => ok(summary), form: () => created() });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    await fillIdentity();
    await click("dental.next");
    await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.allergies.some")));
    await userEvent.click(screen.getByLabelText(t("dental.allergy.LATEX")));
    await userEvent.click(screen.getByLabelText(t("dental.allergy.OTHER")));
    await userEvent.type(screen.getByLabelText(t("dental.allergy.otherText")), "Hạt điều{Enter}Tôm");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.medicines.some")));
    await userEvent.type(screen.getByLabelText(t("dental.medicines.text")), "Thuốc A{Enter}Thuốc B");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.reason.OTHER")));
    await userEvent.type(screen.getByLabelText(t("dental.reason.text")), "Sưng{Enter}nướu");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.consent.label")));
    await userEvent.click(await button("action.send"));
    await screen.findByText(t("dental.done.title"));

    const body = sentForm();
    expect(body.allergies).toEqual({ none: false, items: ["LATEX", "OTHER"], otherText: "Hạt điều\nTôm" });
    expect(body.medicines).toEqual({ none: false, text: "Thuốc A\nThuốc B" });
    expect(body.reason).toEqual({ code: "OTHER", text: "Sưng\nnướu" });
  });

  it("child_path_asks_parent", async () => {
    serve({ get: () => ok({ ...summary, child: true }), form: () => created() });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");

    // identity is optional, the parent's name and phone are required
    expect(screen.getByLabelText(t("dental.parentName"))).toBeInTheDocument();
    const next = await button("dental.next");
    expect(next).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t("dental.parentName")), "Tran Thi B");
    expect(next).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t("dental.parentPhone")), "0987654321");
    expect(next).toBeEnabled();
    await userEvent.click(next);

    await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.allergies.none")));
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.medicines.none")));
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.reason.CHECKUP")));
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.consent.label")));
    await userEvent.click(await button("action.send"));
    await screen.findByText(t("dental.done.title"));

    const body = sentForm();
    expect(body.identity).toBeNull();
    expect(body.parentName).toBe("Tran Thi B");
    expect(body.parentPhone).toBe("0987654321");
  });

  it("child_with_half_filled_identity_must_finish_it", async () => {
    serve({ get: () => ok({ ...summary, child: true }) });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    await userEvent.type(screen.getByLabelText(t("dental.parentName")), "Tran Thi B");
    await userEvent.type(screen.getByLabelText(t("dental.parentPhone")), "0987654321");
    await userEvent.type(screen.getByLabelText(t("field.fullName")), "NGUYEN BE");
    expect(await button("dental.next")).toBeDisabled();
  });

  it("adult_has_no_parent_fields", async () => {
    await openSummary();
    await click("dental.start");
    expect(screen.queryByLabelText(t("dental.parentName"))).not.toBeInTheDocument();
    expect(screen.queryByLabelText(t("dental.parentPhone"))).not.toBeInTheDocument();
    expect(await button("dental.next")).toBeDisabled(); // adult identity is required
  });

  it("returning_patient_dob_check_prefills", async () => {
    serve({
      get: () => ok({ ...summary, returning: "AVAILABLE" }),
      previous: () => ok(previous),
      form: () => created(),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");

    await userEvent.type(await screen.findByLabelText(t("dental.dob.label")), "1990-05-17");
    await click("dental.dob.check");
    await waitFor(() => expect(calls("/previous")).toHaveLength(1));
    expect(JSON.parse(calls("/previous")[0][1].body as string)).toEqual({ dateOfBirth: "1990-05-17" });
    expect(calls("/previous")[0][1].headers["X-Visit-Token"]).toBe("tok-123");

    // identity is never pre-filled
    expect(await screen.findByLabelText(t("field.idNumber"))).toHaveValue("");
    await fillIdentity();
    await click("dental.next");
    await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
    await click("dental.next");

    // allergies, medicines and reason come back filled
    expect(await screen.findByLabelText(t("dental.allergies.some"))).toBeChecked();
    expect(screen.getByLabelText(t("dental.allergy.PENICILLIN"))).toBeChecked();
    expect(screen.getByLabelText(t("dental.allergy.otherText"))).toHaveValue("Hạt điều");
    await click("dental.next");
    expect(await screen.findByLabelText(t("dental.medicines.text"))).toHaveValue("Metformin 500mg");
    await click("dental.next");
    expect(await screen.findByLabelText(t("dental.reason.PAIN"))).toBeChecked();
    expect(screen.getByLabelText(t("dental.reason.textOptional"))).toHaveValue("Đau răng hàm");
  });

  it("wrong_dob_shows_mismatch_with_attempts_left_and_never_raw_code", async () => {
    serve({
      get: () => ok({ ...summary, returning: "AVAILABLE" }),
      previous: () => problem(403, "DENTAL_CHECK_FAILED", { attemptsLeft: 3 }),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    await userEvent.type(await screen.findByLabelText(t("dental.dob.label")), "1990-05-17");
    await click("dental.dob.check");
    expect(await screen.findByRole("alert")).toHaveTextContent(t("dental.err.mismatchLeft", { count: 3 }));
    expect(screen.queryByText(/DENTAL_/)).not.toBeInTheDocument();
    // still on the check, can skip to a blank form
    await click("dental.dob.skip");
    expect(await screen.findByLabelText(t("field.idNumber"))).toHaveValue("");
  });

  it("wrong_dob_without_attempt_count_is_generic", async () => {
    serve({
      get: () => ok({ ...summary, returning: "AVAILABLE" }),
      previous: () => problem(403, "DENTAL_CHECK_FAILED"),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    await userEvent.type(await screen.findByLabelText(t("dental.dob.label")), "1990-05-17");
    await click("dental.dob.check");
    expect(await screen.findByRole("alert")).toHaveTextContent(t("dental.err.mismatch"));
  });

  it("locked_check_response_starts_fresh_with_no_more_attempts", async () => {
    serve({
      get: () => ok({ ...summary, returning: "AVAILABLE" }),
      previous: () => problem(423, "DENTAL_CHECK_LOCKED"),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    await userEvent.type(await screen.findByLabelText(t("dental.dob.label")), "1990-05-17");
    await click("dental.dob.check");
    // straight to an empty form; no more attempts offered
    expect(await screen.findByLabelText(t("field.idNumber"))).toHaveValue("");
    expect(screen.queryByLabelText(t("dental.dob.label"))).not.toBeInTheDocument();
    expect(screen.getByText(t("dental.dob.locked"))).toBeInTheDocument();
    expect(calls("/previous")).toHaveLength(1);
  });

  it("locked_check_starts_fresh", async () => {
    serve({ get: () => ok({ ...summary, returning: "LOCKED" }) });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    expect(await screen.findByLabelText(t("field.idNumber"))).toHaveValue("");
    expect(screen.queryByLabelText(t("dental.dob.label"))).not.toBeInTheDocument();
    expect(calls("/previous")).toHaveLength(0);
  });

  it("no_previous_answers_404_starts_empty_without_error", async () => {
    serve({
      get: () => ok({ ...summary, returning: "AVAILABLE" }),
      previous: () => problem(404, "DENTAL_NOT_FOUND"),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    await userEvent.type(await screen.findByLabelText(t("dental.dob.label")), "1990-05-17");
    await click("dental.dob.check");
    expect(await screen.findByLabelText(t("field.idNumber"))).toHaveValue("");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(t("dental.invalid"))).not.toBeInTheDocument();
  });

  it("closed_on_write_shows_closed", async () => {
    serve({ get: () => ok(summary), form: () => problem(409, "DENTAL_VISIT_CLOSED") });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await fillToConsent();
    await userEvent.click(await screen.findByLabelText(t("dental.consent.label")));
    await userEvent.click(await button("action.send"));
    expect(await screen.findByText(t("dental.closed"))).toBeInTheDocument();
  });

  it.each([
    [400, "DENTAL_INVALID_FORM", "dental.err.badForm"],
    [400, "BAD_REQUEST", "dental.err.badForm"],
    [500, "DENTAL_FIELD_UNREADABLE", "common.error"],
    [429, "TOO_MANY_REQUESTS", "common.busy"],
  ])("write_%s_%s_maps_to_%s_and_allows_retry", async (status, code, key) => {
    let n = 0;
    serve({
      get: () => ok(summary),
      form: () => (n++ === 0 ? problem(status, code) : created()),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await fillToConsent();
    await userEvent.click(await screen.findByLabelText(t("dental.consent.label")));
    await userEvent.click(await button("action.send"));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(t(key));
    expect(alert).not.toHaveTextContent(code);
    await userEvent.click(await button("action.send"));
    expect(await screen.findByText(t("dental.done.title"))).toBeInTheDocument();
  });

  it("double_tap_send_posts_once", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    serve({ get: () => ok(summary), form: async () => (await gate, created()) });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await fillToConsent();
    await userEvent.click(await screen.findByLabelText(t("dental.consent.label")));
    const send = await button("action.send");
    await userEvent.dblClick(send);
    release();
    await screen.findByText(t("dental.done.title"));
    expect(calls("/form")).toHaveLength(1);
  });

  it("cccd_photo_prefills_identity_and_sends_scan_id", async () => {
    mockToJpeg.mockResolvedValue(new Blob(["x"], { type: "image/jpeg" }));
    serve({
      get: () => ok(summary),
      scan: () =>
        ok(
          {
            scanId: "scan-1",
            fields: {
              idType: "CCCD",
              idNumber: "079090001234",
              fullName: "NGUYEN VAN A",
              dateOfBirth: "1990-05-17",
              nationality: "VN",
              address: "12 Le Loi, Q1",
            },
          },
          201,
        ),
      form: () => created(),
    });
    render(<App />);
    await screen.findByText(summary.maskedName);
    await click("dental.start");
    const file = new File(["x"], "id.jpg", { type: "image/jpeg" });
    await userEvent.upload(screen.getByLabelText(t("dental.takePhoto")), file);
    await waitFor(() => expect(screen.getByLabelText(t("field.idNumber"))).toHaveValue("079090001234"));
    expect(calls("/id-scans")[0][1].headers["X-Visit-Token"]).toBe("tok-123");
    await click("dental.next");
    await userEvent.type(await screen.findByLabelText(t("dental.phone")), "0912345678");
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.allergies.none")));
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.medicines.none")));
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.reason.CHECKUP")));
    await click("dental.next");
    await userEvent.click(await screen.findByLabelText(t("dental.consent.label")));
    await userEvent.click(await button("action.send"));
    await screen.findByText(t("dental.done.title"));
    expect(sentForm().idScanId).toBe("scan-1");
  });

  it("english_switch_translates_the_flow", async () => {
    await openSummary();
    await userEvent.click(screen.getByRole("button", { name: t("lang.switch") }));
    expect(await screen.findByRole("button", { name: i18n.getFixedT("en")("dental.start") })).toBeInTheDocument();
  });
});
