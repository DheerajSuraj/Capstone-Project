import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type PaperAccountDto } from '../api'

/**
 * The signed-in user's paper wallet, kept fresh.
 *
 * Polls faster while a limit order is waiting (the server may fill it at any
 * moment) and pauses while the tab is hidden. Every action returns the new
 * account, so the screen updates immediately after a click without waiting
 * for the next poll.
 */
export function usePaperAccount(enabled: boolean) {
  const [account, setAccount] = useState<PaperAccountDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(true)

  const refresh = useCallback(async () => {
    if (!enabled) return
    try {
      const a = await api.paperAccount()
      if (alive.current) {
        setAccount(a)
        setError(null)
      }
    } catch (e) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e))
    }
  }, [enabled])

  useEffect(() => {
    alive.current = true
    if (!enabled) {
      setAccount(null)
      return
    }
    refresh()
    return () => {
      alive.current = false
    }
  }, [enabled, refresh])

  const waiting = (account?.openOrders.length ?? 0) > 0
  useEffect(() => {
    if (!enabled) return
    const every = waiting ? 3000 : 10000
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') refresh()
    }, every)
    return () => clearInterval(id)
  }, [enabled, waiting, refresh])

  return { account, setAccount, error, refresh }
}
