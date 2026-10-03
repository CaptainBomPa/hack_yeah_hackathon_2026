/**
 * crypto.randomUUID działa tylko w secure context (HTTPS albo localhost). Dashboard serwowany z Pi
 * po http://<ip-w-sieci> nim nie jest, więc mamy fallback na getRandomValues.
 */
export function newId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}
