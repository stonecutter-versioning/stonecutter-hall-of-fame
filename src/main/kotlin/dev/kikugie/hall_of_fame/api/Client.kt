package dev.kikugie.hall_of_fame.api

import kotlinx.serialization.json.Json
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

object Client {
    val duration = 30.seconds.toJavaDuration()
    val client = OkHttpClient.Builder()
        .connectTimeout(duration)
        .readTimeout(duration)
        .writeTimeout(duration)
        .addNetworkInterceptor { chain ->
            chain.proceed(
                chain.request()
                    .newBuilder()
                    .header("User-Agent", "kikugie/stonecutter")
                    .build()
            )
        }
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    inline fun parameters(block: RequestParameters.() -> Unit) = RequestParameters().apply(block)

    suspend inline fun <reified T> get(url: String, parameters: RequestParameters = RequestParameters()) = suspendCoroutine {
        val builder = Request.Builder().url(url).method(parameters.method, parameters.body).apply {
            parameters.headers.forEach { (key, value) -> header(key, value) }
        }
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = it.resume(Result.failure(e))
            override fun onResponse(call: Call, response: Response) = it.resume(kotlin.runCatching {
                if (!response.isSuccessful)
                    error("Request failed with code ${response.code}:\n${response.message.ifBlank { response.body?.string() }}")
                val body = response.body!!.string().takeUnless(String::isBlank) ?: "\"\""
                json.decodeFromString<T>(body)
            })
        })
    }

    class RequestParameters {
        var method: String = "GET"
        val headers: MutableMap<String, String> = mutableMapOf()
        var body: RequestBody? = null
    }
}