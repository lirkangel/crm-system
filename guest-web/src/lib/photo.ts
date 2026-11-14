export class UndecodablePhotoError extends Error {
  constructor() {
    super("The photo could not be decoded");
    this.name = "UndecodablePhotoError";
  }
}

/**
 * Decodes the photo (EXIF orientation applied), downscales it to at most {@code maxLongEdge} px on the long edge and
 * exports JPEG at quality 0.85. Rejects with {@link UndecodablePhotoError} when the browser cannot decode it
 * (e.g. HEIC outside Safari), so the caller can ask for a camera photo without sending anything.
 */
export async function toUploadJpeg(file: File, maxLongEdge = 2400): Promise<Blob> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  } catch {
    throw new UndecodablePhotoError();
  }
  let canvas: HTMLCanvasElement | undefined;
  try {
    const scale = Math.min(1, maxLongEdge / Math.max(bitmap.width, bitmap.height));
    const width = Math.round(bitmap.width * scale);
    const height = Math.round(bitmap.height * scale);
    const c = (canvas = document.createElement("canvas"));
    c.width = width;
    c.height = height;
    const ctx = c.getContext("2d");
    if (!ctx) throw new UndecodablePhotoError();
    ctx.drawImage(bitmap, 0, 0, width, height);
    return await new Promise<Blob>((resolve, reject) => {
      c.toBlob(
        (b) => (b ? resolve(b) : reject(new UndecodablePhotoError())),
        "image/jpeg",
        0.85,
      );
    });
  } finally {
    if (canvas) canvas.width = canvas.height = 0; // release the pixel buffer
    bitmap.close?.();
  }
}
