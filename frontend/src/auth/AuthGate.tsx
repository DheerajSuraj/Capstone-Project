import { useState, type ReactNode } from 'react';
import { useAuth } from './AuthContext';
import SignIn from './SignIn';
import SignUp from './SignUp';
import ChooseUsername from './ChooseUsername';
import './auth.css';

/**
 * Wrap the application in this. It renders `children` only once somebody is
 * signed in, and otherwise shows the right auth screen.
 *
 *   <AuthProvider>
 *     <AuthGate>
 *       <App />
 *     </AuthGate>
 *   </AuthProvider>
 *
 * If you later add a router, replace this with routes for /login, /signup
 * and /welcome — the three screens are independent components and do not
 * depend on this file.
 */
export default function AuthGate({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const [screen, setScreen] = useState<'signin' | 'signup'>('signin');

  if (status === 'booting') {
    // The silent refresh call. Deliberately near-empty: a spinner that
    // flashes for 40 ms is worse than a still frame.
    return (
      <div
        className="tsb-auth"
        style={{ justifyContent: 'center', alignItems: 'center' }}
      >
        <span className="tsb-auth__spinner" style={{ color: '#3a4049' }} />
      </div>
    );
  }

  if (status === 'authenticated') return <>{children}</>;

  if (status === 'needs_username') return <ChooseUsername />;

  return screen === 'signin' ? (
    <SignIn onGoToSignUp={() => setScreen('signup')} />
  ) : (
    <SignUp onGoToSignIn={() => setScreen('signin')} />
  );
}
