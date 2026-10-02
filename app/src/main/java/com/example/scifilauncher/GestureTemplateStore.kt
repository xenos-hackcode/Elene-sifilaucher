package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One recorded repetition of a gesture - a flat list of normalized floats. For a TRAJECTORY
 * gesture this is [x0,y0, x1,y1, ...] (a resampled, fixed-length palm-center path). For a HOLD
 * gesture this is the 21 hand landmarks' (x,y) positions in one frame, normalized relative to
 * the wrist and hand size so it doesn't matter how close/far the hand is from the camera. */
data class GestureSample(val points: FloatArray)

data class GestureTrainingData(
    val action: GestureAction,
    val samples: List<GestureSample>,
    // Only meaningful for GestureAction.OPEN_APP - which single app this gesture opens.
    val openAppPackage: String? = null
)

private const val PREFS = "gesture_training_prefs"
const val MIN_SAMPLES_TO_BE_TRAINED = 3

/** Real, persistent recorded gesture data - same SharedPreferences+JSON idiom used everywhere
 * else in this app (LocationHistory, NearbyDeviceHistory, RecentAppHistory, etc.). */
object GestureTemplateStore {

    @Synchronized
    fun load(context: Context, action: GestureAction): GestureTrainingData? {
        val raw = prefs(context).getString(action.id, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val samplesArray = obj.getJSONArray("samples")
            val samples = (0 until samplesArray.length()).map { i ->
                val pointsArray = samplesArray.getJSONArray(i)
                GestureSample(FloatArray(pointsArray.length()) { j -> pointsArray.getDouble(j).toFloat() })
            }
            GestureTrainingData(
                action = action,
                samples = samples,
                openAppPackage = if (obj.has("openAppPackage")) obj.getString("openAppPackage") else null
            )
        }.getOrNull()
    }

    fun loadAll(context: Context): Map<GestureAction, GestureTrainingData> =
        GestureAction.entries.mapNotNull { action -> load(context, action)?.let { action to it } }.toMap()

    fun isTrained(context: Context, action: GestureAction): Boolean =
        (load(context, action)?.samples?.size ?: 0) >= MIN_SAMPLES_TO_BE_TRAINED

    @Synchronized
    fun addSample(context: Context, action: GestureAction, sample: GestureSample) {
        val existing = load(context, action)
        val updated = GestureTrainingData(
            action = action,
            samples = (existing?.samples.orEmpty()) + sample,
            openAppPackage = existing?.openAppPackage
        )
        save(context, updated)
    }

    @Synchronized
    fun setOpenAppPackage(context: Context, packageName: String) {
        val existing = load(context, GestureAction.OPEN_APP)
        save(context, GestureTrainingData(GestureAction.OPEN_APP, existing?.samples.orEmpty(), packageName))
    }

    @Synchronized
    fun clear(context: Context, action: GestureAction) {
        prefs(context).edit().remove(action.id).apply()
    }

    private fun save(context: Context, data: GestureTrainingData) {
        val obj = JSONObject()
        val samplesArray = JSONArray()
        data.samples.forEach { sample ->
            val pointsArray = JSONArray()
            sample.points.forEach { pointsArray.put(it.toDouble()) }
            samplesArray.put(pointsArray)
        }
        obj.put("samples", samplesArray)
        if (data.openAppPackage != null) obj.put("openAppPackage", data.openAppPackage)
        prefs(context).edit().putString(data.action.id, obj.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
