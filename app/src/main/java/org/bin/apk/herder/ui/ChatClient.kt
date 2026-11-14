package org.bin.apk.herder.ui

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String)

/**
 * One streamed piece of a reply, channelled by source (v0.2.8): the
 * model's thinking (reasoning_content) and its actual reply (content)
 * arrive as separate deltas and the UI files them into separate
 * cards, so the callbacks carry which channel each piece came on.
 */
enum class TokenKind { REASONING, CONTENT }

/**
 * The thinnest possible OpenAI chat-completions client, talking to the
 * llama-server Herder itself is running. Streaming via SSE so the reply
 * appears as it is generated.
 */
object ChatClient {
    /**
     * Per-request generation cap, set by the client (this tab) — not by
     * the server, whose launch stays uncapped so other clients keep
     * their own say. Reasoning tokens count against it. Without a cap,
     * the Qwen3.5 presets will think about a one-word greeting for
     * 3,600+ tokens (~15 minutes on a Snapdragon 680) and never answer;
     * 1024 ends that in a couple of minutes at worst.
     *
     * v0.2.10: this client also asks for NO thinking at all (see
     * [addNoThinking] below). Measured against the real server
     * (2026-11-10): a one-sentence request made the 0.8B deliberate
     * for 1,716 tokens before answering, so with the 1024 cap the
     * reply literally never existed — the chat showed an endless
     * Thinking card and nothing else (Brian's report). The cap alone
     * could not fix that; only not-thinking does.
     */
    private const val MAX_TOKENS = 1024

    /**
     * Turns the model's thinking off for THIS client's requests: the
     * Qwen3.5 chat template reads enable_thinking from
     * chat_template_kwargs, and llama-server (b12110, verified)
     * honours it — reasoning comes back empty and the reply starts at
     * once (21 tokens for the test sentence, vs 1,716 of thought).
     * The server launch is unchanged: other clients keep their own
     * say about thinking, and if a future model or template ignores
     * the kwarg, its reasoning still lands in the Thinking card
     * rather than polluting the reply.
     */
    private fun addNoThinking(body: JSONObject) {
        body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    }

    /**
     * The Views UI's entry point: streaming chat as a plain blocking
     * call on a daemon thread. [onToken] is invoked on the main
     * thread; the terminal [onDone] also runs on the main thread.
     * Cancellation: closing the stream is the UI's own business —
     * navigating away mid-stream drops the connection when the reader
     * errors out, exactly as the Compose UI behaved.
     *
     * This is the ONLY streaming implementation (v0.3.0 review pass):
     * a coroutine twin (streamChat) sat beside it unused, and every
     * fix — the v0.2.8 channelling, the v0.2.10 no-thinking — had to
     * be made twice, in two copies that had already diverged. Deleted.
     */
    fun chatAsync(
        port: Int,
        history: List<ChatMessage>,
        onToken: (TokenKind, String) -> Unit,
        onDone: (Result<String>) -> Unit,
    ) {
        Thread {
            val result = runCatching { blockingChat(port, history, onToken) }
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }.apply { isDaemon = true }.start()
    }

    /** The streaming loop, synchronous. Runs entirely on the caller's thread. */
    private fun blockingChat(
        port: Int,
        history: List<ChatMessage>,
        onToken: (TokenKind, String) -> Unit,
    ): String {
        val mainHandler = Handler(Looper.getMainLooper())
        val messagesJson = JSONArray()
        for (message in history) {
            messagesJson.put(JSONObject().apply {
                put("role", message.role)
                put("content", message.content)
            })
        }
        val requestJson = JSONObject().apply {
            put("messages", messagesJson)
            put("stream", true)
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.7)
        }
        addNoThinking(requestJson)
        val body = requestJson.toString()

        val connection = URL("http://[::1]:$port/v1/chat/completions")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15_000
        // Five minutes of SILENCE, not an infinite wait (v0.3.0 review
        // pass): readTimeout was 0, so a server that stayed alive but
        // stopped sending — hung slot, wedged generation — wedged the
        // chat tab forever; Send stayed disabled until the activity
        // died. The honest trade-off: once generation starts, tokens
        // stream continuously, so the only silent window is prompt
        // processing, and five minutes of it is beyond anything the
        // bounded history can produce even on the slowest device this
        // app targets. A slow-but-honest stream never trips this; a
        // wedged one now ends with an error instead of a frozen tab.
        connection.readTimeout = 300_000
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) {
            val error = runCatching { connection.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
            throw IllegalStateException("Server returned HTTP ${connection.responseCode}: ${error?.take(300)}")
        }
        val full = StringBuilder()
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                // Parse the delta. Reasoning models (the Qwen3.5
                // presets) stream their thinking in reasoning_content
                // and send content as JSON null meanwhile — and
                // org.json coerces a JSON null to the literal string
                // "null" if read carelessly (the v0.1.6 chat bubble
                // that said only "null" while the server generated
                // 300+ tokens). Read both fields, as real strings
                // only — and say WHICH field each piece came from, so
                // the UI can file thinking and reply into separate
                // cards (v0.2.8). A delta can carry BOTH fields at
                // once (the boundary chunk, on some servers): emit
                // both pieces, content first — the earlier
                // first-non-null read silently dropped the second.
                val pieces = runCatching {
                    val delta = JSONObject(payload)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("delta")
                    listOf(
                        "content" to TokenKind.CONTENT,
                        "reasoning_content" to TokenKind.REASONING,
                    ).mapNotNull { (key, kind) ->
                        if (delta.isNull(key)) null
                        else delta.optString(key).takeIf { it.isNotEmpty() }?.let { kind to it }
                    }
                }.getOrNull().orEmpty()
                for (piece in pieces) {
                    full.append(piece.second)
                    val captured = piece
                    mainHandler.post { onToken(captured.first, captured.second) }
                }
            }
        }
        return full.toString()
    }
}
