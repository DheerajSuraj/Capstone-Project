import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import * as api from './api';
import type { AuthResult, NeedsUsername, User } from './api';
import { forgetGoogleAccount } from './GoogleButton';

/**
 * Holds who is signed in, and owns the one piece of state that spans two
 * screens: a Google identity that has been verified but has no username yet.
 */

type Status = 'booting' | 'anonymous' | 'needs_username' | 'authenticated';

type AuthContextValue = {
  status: Status;
  user: User | null;
  /** Set only while status === 'needs_username'. */
  pending: NeedsUsername | null;

  signUp: (input: {
    username: string;
    email: string;
    password: string;
  }) => Promise<void>;
  signIn: (input: { identifier: string; password: string }) => Promise<void>;
  signInWithGoogle: (credential: string) => Promise<void>;
  claimUsername: (username: string) => Promise<void>;
  cancelPending: () => void;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('booting');
  const [user, setUser] = useState<User | null>(null);
  const [pending, setPending] = useState<NeedsUsername | null>(null);

  // React 18 StrictMode mounts effects twice in development. Without this
  // guard the boot refresh fires twice, and because refresh tokens rotate
  // server-side the second call would be replaying a token the first call
  // already spent — which looks exactly like token theft to the backend,
  // and correctly revokes the whole session.
  const booted = useRef(false);

  useEffect(() => {
    if (booted.current) return;
    booted.current = true;

    // Deliberately no cancellation flag and no cleanup function. The ref
    // above already guarantees this runs exactly once across StrictMode's
    // mount/unmount/remount — so a cleanup that cancelled the request would
    // be cancelling the only one that exists. Its result would be discarded,
    // setStatus would never fire, and status would sit on 'booting' forever,
    // which renders as an app where nothing responds to clicks.
    api
      .refresh()
      .then((r) => {
        setUser(r.user);
        setStatus('authenticated');
      })
      .catch(() => {
        // No valid refresh cookie. That is the normal first visit, not an
        // error worth showing anyone. The browser still logs the 401 to the
        // console; that is Chrome reporting the request, not a bug.
        setStatus('anonymous');
      });
  }, []);

  const adopt = useCallback((result: AuthResult) => {
    if (result.status === 'authenticated') {
      setUser(result.user);
      setPending(null);
      setStatus('authenticated');
    } else {
      setPending(result);
      setStatus('needs_username');
    }
  }, []);

  const signUp = useCallback(
    async (input: { username: string; email: string; password: string }) => {
      adopt(await api.signUp(input));
    },
    [adopt],
  );

  const signIn = useCallback(
    async (input: { identifier: string; password: string }) => {
      adopt(await api.signIn(input));
    },
    [adopt],
  );

  const signInWithGoogle = useCallback(
    async (credential: string) => {
      adopt(await api.signInWithGoogle(credential));
    },
    [adopt],
  );

  const claimUsername = useCallback(
    async (username: string) => {
      if (!pending) {
        throw new api.ApiError(
          'PENDING_EXPIRED',
          'That sign-in attempt has expired. Please try again.',
        );
      }
      adopt(
        await api.claimUsername({
          pendingToken: pending.pendingToken,
          username,
        }),
      );
    },
    [adopt, pending],
  );

  const cancelPending = useCallback(() => {
    setPending(null);
    setStatus('anonymous');
  }, []);

  const signOut = useCallback(async () => {
    try {
      await api.signOut();
    } finally {
      // Clear locally even if the server call failed. The refresh cookie is
      // httpOnly and expires on its own, and the user asked to be signed
      // out — a network blip should not leave them looking signed in.
      forgetGoogleAccount();
      setUser(null);
      setPending(null);
      setStatus('anonymous');
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      pending,
      signUp,
      signIn,
      signInWithGoogle,
      claimUsername,
      cancelPending,
      signOut,
    }),
    [
      status,
      user,
      pending,
      signUp,
      signIn,
      signInWithGoogle,
      claimUsername,
      cancelPending,
      signOut,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}