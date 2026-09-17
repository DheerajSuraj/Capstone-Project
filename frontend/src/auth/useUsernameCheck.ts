import { useEffect, useRef, useState } from 'react';
import { checkUsername, validateUsername } from './api';

export type UsernameState =
  | { kind: 'empty' }
  | { kind: 'invalid'; reason: string }
  | { kind: 'checking' }
  | { kind: 'available' }
  | { kind: 'taken'; suggestions: string[] }
  | { kind: 'unknown' };

/**
 * Tells the user whether a username is free, 400 ms after they stop typing.
 *
 * Two things matter here and are easy to get wrong:
 *  - every in-flight request is aborted when the value changes, so a slow
 *    early response cannot land after a fast later one and show the wrong
 *    answer for the wrong name;
 *  - a failed check reports 'unknown', never 'available'. The server checks
 *    again on submit; this is a courtesy, not a gate.
 */
export function useUsernameCheck(value: string, delay = 400): UsernameState {
  const [state, setState] = useState<UsernameState>({ kind: 'empty' });
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    controller.current?.abort();

    const trimmed = value.trim();
    if (trimmed.length === 0) {
      setState({ kind: 'empty' });
      return;
    }

    const problem = validateUsername(trimmed);
    if (problem) {
      setState({ kind: 'invalid', reason: problem });
      return;
    }

    setState({ kind: 'checking' });
    const ac = new AbortController();
    controller.current = ac;

    const timer = window.setTimeout(() => {
      checkUsername(trimmed, ac.signal)
        .then((r) => {
          if (ac.signal.aborted) return;
          setState(
            r.available
              ? { kind: 'available' }
              : { kind: 'taken', suggestions: r.suggestions ?? [] },
          );
        })
        .catch((e: unknown) => {
          if (ac.signal.aborted || (e as Error)?.name === 'AbortError') return;
          setState({ kind: 'unknown' });
        });
    }, delay);

    return () => {
      window.clearTimeout(timer);
      ac.abort();
    };
  }, [value, delay]);

  return state;
}
