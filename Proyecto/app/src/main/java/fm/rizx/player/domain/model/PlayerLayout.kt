package fm.rizx.player.domain.model

/**
 * How the Now Playing screen stacks its controls. Purely an arrangement — every layout shows the same
 * controls, so switching one never hides an action.
 *
 * [CLASSIC] is the original: progress, times, title, transport, then like and add-to-playlist on their
 * own row underneath. [COMPACT] lifts that pair up to flank the title, above the progress bar, which
 * puts every non-transport control in the screen's top half and buys the artwork the row it frees.
 */
enum class PlayerLayout { CLASSIC, COMPACT }
