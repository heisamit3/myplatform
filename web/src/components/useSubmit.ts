import { useState, type FormEvent } from 'react'

/** Form submit state: runs the action, tracks "busy", and keeps the error message to show. */
export function useSubmit(action: () => Promise<void>) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, onSubmit }
}
