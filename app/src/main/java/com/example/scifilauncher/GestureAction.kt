package com.example.scifilauncher

/** Every gesture Xenos can be trained to recognize, and what it does when recognized. Two real,
 * different KINDS of gesture live here, matched differently (see GestureMatcher.kt):
 * - TRAJECTORY gestures (every action below): a short hand motion over ~1s, matched by comparing
 *   the live palm-center path against recorded reference paths (DTW distance).
 * - HOLD gestures: a static hand shape/pose held in place, matched every frame by comparing the
 *   live 21-landmark shape against a recorded reference pose, firing repeatedly for as long as
 *   the pose keeps matching. No action currently uses this kind - continuous scroll via a held
 *   pose was removed in favor of repeated swipes with return-motion suppression (see
 *   HandGestureService's OPPOSITE_SWIPE_COOLDOWN_MS) - kept as infrastructure (GestureMatcher's
 *   pose functions, HandGestureService.checkHoldGesture) in case a future gesture wants it.
 */
enum class GestureKind { TRAJECTORY, HOLD }

/** Which gestures are meaningfully comparable to each other - both the live recognizer
 * (HandGestureService) and the training screen's distinctiveness/axis checks used to each
 * re-derive this independently (one comparing raw actions with ad-hoc `!= ZOOM_IN` filters, the
 * other with its own isZoomGesture()/isCardinalSwipe()/swipeAxis() helpers), and those two
 * implementations had already drifted out of sync once this session (a direction-sign check one
 * had and the other didn't). One shared definition here instead. */
enum class GestureFamily { SWIPE_HORIZONTAL, SWIPE_VERTICAL, ZOOM, HOLD, OTHER_TRAJECTORY }

enum class GestureAction(val id: String, val label: String, val kind: GestureKind, val family: GestureFamily) {
    SWIPE_LEFT("swipe_left", "Swipe Left", GestureKind.TRAJECTORY, GestureFamily.SWIPE_HORIZONTAL),
    SWIPE_RIGHT("swipe_right", "Swipe Right", GestureKind.TRAJECTORY, GestureFamily.SWIPE_HORIZONTAL),
    SWIPE_UP("swipe_up", "Swipe Up", GestureKind.TRAJECTORY, GestureFamily.SWIPE_VERTICAL),
    SWIPE_DOWN("swipe_down", "Swipe Down", GestureKind.TRAJECTORY, GestureFamily.SWIPE_VERTICAL),
    OPEN_GLOBE("open_globe", "Open Map (Globe)", GestureKind.TRAJECTORY, GestureFamily.OTHER_TRAJECTORY),
    // Pinch-to-zoom used to be a fixed thumb/index-distance heuristic only - now trainable like
    // everything else, since a fixed threshold can't know how any one person actually pinches.
    // Captured as a TRAJECTORY of thumb-index distance over time (not palm XY position) - see
    // GestureTrainingActivity's capture branch and HandGestureService's checkZoomGesture.
    // Falls back to the old fixed heuristic (HandGestureService.checkFixedPinch) until trained.
    ZOOM_IN("zoom_in", "Zoom In", GestureKind.TRAJECTORY, GestureFamily.ZOOM),
    ZOOM_OUT("zoom_out", "Zoom Out", GestureKind.TRAJECTORY, GestureFamily.ZOOM),
    // Reactor doesn't exist yet (see planner/not_started.md) - kept as a real, selectable,
    // trainable slot so it's ready the moment it's built, rather than bolted on later. Firing it
    // now tells the user plainly it's not built yet instead of silently doing nothing.
    OPEN_REACTOR("open_reactor", "Open Reactor (coming soon)", GestureKind.TRAJECTORY, GestureFamily.OTHER_TRAJECTORY),
    SLEEP("sleep", "Sleep (Lock Screen)", GestureKind.TRAJECTORY, GestureFamily.OTHER_TRAJECTORY),
    OPEN_AI_CHAT("open_ai_chat", "Open AI Chat", GestureKind.TRAJECTORY, GestureFamily.OTHER_TRAJECTORY),
    // The only action needing extra configuration beyond the recorded gesture itself - which
    // single app to open, chosen at training time (see GestureTrainingActivity's app picker).
    OPEN_APP("open_app", "Open App", GestureKind.TRAJECTORY, GestureFamily.OTHER_TRAJECTORY);

    companion object {
        fun fromId(id: String): GestureAction? = entries.firstOrNull { it.id == id }
    }
}
