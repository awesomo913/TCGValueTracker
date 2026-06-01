package com.owner.assist.pipeline

/**
 * Streaming sentence accumulator. Feed LLM deltas; emits complete sentences
 * as soon as a terminator (`. ! ?`) is seen (followed by whitespace or end).
 *
 * Critical for the latency budget — first sentence flushed to TTS the moment
 * the LLM streams it, instead of waiting for the entire reply.
 */
class SentenceSplitter {

    private val buf = StringBuilder()

    /**
     * Append a delta. Returns any complete sentences that became available.
     * If the delta extends an in-progress sentence without finishing it, returns empty.
     */
    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buf.append(delta)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            val isTerminal = c == '.' || c == '!' || c == '?' || c == '\n'
            if (isTerminal) {
                // include the terminator and any trailing space
                var end = i + 1
                while (end < buf.length && buf[end].isWhitespace()) end++
                val sentence = buf.substring(start, end).trim()
                if (sentence.isNotEmpty()) out += sentence
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start > 0) {
            buf.delete(0, start)
        }
        return out
    }

    /** Flush whatever remains. Called when LLM stream ends. */
    fun drain(): String {
        val s = buf.toString().trim()
        buf.clear()
        return s
    }
}
