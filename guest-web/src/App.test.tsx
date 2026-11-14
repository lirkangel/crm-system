import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import i18n from "@/i18n";
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

beforeEach(() => {
  resetToken();
  window.history.replaceState(null, "", "/#tok-123");
  mockToJpeg.mockReset();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

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
});
