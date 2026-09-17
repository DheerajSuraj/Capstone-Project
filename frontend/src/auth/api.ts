/**
 * Auth API client.
 *
 * Token strategy:
 *   - the ACCESS token lives in this module's memory only (never localStorage,
 *     never a readable cookie), so a cross-site script cannot read it;
 *   - the REFRESH token lives in an httpOnly, SameSite=Lax cookie that the
 *     backend sets and the browser returns automatically. JavaScript cannot
 *     touch it at all.
 *
 * The cost of that choice is one extra round trip on page load (`refresh()`),
 * because a reload wipes the in-memory access token. That is the trade: a
 * reload costs ~40 ms, and an XSS bug does not cost the account.
 *
 * Every call sends `credentials: 'include'` so the refresh cookie travels.
 */

export type User = {
  id: string;
  username: string;
  email: string;
  displayName: string | null;
  avatarUrl: string | null;
  createdAt: string;
};

export type AuthSuccess = {
  status: 'authenticated';
  accessToken: string;
  user: User;
};

/**
 * Google gave us a verified email but this person has never been here, so
 * there is no username to put on a leaderboard yet. The backend holds the
 * verified identity behind a short-lived `pendingToken` (5 minutes) rather
 * than trusting the browser to send the email back later.
 */
export type NeedsUsername = {
  status: 'needs_username';
  pendingToken: string;
  email: string;
  displayName: string | null;
  avatarUrl: string | null;
  suggestions: string[];
};

export type AuthResult = AuthSuccess | NeedsUsername;

export type ApiErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'RATE_LIMITED'
  | 'EMAIL_TAKEN'
  | 'USERNAME_TAKEN'
  | 'USERNAME_INVALID'
  | 'WEAK_PASSWORD'
  | 'ACCOUNT_HAS_PASSWORD'
  | 'PENDING_EXPIRED'
  | 'GOOGLE_REJECTED'
  | 'UNAUTHENTICATED'
  | 'NETWORK'
  | 'UNKNOWN';

export class ApiError extends Error {
  code: ApiErrorCode;
  /** Present on RATE_LIMITED — seconds until the next attempt is allowed. */
  retryAfterSeconds?: number;
  status: number;

  constructor(
    code: ApiErrorCode,
    message: string,
    status = 0,
    retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const BASE = import.meta.env.VITE_API_BASE ?? '/api';

let accessToken: string | null = null;
let onTokenChange: ((token: string | null) => void) | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
  onTokenChange?.(token);
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function subscribeToToken(fn: (token: string | null) => void): void {
  onTokenChange = fn;
}

async function parseError(res: Response): Promise<ApiError> {
  let code: ApiErrorCode = 'UNKNOWN';
  let message = 'Something went wrong. Please try again.';
  let retryAfterSeconds: number | undefined;

  try {
    const body = await res.json();
    if (typeof body?.code === 'string') code = body.code as ApiErrorCode;
    if (typeof body?.message === 'string') message = body.message;
    if (typeof body?.retryAfterSeconds === 'number') {
      retryAfterSeconds = body.retryAfterSeconds;
    }
  } catch {
    /* non-JSON body (proxy error page, 502, …) — keep the defaults */
  }

  if (code === 'UNKNOWN' && res.status === 401) code = 'UNAUTHENTICATED';
  return new ApiError(code, message, res.status, retryAfterSeconds);
}

type RequestOptions = {
  method?: string;
  body?: unknown;
  /** Set false on the refresh call itself, to avoid recursing. */
  retryOn401?: boolean;
  signal?: AbortSignal;
};

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, retryOn401 = true, signal } = opts;

  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;

  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') throw e;
    throw new ApiError('NETWORK', 'Could not reach the server.', 0);
  }

  // Access token expired — spend the refresh cookie once, then replay.
  if (res.status === 401 && retryOn401 && accessToken) {
    try {
      await refresh();
    } catch {
      setAccessToken(null);
      throw new ApiError('UNAUTHENTICATED', 'Your session has expired.', 401);
    }
    return request<T>(path, { ...opts, retryOn401: false });
  }

  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

function adopt(result: AuthResult): AuthResult {
  if (result.status === 'authenticated') setAccessToken(result.accessToken);
  return result;
}

/* ---------------------------------------------------------------- calls */

export async function signUp(input: {
  username: string;
  email: string;
  password: string;
}): Promise<AuthResult> {
  const r = await request<AuthResult>('/auth/signup', {
    method: 'POST',
    body: input,
    retryOn401: false,
  });
  return adopt(r);
}

/** `identifier` is an email or a username — the backend works out which. */
export async function signIn(input: {
  identifier: string;
  password: string;
}): Promise<AuthResult> {
  const r = await request<AuthResult>('/auth/login', {
    method: 'POST',
    body: input,
    retryOn401: false,
  });
  return adopt(r);
}

/**
 * `credential` is the Google ID token (a JWT) that Google Identity Services
 * hands the browser. We do not trust it here — the backend verifies its
 * signature against Google's public keys and checks the audience before it
 * believes a single field inside it.
 */
export async function signInWithGoogle(credential: string): Promise<AuthResult> {
  const r = await request<AuthResult>('/auth/google', {
    method: 'POST',
    body: { credential },
    retryOn401: false,
  });
  return adopt(r);
}

/** Second half of the Google flow: claim a username for a pending identity. */
export async function claimUsername(input: {
  pendingToken: string;
  username: string;
}): Promise<AuthResult> {
  const r = await request<AuthResult>('/auth/google/username', {
    method: 'POST',
    body: input,
    retryOn401: false,
  });
  return adopt(r);
}

export async function refresh(): Promise<AuthSuccess> {
  const r = await request<AuthSuccess>('/auth/refresh', {
    method: 'POST',
    retryOn401: false,
  });
  setAccessToken(r.accessToken);
  return r;
}

export async function signOut(): Promise<void> {
  try {
    await request<void>('/auth/logout', { method: 'POST', retryOn401: false });
  } finally {
    // Clear locally even if the server call failed — the cookie is
    // httpOnly and expires on its own; the user asked to be signed out.
    setAccessToken(null);
  }
}

export type UsernameCheck = {
  available: boolean;
  /** Only populated when `available` is false. */
  suggestions: string[];
};

export async function checkUsername(
  username: string,
  signal?: AbortSignal,
): Promise<UsernameCheck> {
  return request<UsernameCheck>(
    `/auth/username-available?u=${encodeURIComponent(username)}`,
    { retryOn401: false, signal },
  );
}

/* ------------------------------------------------- shared client rules */

/**
 * Mirrors the server's rule. The server is the authority — this exists only
 * so the user is told before a round trip, never so the check can be skipped.
 */
export const USERNAME_PATTERN = /^[a-zA-Z0-9_]{3,20}$/;

export function validateUsername(u: string): string | null {
  if (u.length === 0) return null;
  if (u.length < 3) return 'At least 3 characters.';
  if (u.length > 20) return 'At most 20 characters.';
  if (!USERNAME_PATTERN.test(u)) {
    return 'Letters, numbers and underscores only.';
  }
  return null;
}

export const MIN_PASSWORD_LENGTH = 10;

/**
 * Length first, because that is what actually resists guessing. No
 * "one uppercase, one symbol" rule — NIST dropped composition rules in
 * SP 800-63B for the good reason that they mostly produce `Password1!`.
 * The server additionally rejects known-breached passwords, which this
 * cannot do.
 */
export function passwordStrength(pw: string): {
  score: 0 | 1 | 2 | 3 | 4;
  label: string;
} {
  if (pw.length === 0) return { score: 0, label: '' };
  if (pw.length < MIN_PASSWORD_LENGTH) {
    return { score: 1, label: `Too short — ${MIN_PASSWORD_LENGTH} minimum` };
  }

  let score = 1;
  if (pw.length >= 12) score++;
  if (pw.length >= 16) score++;
  const variety =
    Number(/[a-z]/.test(pw)) +
    Number(/[A-Z]/.test(pw)) +
    Number(/[0-9]/.test(pw)) +
    Number(/[^a-zA-Z0-9]/.test(pw));
  if (variety >= 3) score++;

  const capped = Math.min(score, 4) as 1 | 2 | 3 | 4;
  const labels = { 1: 'Weak', 2: 'Fair', 3: 'Good', 4: 'Strong' } as const;
  return { score: capped, label: labels[capped] };
}
