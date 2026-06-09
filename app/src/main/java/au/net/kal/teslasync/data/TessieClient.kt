package au.net.kal.teslasync.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin Tessie REST client. Every Tessie call funnels through here (single point of control)
 * so timeouts, auth headers and logging live in one place. Call from a background thread
 * (e.g. Dispatchers.IO) — these methods block.
 */
class TessieClient(
    private val client: OkHttpClient = defaultClient(),
) {
    sealed interface Result {
        data class Ok(val body: String) : Result
        data class HttpError(val code: Int, val message: String) : Result
        data class Failure(val error: Throwable) : Result
    }

    /** GET /{vin}/state — use_cache=true returns data <15s old without waking the car. */
    fun getState(vin: String, token: String): Result =
        get("$BASE/$vin/state?use_cache=true", token)

    /** GET /vehicles — for the VIN picker. */
    fun getVehicles(token: String): Result =
        get("$BASE/vehicles", token)

    private fun get(url: String, token: String): Result {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        val started = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - started
                Log.i(TAG, "GET ${url.substringAfter(BASE)} -> ${resp.code} (${ms}ms)")
                if (resp.isSuccessful) Result.Ok(body)
                else Result.HttpError(resp.code, body.take(200))
            }
        } catch (e: IOException) {
            Log.w(TAG, "GET ${url.substringAfter(BASE)} failed", e)
            Result.Failure(e)
        }
    }

    companion object {
        private const val TAG = "TessieClient"
        private const val BASE = "https://api.tessie.com"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
