package fm.rizx.player.domain.sync

/** Why a sync run was asked for. Logging and debugging only: every reason runs the same way. */
enum class SyncReason {
    /** The session became signed in, including opening the app with a stored session. */
    SIGN_IN,
    /** A local edit settled (debounced). */
    LOCAL_EDIT,
    /** The app came back on screen after a quiet spell or with edits waiting. */
    FOREGROUND,
    /** The periodic backstop. */
    PERIODIC,
    /** Another device pushed something: the invalidation channel said so. */
    REALTIME,
    /** The account-merge choice. */
    MERGE,
    /** The button on the account screen. */
    MANUAL,
}
