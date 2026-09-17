import { useEffect, useRef, useState } from 'react';

/**
 * Google Identity Services sign-in.
 *
 * Flow: GIS renders its own button -> the user picks an account -> Google
 * hands the browser an ID token (a signed JWT) -> we post it to
 * POST /api/auth/google -> the backend verifies the signature against
 * Google's public keys, checks `aud` and `iss`, and only then trusts the
 * email inside. Nothing about the identity is taken from this component.
 *
 * Why Google's own button rather than one styled like the rest of the form:
 * the ID-token flow is only exposed through `renderButton` and One Tap.
 * Driving it from a custom button means switching to the OAuth 2.0 code
 * flow, which returns an authorization code instead of an ID token and
 * needs a different (larger) backend. `theme: 'filled_black'` is the
 * closest Google ships to the terminal palette. Google's brand terms also
 * require their button be recognisably theirs.
 */

type CredentialResponse = { credential?: string };

type GsiIdApi = {
  initialize: (config: {
    client_id: string;
    callback: (res: CredentialResponse) => void;
    auto_select?: boolean;
    cancel_on_tap_outside?: boolean;
    use_fedcm_for_prompt?: boolean;
  }) => void;
  renderButton: (
    parent: HTMLElement,
    options: {
      type?: 'standard' | 'icon';
      theme?: 'outline' | 'filled_blue' | 'filled_black';
      size?: 'small' | 'medium' | 'large';
      text?: 'signin_with' | 'signup_with' | 'continue_with' | 'signin';
      shape?: 'rectangular' | 'pill' | 'circle' | 'square';
      logo_alignment?: 'left' | 'center';
      width?: number;
    },
  ) => void;
  disableAutoSelect: () => void;
};

declare global {
  interface Window {
    google?: { accounts: { id: GsiIdApi } };
  }
}

const GSI_SRC = 'https://accounts.google.com/gsi/client';

/** Loads the GIS script once per page, no matter how many callers ask. */
let gsiPromise: Promise<void> | null = null;

function loadGsi(): Promise<void> {
  if (gsiPromise) return gsiPromise;

  gsiPromise = new Promise<void>((resolve, reject) => {
    if (window.google?.accounts?.id) {
      resolve();
      return;
    }
    const existing = document.querySelector<HTMLScriptElement>(
      `script[src="${GSI_SRC}"]`,
    );
    const script = existing ?? document.createElement('script');
    script.addEventListener('load', () => resolve(), { once: true });
    script.addEventListener(
      'error',
      () => reject(new Error('Google sign-in could not be loaded.')),
      { once: true },
    );
    if (!existing) {
      script.src = GSI_SRC;
      script.async = true;
      script.defer = true;
      document.head.appendChild(script);
    }
  }).catch((e) => {
    // Let a later mount try again (blocked once by an extension or a
    // flaky network should not disable Google for the whole session).
    gsiPromise = null;
    throw e;
  });

  return gsiPromise;
}

export default function GoogleButton({
  onCredential,
  onError,
  text = 'continue_with',
  width = 400,
}: {
  onCredential: (credential: string) => void;
  onError?: (message: string) => void;
  text?: 'signin_with' | 'signup_with' | 'continue_with';
  width?: number;
}) {
  const slot = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading');

  // Keep the callback in a ref: GIS holds whatever function it was given at
  // initialize() time, and re-initializing on every render would rebuild the
  // button under the user's cursor.
  const handler = useRef(onCredential);
  handler.current = onCredential;

  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;

  useEffect(() => {
    if (!clientId) {
      setState('failed');
      onError?.(
        'Google sign-in is not configured. Set VITE_GOOGLE_CLIENT_ID in your .env file.',
      );
      return;
    }

    let disposed = false;

    loadGsi()
      .then(() => {
        if (disposed || !slot.current) return;
        const id = window.google?.accounts?.id;
        if (!id) throw new Error('Google sign-in is unavailable.');

        id.initialize({
          client_id: clientId,
          callback: (res) => {
            if (res.credential) handler.current(res.credential);
          },
          auto_select: false,
          cancel_on_tap_outside: true,
        });

        slot.current.replaceChildren();
        id.renderButton(slot.current, {
          type: 'standard',
          theme: 'filled_black',
          size: 'large',
          text,
          shape: 'rectangular',
          logo_alignment: 'left',
          // GIS clamps this to 400; anything larger is silently ignored.
          width: Math.min(width, 400),
        });
        setState('ready');
      })
      .catch((e: Error) => {
        if (disposed) return;
        setState('failed');
        onError?.(e.message);
      });

    return () => {
      disposed = true;
    };
    // `onError` and `text`/`width` are intentionally not dependencies: a
    // change to any of them should not tear down and rebuild the button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientId]);

  if (state === 'failed') {
    return (
      <button type="button" className="tsb-auth__google" disabled>
        Google sign-in unavailable
      </button>
    );
  }

  return (
    <div style={{ minHeight: 50, display: 'flex', justifyContent: 'center' }}>
      {state === 'loading' && (
        <button
          type="button"
          className="tsb-auth__google"
          style={{ width: '100%' }}
          disabled
        >
          <span className="tsb-auth__spinner" aria-hidden="true" />
          Loading Google…
        </button>
      )}
      <div ref={slot} style={{ display: state === 'ready' ? 'block' : 'none' }} />
    </div>
  );
}

/**
 * Call on sign-out, so the next visit does not silently re-select the same
 * Google account and make "sign out" look broken.
 */
export function forgetGoogleAccount(): void {
  window.google?.accounts?.id?.disableAutoSelect();
}
