package dev.jdguo.bank.ui;

/** Connection-local retry state; channel registration can arrive after JOIN. */
final class BankUiHandshake {
    private int ticks;
    private int attempts;
    void reset() { ticks = 0; attempts = 0; }
    boolean poll(boolean connected, boolean advertised) {
        if (!connected || !advertised || attempts >= 5) return false;
        if (ticks++ % 20 != 0) return false;
        attempts++;
        return true;
    }
    void acknowledged() { attempts = 5; }
}
