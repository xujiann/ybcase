/** Content-Disposition distinguishes a real JSON attachment from an R.fail envelope. */
export async function downloadError(blob: Blob, contentType: string, disposition?: string): Promise<{code: number; message: string} | null> {
  if (/\battachment\b/i.test(disposition || '') || !contentType.includes('application/json')) return null
  try {
    const value = JSON.parse(await blob.text())
    return value && Number.isInteger(value.code) && value.code !== 0
      && typeof value.message === 'string' && Object.prototype.hasOwnProperty.call(value, 'data')
      ? { code: value.code, message: value.message } : null
  } catch { return null }
}
