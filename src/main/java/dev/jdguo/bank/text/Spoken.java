package dev.jdguo.bank.text;

/** One player-facing line, kept in Chinese and English together. */
public record Spoken(String zh, String en, Tone tone) {
    public String text(boolean english) {
        return english ? en : zh;
    }
}
