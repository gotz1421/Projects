package com.voicerewriter

/**
 * Splits a long dictation into pieces of about [TARGET_SEC] seconds, cutting in the quietest
 * moment near each boundary so no word is sliced in half.
 *
 * Whisper works on 30-second windows; one long upload makes the service stitch windows
 * together itself, and that's where long takes went wrong (text stopping half-way, prompt
 * echoes). Sending pieces and joining the text is what makes long dictations reliable.
 */
object AudioChunker {
    const val TARGET_SEC = 25
    private const val MIN_SEC = 15
    private const val MAX_SEC = 29
    private const val WINDOW_MS = 100

    /** Index ranges into [samples]; a single range when the take is short enough. */
    fun split(samples: ShortArray, rate: Int = AudioRecorder.SAMPLE_RATE): List<IntRange> {
        val n = samples.size
        if (n <= MAX_SEC * rate) return listOf(0 until n)
        val win = rate * WINDOW_MS / 1000
        val out = ArrayList<IntRange>()
        var start = 0
        while (n - start > MAX_SEC * rate) {
            val from = start + MIN_SEC * rate
            val to = minOf(start + MAX_SEC * rate, n - win)
            var bestAt = start + TARGET_SEC * rate
            var bestEnergy = Long.MAX_VALUE
            var i = from
            while (i + win <= to) {
                var e = 0L
                var j = i
                while (j < i + win) { val v = samples[j].toInt(); e += v.toLong() * v; j += 4 }
                if (e < bestEnergy) { bestEnergy = e; bestAt = i + win / 2 }
                i += win / 2
            }
            out.add(start until bestAt)
            start = bestAt
        }
        out.add(start until n)
        return out
    }
}
