package dev.flybrain.voice;

/** Only a command originating on this client may authorize local hearing/cache receipt. */
public final class VoiceReceiveConsent {
    private volatile boolean local;
    public void command(String command) {
        String text = command.strip().replaceAll("\\s+", " ");
        if (text.equals("flybrain voice receive on")) local = true;
        else if (text.equals("flybrain voice receive off")) local = false;
    }
    public void disconnect() { local = false; }
    public boolean acceptServerState(boolean requested) { return local && requested; }
}
