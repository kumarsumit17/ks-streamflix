package com.ks.streamflix.mac

import com.ks.streamflix.ffmpeg.BundledFfmpegChecks
import java.io.File
import kotlin.test.Test

class BundledFfmpegTest {
    /** The FFmpeg this app bundles (built by bundleFfmpeg on this OS; elsewhere there is none to check). */
    @Test fun theBundledFfmpegDoesWhatTheServerNeeds() {
        val dir = System.getProperty("streamflix.bundledFfmpeg")?.let(::File) ?: return
        BundledFfmpegChecks.run(dir, windows = false)
    }
}
