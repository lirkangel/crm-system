import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import i18n from "@/i18n";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import App from "./App";
import { resetToken } from "@/api/checkin";

const mockToJpeg = vi.fn();
vi.mock("@/lib/photo", async (orig) => ({
  ...(await orig<typeof import("@/lib/photo")>()),
  toUploadJpeg: (...a: unknown[]) => mockToJpeg(...a),
}));

const summary = {
  hotelName: "Khách sạn Mặt Trời",
  roomTypeName: "Phòng đôi",
  checkIn: "2026-11-15",
  checkOut: "2026-11-17",
  adults: 2,
  maxGuests: 3,
  open: true,
  guests: [{ id: "g1", maskedName: "N***** V** A*", documentType: "CCCD" }],
};

const scanFields = {
  idType: "CCCD",
  idNumber: "079090001234",
  fullName: "NGUYEN VAN A",
  dateOfBirth: "1990-05-17",
  nationality: "VN",
  address: "12 Le Loi, Quan 1, TP HCM",
  sex: "Nam",
  issueDate: "2021-06-10",
};

function ok(data: unknown, status = 200) {
  return new Response(JSON.stringify({ success: true, code: "SUCCESS", message: "success", data }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
function problem(status: number, code: string) {
  return new Response(JSON.stringify({ status, code, title: code }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

let fetchMock: ReturnType<typeof vi.fn>;
function calls(method: string, suffix: string) {
  return fetchMock.mock.calls.filter(
    ([url, init]) => String(url).endsWith(suffix) && (init?.method ?? "GET") === method,
  );
}

let confirmMock: ReturnType<typeof vi.spyOn>;

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  confirmMock = vi.spyOn(window, "confirm").mockReturnValue(true);
  resetToken();
  window.history.replaceState(null, "", "/#tok-123");
  mockToJpeg.mockReset();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  vi.unstubAllGlobals();
  confirmMock.mockRestore();
});

function serve(get: () => Response) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (String(url).endsWith("/public/checkin") && !init?.method) return get();
    throw new Error("unexpected " + url);
  });
}

describe("App", () => {
  it("shows_stay_and_masked_names", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    serve(() => ok(summary));
    render(<App />);
    expect(await screen.findByText("N***** V** A*")).toBeInTheDocument();
    expect(screen.getByText(/Khách sạn Mặt Trời/)).toBeInTheDocument();
    expect(screen.getByText(/Phòng đôi/)).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/plugins/hotel/public/checkin");
    expect(init.headers["X-Checkin-Token"]).toBe("tok-123");
    expect(window.location.hash).toBe("");
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  it("expired_or_replaced_link_shows_ask_hotel", async () => {
    serve(() => problem(404, "HOTEL_NOT_FOUND"));
    render(<App />);
    expect(await screen.findByText(i18n.t("invalid"))).toBeInTheDocument();
  });

  it("closed_link_shows_closed", async () => {
    serve(() => ok({ ...summary, open: false }));
    render(<App />);
    expect(await screen.findByText(i18n.t("closed"))).toBeInTheDocument();
  });

  async function openPassport() {
    serve(() => ok(summary));
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: i18n.t("stay.add") }));
    await userEvent.click(screen.getByRole("button", { name: i18n.t("adult.noCccd") }));
  }

  async function fillPassport() {
    await userEvent.type(screen.getByLabelText(i18n.t("field.idNumber")), "B1234567");
    await userEvent.type(screen.getByLabelText(i18n.t("field.fullName")), "JOHN SMITH");
    await userEvent.type(screen.getByLabelText(i18n.t("field.dateOfBirth")), "1985-03-02");
    await userEvent.selectOptions(screen.getByLabelText(i18n.t("field.nationality")), "US");
    await userEvent.type(screen.getByLabelText(i18n.t("field.visaNumber")), "V99");
    await userEvent.type(screen.getByLabelText(i18n.t("field.entryDate")), "2026-11-01");
    await userEvent.click(screen.getByLabelText(i18n.t("consent.label")));
  }

  it("send_disabled_until_consent", async () => {
    await openPassport();
    await userEvent.type(screen.getByLabelText(i18n.t("field.idNumber")), "B1234567");
    await userEvent.type(screen.getByLabelText(i18n.t("field.fullName")), "JOHN SMITH");
    await userEvent.type(screen.getByLabelText(i18n.t("field.dateOfBirth")), "1985-03-02");
    await userEvent.selectOptions(screen.getByLabelText(i18n.t("field.nationality")), "US");
    await userEvent.type(screen.getByLabelText(i18n.t("field.visaNumber")), "V99");
    await userEvent.type(screen.getByLabelText(i18n.t("field.entryDate")), "2026-11-01");

    const send = screen.getByRole("button", { name: i18n.t("action.send") });
    expect(send).toBeDisabled();
    await userEvent.click(screen.getByLabelText(i18n.t("consent.label")));
    expect(send).toBeEnabled();
    expect(i18n.t("consent.text")).toMatch(/30/);
  });

  it("passport_submit_posts_identity_and_consent", async () => {
    await openPassport();
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith("/guests") && init?.method === "POST")
        return ok({ id: "g2", maskedName: "J**** S****", documentType: "PASSPORT" }, 201);
      return ok({ ...summary, guests: [...summary.guests, { id: "g2", maskedName: "J**** S****", documentType: "PASSPORT" }] });
    });
    await userEvent.type(screen.getByLabelText(i18n.t("field.idNumber")), "B1234567");
    await userEvent.type(screen.getByLabelText(i18n.t("field.fullName")), "JOHN SMITH");
    await userEvent.type(screen.getByLabelText(i18n.t("field.dateOfBirth")), "1985-03-02");
    await userEvent.selectOptions(screen.getByLabelText(i18n.t("field.nationality")), "US");
    await userEvent.type(screen.getByLabelText(i18n.t("field.visaNumber")), "V99");
    await userEvent.type(screen.getByLabelText(i18n.t("field.entryDate")), "2026-11-01");
    await userEvent.click(screen.getByLabelText(i18n.t("consent.label")));
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));

    expect(await screen.findByText("J**** S****")).toBeInTheDocument();
    const [, init] = calls("POST", "/guests")[0];
    expect(JSON.parse(init.body)).toEqual({
      identity: {
        idType: "PASSPORT",
        idNumber: "B1234567",
        fullName: "JOHN SMITH",
        dateOfBirth: "1985-03-02",
        nationality: "US",
        address: null,
        visaNumber: "V99",
        entryDate: "2026-11-01",
        idScanId: null,
      },
      consent: true,
    });
  });

  it("undecodable_photo_asks_for_camera", async () => {
    const { UndecodablePhotoError } = await import("@/lib/photo");
    mockToJpeg.mockRejectedValue(new UndecodablePhotoError());
    serve(() => ok(summary));
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: i18n.t("stay.add") }));
    const input = screen.getByLabelText(i18n.t("adult.takePhoto")) as HTMLInputElement;
    expect(input).toHaveAttribute("capture", "environment");
    await userEvent.upload(input, new File(["x"], "p.heic", { type: "image/heic" }));
    expect(await screen.findByText(i18n.t("scan.unreadablePhoto"))).toBeInTheDocument();
    expect(calls("POST", "/id-scans")).toHaveLength(0);
  });

  it("scan_prefills_review_fields", async () => {
    mockToJpeg.mockResolvedValue(new Blob(["jpg"], { type: "image/jpeg" }));
    serve(() => ok(summary));
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: i18n.t("stay.add") }));
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith("/id-scans") && init?.method === "POST")
        return ok({ scanId: "scan-1", fields: scanFields }, 201);
      if (String(url).endsWith("/guests") && init?.method === "POST")
        return ok({ id: "g2", maskedName: "N***** V** A*", documentType: "CCCD" }, 201);
      return ok(summary);
    });
    await userEvent.upload(
      screen.getByLabelText(i18n.t("adult.takePhoto")),
      new File(["x"], "p.jpg", { type: "image/jpeg" }),
    );
    expect(await screen.findByDisplayValue("NGUYEN VAN A")).toBeInTheDocument();
    expect(screen.getByLabelText(i18n.t("field.idNumber"))).toHaveValue("079090001234");
    expect(screen.getByLabelText(i18n.t("field.dateOfBirth"))).toHaveValue("1990-05-17");
    expect(screen.getByLabelText(i18n.t("field.nationality"))).toHaveValue("VN");
    expect(screen.getByLabelText(i18n.t("field.address"))).toHaveValue("12 Le Loi, Quan 1, TP HCM");

    await userEvent.click(screen.getByLabelText(i18n.t("consent.label")));
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));
    await waitFor(() => expect(calls("POST", "/guests")).toHaveLength(1));
    const body = JSON.parse(calls("POST", "/guests")[0][1].body);
    expect(body.identity.idScanId).toBe("scan-1");
    expect(body.identity.idType).toBe("CCCD");
    expect(body.consent).toBe(true);
  });

  it("vietnamese_by_default_english_toggle", async () => {
    serve(() => ok(summary));
    render(<App />);
    expect(await screen.findByRole("button", { name: i18n.t("stay.add") })).toHaveTextContent(
      "Thêm khách",
    );
    expect(document.documentElement.lang).toBe("vi");
    await userEvent.click(screen.getByRole("button", { name: "English" }));
    expect(await screen.findByRole("button", { name: "Add an adult" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
  });

  it("add_button_relabels_after_expected_adults", async () => {
    const g = (id: string) => ({ id, maskedName: id, documentType: "CCCD" });
    serve(() => ok({ ...summary, adults: 1, maxGuests: 2, guests: [g("a")] }));
    const { unmount } = render(<App />);
    expect(await screen.findByRole("button", { name: i18n.t("stay.addAnother") })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: i18n.t("stay.add") })).toBeNull();
    unmount();
    serve(() => ok({ ...summary, adults: 1, maxGuests: 2, guests: [g("a"), g("b")] }));
    render(<App />);
    await screen.findByText("b");
    expect(screen.queryByRole("button", { name: /^Thêm khách/ })).toBeNull();
  });

  it("date_limits_use_local_dates", async () => {
    const env = (globalThis as unknown as { process: { env: Record<string, string | undefined> } }).process.env;
    const tz = env.TZ;
    env.TZ = "Asia/Ho_Chi_Minh"; // 23:30+07:00 is still 2026-11-14 locally (it is 16:30 UTC)
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-14T23:30:00+07:00"));
    try {
      await openPassport();
      expect(screen.getByLabelText(i18n.t("field.entryDate"))).toHaveAttribute("max", "2026-11-14");
      expect(screen.getByLabelText(i18n.t("field.dateOfBirth"))).toHaveAttribute("max", "2026-11-13");
    } finally {
      vi.useRealTimers();
      if (tz === undefined) delete env.TZ;
      else env.TZ = tz;
    }
  });

  it("submit_body_and_token_header", async () => {
    mockToJpeg.mockResolvedValue(new Blob(["jpg"], { type: "image/jpeg" }));
    serve(() => ok(summary));
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: i18n.t("stay.add") }));
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith("/id-scans")) return ok({ scanId: "s9", fields: scanFields }, 201);
      if (String(url).endsWith("/guests") && init?.method === "POST") return ok({ id: "g3", maskedName: "x", documentType: "CCCD" }, 201);
      return ok(summary);
    });
    await userEvent.upload(screen.getByLabelText(i18n.t("adult.takePhoto")), new File(["x"], "p.jpg", { type: "image/jpeg" }));
    await userEvent.click(await screen.findByLabelText(i18n.t("consent.label")));
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));
    await waitFor(() => expect(calls("POST", "/guests")).toHaveLength(1));
    const body = JSON.parse(calls("POST", "/guests")[0][1].body);
    expect(body.consent).toBe(true);
    expect(body.identity.idScanId).toBe("s9");
    // DELETE
    await userEvent.click(await screen.findByRole("button", { name: /Xóa/ }));
    await waitFor(() => expect(calls("DELETE", "/guests/g1")).toHaveLength(1));
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.headers["X-Checkin-Token"]).toBe("tok-123");
    }
    expect(calls("GET", "/public/checkin").length).toBeGreaterThan(0);
    expect(calls("POST", "/id-scans")).toHaveLength(1);
  });

  it("network_failure_on_load_and_submit_does_not_crash", async () => {
    fetchMock.mockRejectedValue(new TypeError("offline"));
    const { unmount } = render(<App />);
    expect(await screen.findByText(i18n.t("common.error"))).toBeInTheDocument();
    expect(screen.getByRole("button", { name: i18n.t("common.retry") })).toBeInTheDocument();
    unmount();

    await openPassport();
    fetchMock.mockRejectedValue(new TypeError("offline"));
    await fillPassport();
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));
    expect(await screen.findByRole("alert")).toHaveTextContent(i18n.t("common.error"));
    expect(screen.getByRole("button", { name: i18n.t("action.send") })).toBeEnabled();
  });

  it("unreadable_then_successful_upload", async () => {
    mockToJpeg.mockResolvedValue(new Blob(["jpg"], { type: "image/jpeg" }));
    serve(() => ok(summary));
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: i18n.t("stay.add") }));
    let n = 0;
    fetchMock.mockImplementation(async () =>
      n++ === 0 ? problem(400, "HOTEL_SCAN_UNREADABLE") : ok({ scanId: "s2", fields: scanFields }, 201),
    );
    const input = screen.getByLabelText(i18n.t("adult.takePhoto"));
    const file = new File(["x"], "p.jpg", { type: "image/jpeg" });
    await userEvent.upload(input, file);
    expect(await screen.findByText(i18n.t("scan.unreadable"))).toBeInTheDocument();
    await userEvent.upload(screen.getByLabelText(i18n.t("adult.takePhoto")), file);
    expect(await screen.findByDisplayValue("NGUYEN VAN A")).toBeInTheDocument();
  });

  it.each([
    [404, "HOTEL_NOT_FOUND", "invalid"],
    [409, "HOTEL_CHECKIN_CLOSED", "closed"],
  ])("submit_%i_shows_screen", async (status, code, key) => {
    await openPassport();
    fetchMock.mockImplementation(async () => problem(status, code));
    await fillPassport();
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));
    expect(await screen.findByText(i18n.t(key))).toBeInTheDocument();
  });

  it("double_tap_sends_once", async () => {
    await openPassport();
    let release!: () => void;
    fetchMock.mockImplementation(
      (url: string, init?: RequestInit) =>
        new Promise((res) => {
          release = () =>
            res(String(url).endsWith("/guests") && init?.method === "POST" ? ok({ id: "z", maskedName: "z", documentType: "PASSPORT" }, 201) : ok(summary));
        }),
    );
    await fillPassport();
    const send = screen.getByRole("button", { name: i18n.t("action.send") });
    await userEvent.dblClick(send);
    expect(calls("POST", "/guests")).toHaveLength(1);
    release();
  });

  it("visa_exempt_foreigner_submits_without_visa_or_entry_date", async () => {
    await openPassport();
    expect(screen.getByText(i18n.t("passport.visaLater"))).toBeInTheDocument();
    expect(screen.getByLabelText(i18n.t("field.visaNumber"))).not.toBeRequired();
    expect(screen.getByLabelText(i18n.t("field.entryDate"))).not.toBeRequired();
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith("/guests") && init?.method === "POST")
        return ok({ id: "g2", maskedName: "J**** S****", documentType: "PASSPORT" }, 201);
      return ok(summary);
    });
    await userEvent.type(screen.getByLabelText(i18n.t("field.idNumber")), "B1234567");
    await userEvent.type(screen.getByLabelText(i18n.t("field.fullName")), "JOHN SMITH");
    await userEvent.type(screen.getByLabelText(i18n.t("field.dateOfBirth")), "1985-03-02");
    await userEvent.selectOptions(screen.getByLabelText(i18n.t("field.nationality")), "US");
    await userEvent.click(screen.getByLabelText(i18n.t("consent.label")));

    const send = screen.getByRole("button", { name: i18n.t("action.send") });
    expect(send).toBeEnabled();
    await userEvent.click(send);

    await waitFor(() => expect(calls("POST", "/guests")).toHaveLength(1));
    const body = JSON.parse(calls("POST", "/guests")[0][1].body);
    expect(body.identity.visaNumber).toBeNull();
    expect(body.identity.entryDate).toBeNull();
    expect(body.identity.nationality).toBe("US");
  });

  it("vietnamese_citizen_still_needs_an_address", async () => {
    await openPassport();
    await userEvent.type(screen.getByLabelText(i18n.t("field.idNumber")), "B1234567");
    await userEvent.type(screen.getByLabelText(i18n.t("field.fullName")), "NGUYEN VAN B");
    await userEvent.type(screen.getByLabelText(i18n.t("field.dateOfBirth")), "1985-03-02");
    await userEvent.selectOptions(screen.getByLabelText(i18n.t("field.nationality")), "VN");
    await userEvent.click(screen.getByLabelText(i18n.t("consent.label")));
    expect(screen.getByRole("button", { name: i18n.t("action.send") })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(i18n.t("field.address")), "Hue");
    expect(screen.getByRole("button", { name: i18n.t("action.send") })).toBeEnabled();
  });

  function serveWithDelete(del: () => Response | Promise<Response>, afterDelete = summary) {
    let deleted = false;
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith("/guests/g1") && init?.method === "DELETE") {
        deleted = true;
        return del();
      }
      if (String(url).endsWith("/public/checkin") && !init?.method) return ok(deleted ? afterDelete : summary);
      throw new Error("unexpected " + url);
    });
  }

  it("delete_404_reloads_summary_not_invalid", async () => {
    // someone removed it already (another tap or device): the list reloads, the page stays usable
    serveWithDelete(() => problem(404, "HOTEL_NOT_FOUND"), { ...summary, guests: [] });
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: /Xóa/ }));

    expect(await screen.findByText(i18n.t("stay.none"))).toBeInTheDocument();
    expect(screen.queryByText(i18n.t("invalid"))).toBeNull();
    expect(calls("DELETE", "/guests/g1")).toHaveLength(1);
    expect(calls("GET", "/public/checkin")).toHaveLength(2);
  });

  it("delete_404_then_summary_404_means_the_link_is_dead", async () => {
    let dead = false;
    fetchMock.mockImplementation(async (_url: string, init?: RequestInit) => {
      if (init?.method === "DELETE") {
        dead = true;
        return problem(404, "HOTEL_NOT_FOUND");
      }
      return dead ? problem(404, "HOTEL_NOT_FOUND") : ok(summary);
    });
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: /Xóa/ }));

    expect(await screen.findByText(i18n.t("invalid"))).toBeInTheDocument();
  });

  it("remove_asks_confirmation_and_guards_double_tap", async () => {
    let release!: () => void;
    serveWithDelete(
      () =>
        new Promise<Response>((res) => {
          release = () => res(new Response(null, { status: 204 }));
        }),
      { ...summary, guests: [] },
    );
    render(<App />);
    const remove = await screen.findByRole("button", { name: /Xóa/ });

    // declined: nothing is sent
    confirmMock.mockReturnValueOnce(false);
    await userEvent.click(remove);
    expect(confirmMock).toHaveBeenCalledWith(i18n.t("stay.removeConfirm"));
    expect(calls("DELETE", "/guests/g1")).toHaveLength(0);

    // accepted, then tapped twice while the request is in flight
    await userEvent.dblClick(remove);
    expect(calls("DELETE", "/guests/g1")).toHaveLength(1);
    expect(confirmMock).toHaveBeenCalledTimes(2);
    expect(remove).toBeDisabled();
    release();
    expect(await screen.findByText(i18n.t("stay.none"))).toBeInTheDocument();
  });

  it("same_document_again_says_already_sent", async () => {
    await openPassport();
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith("/guests") && init?.method === "POST")
        return ok({ id: "g1", maskedName: "N***** V** A*", documentType: "CCCD" }, 200);
      return ok(summary);
    });
    await fillPassport();
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));

    expect(await screen.findByText(i18n.t("guests.alreadySent"))).toBeInTheDocument();
    expect(i18n.t("guests.alreadySent")).toMatch(/remove|xóa/i);
    // a fresh submission shows no such notice
    await userEvent.click(await screen.findByRole("button", { name: i18n.t("stay.add") }));
    expect(screen.queryByText(i18n.t("guests.alreadySent"))).toBeNull();
  });

  it.each([
    ["HOTEL_INVALID_ID_DOC", 400, "identity.invalidDoc"],
    ["VALIDATION_ERROR", 400, "identity.invalid"],
    ["HOTEL_SCAN_LIMIT", 409, "scan.limit"],
  ])("submit_%s_shows_its_message", async (code, status, key) => {
    await openPassport();
    fetchMock.mockImplementation(async () => problem(status, code));
    await fillPassport();
    await userEvent.click(screen.getByRole("button", { name: i18n.t("action.send") }));
    expect(await screen.findByRole("alert")).toHaveTextContent(i18n.t(key));
  });

  it("invalid_text_says_reopen_the_link", () => {
    expect(i18n.getFixedT("vi")("invalid")).toBe(
      "Không mở được liên kết. Hãy mở lại liên kết từ tin nhắn của khách sạn; nếu vẫn lỗi, vui lòng liên hệ khách sạn.",
    );
    expect(i18n.getFixedT("en")("invalid")).toBe(
      "This link couldn't be opened. Open it again from the hotel's message; if it still fails, contact the hotel.",
    );
  });

  it("dates_and_document_type_are_displayed_for_people", async () => {
    serve(() =>
      ok({
        ...summary,
        guests: [
          { id: "g1", maskedName: "N***** V** A*", documentType: "CCCD" },
          { id: "g2", maskedName: "J**** S****", documentType: "PASSPORT" },
          { id: "g3", maskedName: "O***", documentType: "OTHER" },
        ],
      }),
    );
    render(<App />);
    expect(await screen.findByText(/15\/11\/2026 đến 17\/11\/2026/)).toBeInTheDocument();
    expect(screen.getByText("(CCCD)")).toBeInTheDocument();
    expect(screen.getByText("(Hộ chiếu)")).toBeInTheDocument();
    expect(screen.getByText("(Khác)")).toBeInTheDocument();
    expect(screen.queryByText(/PASSPORT/)).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "English" }));
    const local = (d: number) => new Date(2026, 10, d).toLocaleDateString();
    expect(await screen.findByText(`${local(15)} to ${local(17)}`)).toBeInTheDocument();
    expect(screen.getByText("(Passport)")).toBeInTheDocument();
  });

  it("document_title_is_vietnamese_and_follows_the_language", async () => {
    serve(() => ok(summary));
    render(<App />);
    await screen.findByText("N***** V** A*");
    expect(document.title).toBe("Khai báo lưu trú trước");
    await userEvent.click(screen.getByRole("button", { name: "English" }));
    await waitFor(() => expect(document.title).toBe("Pre-arrival registration"));
  });

  it("error_boundary_renders_failed_view", () => {
    const Boom = () => {
      throw new Error("boom");
    };
    vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(i18n.t("common.error"));
    expect(screen.getByRole("button", { name: i18n.t("common.retry") })).toBeInTheDocument();
  });
});
