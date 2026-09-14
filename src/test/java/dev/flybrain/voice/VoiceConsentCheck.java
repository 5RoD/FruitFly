package dev.flybrain.voice;

public final class VoiceConsentCheck {
    public static void main(String[] args) {
        var gate = new VoiceReceiveConsent();
        if (gate.acceptServerState(true)) throw new AssertionError("server invented client consent");
        gate.command("flybrain voice receive on");
        if (!gate.acceptServerState(true)) throw new AssertionError("explicit receive consent ignored");
        gate.command("flybrain voice receive off");
        if (gate.acceptServerState(true)) throw new AssertionError("revoked local consent resurrected by server");
        gate.command("flybrain voice receive on"); gate.disconnect();
        if (gate.acceptServerState(true)) throw new AssertionError("consent survived connection");
        gate.command("say flybrain voice receive on");
        if (gate.acceptServerState(true)) throw new AssertionError("unrelated command enabled receive");
        System.out.println("VoiceConsentCheck PASS: local explicit receive gate, hostile server state rejection, off/disconnect revocation, exact command matching");
    }
}
