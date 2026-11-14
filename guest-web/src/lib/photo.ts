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
  try {
    const scale = Math.min(1, maxLongEdge / Math.max(bitmap.width, bitmap.height));
    const width = Math.round(bitmap.width * scale);
    const height = Math.round(bitmap.height * scale);
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new UndecodablePhotoError();
    ctx.drawImage(bitmap, 0, 0, width, height);
    return await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (b) => (b ? resolve(b) : reject(new UndecodablePhotoError())),
        "image/jpeg",
        0.85,
      );
    });
  } finally {
    bitmap.close?.();
  }
}
