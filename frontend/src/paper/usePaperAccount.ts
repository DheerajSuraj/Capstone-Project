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
  const [account, setAccountState] = useState<PaperAccountDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(true)
  // Every new account (a poll, or what a click returned) gets a number. A
  // poll that set off before a click but answered after it carries an older
  // number and is dropped — before, it put the old balance back on screen.
  const version = useRef(0)

  const setAccount = useCallback((a: PaperAccountDto) => {
    version.current++
    setAccountState(a)
  }, [])

  const refresh = useCallback(async () => {
    if (!enabled) return
    const mine = ++version.current
    try {
      const a = await api.paperAccount()
      if (alive.current && mine === version.current) {
        setAccountState(a)
        setError(null)
      }
    } catch (e) {
      if (alive.current && mine === version.current) {
        setError(e instanceof Error ? e.message : String(e))
      }
    }
  }, [enabled])

  useEffect(() => {
    alive.current = true
    if (!enabled) {
      version.current++
      setAccountState(null)
      setError(null)
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
