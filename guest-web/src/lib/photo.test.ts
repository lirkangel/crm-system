import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UndecodablePhotoError, toUploadJpeg } from "./photo";

// jsdom has neither createImageBitmap nor a canvas: both are mocked.
describe("toUploadJpeg", () => {
  const drawImage = vi.fn();
  const close = vi.fn();
  let getContext: ReturnType<typeof vi.spyOn>;
  const canvas = () => getContext.mock.contexts[0] as HTMLCanvasElement;

  beforeEach(() => {
    drawImage.mockReset();
    close.mockReset();
    getContext = vi
      .spyOn(HTMLCanvasElement.prototype, "getContext")
      .mockImplementation((() => ({ drawImage })) as never);
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(((
      cb: BlobCallback,
      type?: string,
      quality?: number,
    ) => {
      cb(new Blob([`${type}@${quality}`], { type }));
    }) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("downscales 4000x3000 to 2400x1800 and exports JPEG 0.85", async () => {
    const cib = vi.fn().mockResolvedValue({ width: 4000, height: 3000, close });
    vi.stubGlobal("createImageBitmap", cib);
    const file = new File(["x"], "p.jpg", { type: "image/jpeg" });

    const blob = await toUploadJpeg(file);

    expect(cib).toHaveBeenCalledWith(file, { imageOrientation: "from-image" });
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 2400, 1800);
    expect(blob.type).toBe("image/jpeg");
    expect(await blob.text()).toBe("image/jpeg@0.85");
    expect(close).toHaveBeenCalled();
    expect(canvas().width).toBe(0);
    expect(canvas().height).toBe(0);
  });

  it("downscales a portrait 1200x4000 to 720x2400", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue({ width: 1200, height: 4000, close }));
    await toUploadJpeg(new File(["x"], "p.jpg"));
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 720, 2400);
  });

  it("rejects when there is no 2d context", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue({ width: 10, height: 10, close }));
    getContext.mockImplementation((() => null) as never);
    await expect(toUploadJpeg(new File(["x"], "p.jpg"))).rejects.toBeInstanceOf(UndecodablePhotoError);
    expect(close).toHaveBeenCalled();
  });

  it("does not upscale a small photo", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue({ width: 800, height: 1200, close }));
    await toUploadJpeg(new File(["x"], "p.jpg"));
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 800, 1200);
  });

  it("rejects an undecodable photo", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockRejectedValue(new Error("nope")));
    await expect(toUploadJpeg(new File(["x"], "p.heic"))).rejects.toBeInstanceOf(
      UndecodablePhotoError,
    );
  });

  it("rejects when createImageBitmap is missing", async () => {
    vi.stubGlobal("createImageBitmap", undefined);
    await expect(toUploadJpeg(new File(["x"], "p.heic"))).rejects.toBeInstanceOf(
      UndecodablePhotoError,
    );
  });
});
