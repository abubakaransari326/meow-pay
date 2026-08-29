import { useCallback, useState } from "react";
import { AuthPage } from "./AuthPage";
import { SendPage } from "./SendPage";
import { getToken, setToken } from "./api";

export function App() {
  const [signedIn, setSignedIn] = useState(() => Boolean(getToken()));

  const signOut = useCallback(() => {
    setToken(null);
    setSignedIn(false);
  }, []);

  if (!signedIn) {
    return <AuthPage onSignedIn={() => setSignedIn(true)} />;
  }

  return <SendPage onSignedOut={signOut} onUnauthorized={signOut} />;
}
