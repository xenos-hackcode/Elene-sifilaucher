package com.example.scifilauncher

import kotlin.math.sqrt

/** Real trajectory/pose comparison math backing gesture training's distinctiveness check and
 * HandGestureService's live recognition - not a placeholder distance function. Two different
 * comparisons for the two different gesture kinds (see GestureAction.GestureKind):
 * - TRAJECTORY (the four swipes, open globe/reactor/chat/app, sleep): Dynamic Time Warping over
 *   a resampled, normalized 2D palm-center path - DTW because two people (or the same person
 *   twice) rarely swipe at exactly the same speed, and DTW tolerates that stretch/compression
 *   that a plain point-by-point distance would incorrectly penalize.
 * - HOLD (the two continuous-scroll gestures): plain Euclidean distance between two normalized
 *   21-landmark hand-shape snapshots - a held pose has no time axis to warp.
 */
object GestureMatcher {

    const val TRAJECTORY_RESAMPLE_LENGTH = 24
    // Landmark count MediaPipe Hand Landmarker always reports per hand.
    const val POSE_LANDMARK_COUNT = 21

    /** Resamples a variable-length recorded (x,y) path to a fixed number of evenly-spaced
     * points via linear interpolation along the path's own arc length - so a slow 40-frame swipe
     * and a fast 12-frame swipe of the same real shape resample to comparable point counts. */
    fun resampleTrajectory(rawXY: List<Pair<Float, Float>>, targetLength: Int = TRAJECTORY_RESAMPLE_LENGTH): FloatArray {
        if (rawXY.size < 2) {
            val x = rawXY.firstOrNull()?.first ?: 0f
            val y = rawXY.firstOrNull()?.second ?: 0f
            return FloatArray(targetLength * 2) { i -> if (i % 2 == 0) x else y }
        }
        val cumulative = DoubleArray(rawXY.size)
        for (i in 1 until rawXY.size) {
            val dx = rawXY[i].first - rawXY[i - 1].first
            val dy = rawXY[i].second - rawXY[i - 1].second
            cumulative[i] = cumulative[i - 1] + sqrt((dx * dx + dy * dy).toDouble())
        }
        val totalLength = cumulative.last().coerceAtLeast(1e-6)

        val out = FloatArray(targetLength * 2)
        for (step in 0 until targetLength) {
            val targetDist = totalLength * step / (targetLength - 1).coerceAtLeast(1)
            var segment = 0
            while (segment < cumulative.size - 2 && cumulative[segment + 1] < targetDist) segment++
            val segStart = cumulative[segment]
            val segEnd = cumulative[segment + 1].coerceAtLeast(segStart + 1e-6)
            val t = ((targetDist - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0)
            val x = rawXY[segment].first + (rawXY[segment + 1].first - rawXY[segment].first) * t
            val y = rawXY[segment].second + (rawXY[segment + 1].second - rawXY[segment].second) * t
            out[step * 2] = x.toFloat()
            out[step * 2 + 1] = y.toFloat()
        }
        return out
    }

    /** Centers on the path's own centroid and scales to unit extent - two recordings of "the
     * same" swipe made in different parts of the camera frame, or slightly bigger/smaller,
     * should compare as near-identical, not different, once normalized this way. */
    fun normalizeTrajectory(flatXY: FloatArray): FloatArray {
        var cx = 0f
        var cy = 0f
        val n = flatXY.size / 2
        for (i in 0 until n) {
            cx += flatXY[i * 2]
            cy += flatXY[i * 2 + 1]
        }
        cx /= n
        cy /= n
        var maxExtent = 1e-6f
        for (i in 0 until n) {
            val dx = flatXY[i * 2] - cx
            val dy = flatXY[i * 2 + 1] - cy
            maxExtent = maxOf(maxExtent, sqrt(dx * dx + dy * dy))
        }
        val out = FloatArray(flatXY.size)
        for (i in 0 until n) {
            out[i * 2] = (flatXY[i * 2] - cx) / maxExtent
            out[i * 2 + 1] = (flatXY[i * 2 + 1] - cy) / maxExtent
        }
        return out
    }

    /** Standard DTW over 2D point sequences (already resampled+normalized). Lower = more alike;
     * 0 = identical. */
    fun dtwDistance(a: FloatArray, b: FloatArray): Float {
        val n = a.size / 2
        val m = b.size / 2
        val cost = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        cost[0][0] = 0f
        for (i in 1..n) {
            for (j in 1..m) {
                val dx = a[(i - 1) * 2] - b[(j - 1) * 2]
                val dy = a[(i - 1) * 2 + 1] - b[(j - 1) * 2 + 1]
                val d = sqrt(dx * dx + dy * dy)
                cost[i][j] = d + minOf(cost[i - 1][j], cost[i][j - 1], cost[i - 1][j - 1])
            }
        }
        return cost[n][m] / (n + m) // averaged so path length doesn't dominate the score
    }

    /** Normalizes a pose (21 landmarks' x,y) relative to the wrist (landmark 0) and the hand's
     * own size (wrist-to-middle-knuckle distance), so it doesn't matter how close to the camera
     * or where in frame the hand is. */
    fun normalizePose(flatLandmarksXY: FloatArray): FloatArray {
        val wristX = flatLandmarksXY[0]
        val wristY = flatLandmarksXY[1]
        // Landmark 9 = middle finger MCP - a stable reference for hand scale.
        val refX = flatLandmarksXY[9 * 2]
        val refY = flatLandmarksXY[9 * 2 + 1]
        val scale = sqrt((refX - wristX) * (refX - wristX) + (refY - wristY) * (refY - wristY)).coerceAtLeast(1e-4f)

        val out = FloatArray(flatLandmarksXY.size)
        for (i in 0 until POSE_LANDMARK_COUNT) {
            out[i * 2] = (flatLandmarksXY[i * 2] - wristX) / scale
            out[i * 2 + 1] = (flatLandmarksXY[i * 2 + 1] - wristY) / scale
        }
        return out
    }

    /** Plain Euclidean distance between two normalized pose vectors - lower = more alike. */
    fun poseDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum / a.size)
    }

    /** Best (lowest) distance from [sample] to any of [templateSamples] - a gesture is recorded
     * as several reps, so live recognition compares against whichever recorded rep is closest,
     * not just an average that could blur out real variation between reps. */
    fun bestTrajectoryDistance(sample: FloatArray, templateSamples: List<FloatArray>): Float =
        templateSamples.minOfOrNull { dtwDistance(sample, it) } ?: Float.POSITIVE_INFINITY

    fun bestPoseDistance(sample: FloatArray, templateSamples: List<FloatArray>): Float =
        templateSamples.minOfOrNull { poseDistance(sample, it) } ?: Float.POSITIVE_INFINITY
}
