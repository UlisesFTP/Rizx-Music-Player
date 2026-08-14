package fm.rizx.player.domain.model

/**
 * Which reading of a lyric the screen is showing.
 *
 * Only relevant for a song written in a script the listener can't read: the words are right, and still
 * unusable. The three readings are alternatives, not layers — showing two at once doubles the height of
 * every line and halves how much of the song fits on screen.
 */
enum class LyricsDisplayMode {
    /** The lyric as it was written. Always available, always the default. */
    ORIGINAL,

    /** How it sounds, in Latin letters. What you need to sing along. */
    PRONUNCIATION,

    /** What it means, in the app's language. What you need to understand it. */
    TRANSLATION,
}
