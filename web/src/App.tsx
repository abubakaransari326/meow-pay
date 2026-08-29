import { useState } from "react";
import { AuthPage } from "./AuthPage";
import { SendStub } from "./SendStub";
import { getToken, setToken } from "./api";

export function App() {
  const [signedIn, setSignedIn] = useState(() => Boolean(getToken()));

  if (!signedIn) {
    return <AuthPage onSignedIn={() => setSignedIn(true)} />;
  }

  return (
    <SendStub
      onSignedOut={() => {
        setToken(null);
        setSignedIn(false);
      }}
    />
  );
}
