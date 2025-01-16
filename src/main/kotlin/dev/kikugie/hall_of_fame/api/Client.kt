package dev.kikugie.hall_of_fame.api

import kotlinx.serialization.json.Json
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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

    suspend inline fun <reified T> get(url: String, headers: Map<String, String> = emptyMap()) = suspendCoroutine {
        val builder = Request.Builder().url(url)
        for ((name, value) in headers) builder.header(name, value)
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = it.resume(Result.failure(e))
            override fun onResponse(call: Call, response: Response) = it.resume(kotlin.runCatching {
                if (!response.isSuccessful)
                    error("Request failed with code ${response.code}:\n${response.message}")
                val body = response.body!!.string().takeUnless(String::isBlank) ?: "\"\""
                json.decodeFromString<T>(body)
            })
        })
    }
}