type Props = {
  onSignedOut: () => void;
};

export function SendStub({ onSignedOut }: Props) {
  return (
    <main className="page">
      <p className="eyebrow">MeowPay</p>
      <h1>You are signed in.</h1>
      <p className="lede">Wallet and send land in the next step.</p>
      <p>
        <button type="button" className="link" onClick={onSignedOut}>
          Sign out
        </button>
      </p>
    </main>
  );
}
