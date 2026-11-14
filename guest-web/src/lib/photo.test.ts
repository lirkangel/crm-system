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
    expect(canvas().width).toBe(2400);
    expect(canvas().height).toBe(1800);
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 2400, 1800);
    expect(blob.type).toBe("image/jpeg");
    expect(await blob.text()).toBe("image/jpeg@0.85");
    expect(close).toHaveBeenCalled();
  });

  it("does not upscale a small photo", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue({ width: 800, height: 1200, close }));
    await toUploadJpeg(new File(["x"], "p.jpg"));
    expect(canvas().width).toBe(800);
    expect(canvas().height).toBe(1200);
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
