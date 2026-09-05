package com.arise.assistant.tools

import android.content.Context
import com.arise.assistant.engine.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Live data executors using public open APIs (no key required). All results
 * verified against the HTTP status before being reported as success.
 */
object InfoExecutors {

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    fun all(): List<ToolSpec> = listOf(
        ToolSpec("get_weather", "Get the current weather for a city",
            "place: city/town (required)") { a -> weather(a) }
    )

    private suspend fun AgentContext.weather(args: Map<String, String>): ToolResult {
        args.req("get_weather", "place")?.let { return it }
        val place = args["place"]!!.trim().take(60)
        return withContext(Dispatchers.IO) {
            try {
                val geoReq = Request.Builder()
                    .url("https://geocoding-api.open-meteo.com/v1/search?name=${android.net.Uri.encode(place)}&count=1&language=en&format=json")
                    .build()
                val geoText = client.newCall(geoReq).execute().use { r ->
                    if (!r.isSuccessful) return@withContext failResult("get_weather", "Weather lookup failed (${r.code})")
                    r.body?.string().orEmpty()
                }
                val geo = JSONObject(geoText).optJSONArray("results")?.optJSONObject(0)
                    ?: return@withContext failResult("get_weather", "I couldn’t find “$place” — try a bigger city")
                val lat = geo.getDouble("latitude"); val lon = geo.getDouble("longitude")
                val name = geo.optString("name", place)
                val wxReq = Request.Builder()
                    .url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto")
                    .build()
                val wxText = client.newCall(wxReq).execute().use { r ->
                    if (!r.isSuccessful) return@withContext failResult("get_weather", "Forecast service unavailable (${r.code})")
                    r.body?.string().orEmpty()
                }
                val cur = JSONObject(wxText).optJSONObject("current")
                    ?: return@withContext failResult("get_weather", "No current conditions available")
                val temp = cur.optDouble("temperature_2m").roundToInt()
                val code = cur.optInt("weather_code", 0)
                val wind = cur.optDouble("wind_speed_10m").roundToInt()
                okResult("get_weather", "In $name it is $temp° with ${describe(code)} and wind at $wind km/h", output = "weather:$name:$temp")
            } catch (e: Exception) {
                failResult("get_weather", "Couldn’t reach the weather service (${e.javaClass.simpleName})")
            }
        }
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "clear skies"
        1, 2, 3 -> "partly cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "thunderstorms"
        else -> "mixed conditions"
    }
}

// keep Context import referenced for future param docs
private typealias _Ctx = Context
