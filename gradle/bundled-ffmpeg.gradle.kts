// Bundled FFmpeg (specs/plan-per-platform.md, step 4), applied by desktop/mac and desktop/windows.
// Builds the pinned FFmpeg release from its official source, LGPL only (no GPL or non-free parts,
// so no libx264), with every demuxer and decoder but only the encoders, muxers and filters the
// server uses, and lays out ffmpeg, ffprobe and their shared libraries in
// build/ffmpeg-resources/<os>/ffmpeg (the app's resources). Shared libraries keep the LGPL's
// relinking terms simple; LICENSE.txt and SOURCE.txt go beside them.
//   macOS: clang from the Command Line Tools; VideoToolbox encoders.
//   Windows: MSYS2's MinGW-w64 toolchain (on GitHub's Windows machines at C:\msys64, or MSYS2_ROOT);
//            Media Foundation, NVENC and AMF encoders, built against their open headers only.
import java.io.File
import java.net.URI
import java.security.MessageDigest

val ffmpegVersion = "9.1"
val ffmpegSourceUrl = "https://ffmpeg.org/releases/ffmpeg-$ffmpegVersion.tar.xz"
// Checked against FFmpeg's release signing key (FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8).
val ffmpegSourceSha = "1a4d394ae63d190a526cd052eb4415c156b5c5e2df37efab41a7de0b8fde2433"

val osName = System.getProperty("os.name")
val isMac = osName.startsWith("Mac")
val isWindows = osName.startsWith("Windows")
// Compose's per-OS resource folder names. Only the module for this computer's OS bundles FFmpeg
// (desktop/mac on macOS, desktop/windows on Windows); each installer builds on its own OS.
val resourceOs = when {
    project.name == "mac" && isMac && System.getProperty("os.arch") == "aarch64" -> "macos-arm64"
    project.name == "mac" && isMac -> "macos-x64"
    project.name == "windows" && isWindows -> "windows"
    else -> null
}

val commonFlags = listOf(
    "--enable-shared", "--disable-static", "--disable-autodetect",
    "--disable-doc", "--disable-ffplay", "--disable-network", "--disable-avdevice", "--disable-indevs", "--disable-outdevs",
    "--disable-protocols", "--enable-protocol=file,pipe,fd",
    "--disable-muxers", "--enable-muxer=hls,mp4,webvtt,null",
    "--enable-zlib",
)
val commonEncoders = "aac,webvtt"
val commonFilters = "scale,format,overlay,aresample,aformat,anull,null,setpts,fps"
val configureFlags: List<String> = when {
    isMac -> commonFlags + listOf(
        "--install-name-dir=@rpath", "--extra-ldexeflags=-Wl,-rpath,@executable_path",
        "--enable-pthreads", "--enable-videotoolbox", "--enable-audiotoolbox", "--enable-bzlib",
        "--disable-encoders", "--enable-encoder=$commonEncoders,h264_videotoolbox,hevc_videotoolbox",
        "--disable-filters", "--enable-filter=$commonFilters,scale_vt",
    )
    isWindows -> commonFlags + listOf(
        "--enable-w32threads", "--enable-mediafoundation", "--enable-ffnvcodec", "--enable-nvenc", "--enable-amf",
        "--enable-d3d11va", "--enable-dxva2",
        "--disable-encoders", "--enable-encoder=$commonEncoders,h264_mf,hevc_mf,h264_nvenc,hevc_nvenc,h264_amf,hevc_amf",
        "--disable-filters", "--enable-filter=$commonFilters",
    )
    else -> emptyList()
}

if (resourceOs != null) {
    val downloads = rootProject.layout.buildDirectory.dir("ffmpeg-download").get().asFile
    val work = layout.buildDirectory.dir("ffmpeg-build").get().asFile
    val resources = layout.buildDirectory.dir("ffmpeg-resources").get().asFile
    val target = File(resources, "$resourceOs/ffmpeg")
    val msysRoot = System.getenv("MSYS2_ROOT") ?: "C:\\msys64"
    val version = ffmpegVersion
    val url = ffmpegSourceUrl
    val sha = ffmpegSourceSha
    val flags = configureFlags
    val mac = isMac

    val bundleFfmpeg = tasks.register("bundleFfmpeg") {
        description = "Builds FFmpeg $version (LGPL) from its source and lays it out in the app's resources."
        inputs.property("version", version)
        inputs.property("flags", flags.joinToString(" "))
        outputs.dir(resources)
        doLast {
            // A Windows path as MSYS2's shell writes it: C:\a\b -> /c/a/b.
            fun msys(path: File) = "/" + path.absolutePath.replace('\\', '/').replace(":", "").let { it[0].lowercase() + it.substring(1) }
            fun run(command: List<String>, dir: File, env: Map<String, String> = emptyMap()) {
                val p = ProcessBuilder(command).directory(dir).redirectErrorStream(true).apply { environment().putAll(env) }.start()
                val log = File(work, "build.log")
                log.appendBytes(p.inputStream.readAllBytes())
                check(p.waitFor() == 0) { "FFmpeg build step failed: ${command.take(3)} (see $log)" }
            }
            downloads.mkdirs()
            val tarball = File(downloads, "ffmpeg-$version.tar.xz")
            fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            if (!tarball.isFile || sha256(tarball) != sha) {
                URI(url).toURL().openStream().use { input -> tarball.outputStream().use { input.copyTo(it) } }
            }
            check(sha256(tarball) == sha) { "ffmpeg-$version.tar.xz does not match its pinned SHA-256" }
            work.deleteRecursively()
            work.mkdirs()
            val out = File(work, "out")
            val jobs = Runtime.getRuntime().availableProcessors().toString()
            // One script for both systems; on Windows it runs in MSYS2's MinGW-w64 shell.
            val script = File(work, "build.sh")
            script.writeText(
                buildString {
                    appendLine("set -e")
                    if (!mac) {
                        appendLine("pacman -Sy --needed --noconfirm --quiet make diffutils pkgconf nasm mingw-w64-x86_64-gcc mingw-w64-x86_64-zlib mingw-w64-x86_64-ffnvcodec-headers mingw-w64-x86_64-amf-headers")
                    }
                    appendLine("tar xf \"\$TARBALL\"")
                    appendLine("cd ffmpeg-$version")
                    appendLine("./configure --prefix=\"\$OUT\" " + flags.joinToString(" ") { "'$it'" })
                    appendLine("make -j$jobs")
                    appendLine("make install")
                },
            )
            if (mac) {
                run(listOf("/bin/sh", script.path), work, mapOf("TARBALL" to tarball.path, "OUT" to out.path))
            } else {
                run(
                    listOf("$msysRoot\\usr\\bin\\bash.exe", "-lc", "bash '${msys(script)}'"), work,
                    mapOf("MSYSTEM" to "MINGW64", "CHERE_INVOKING" to "1", "TARBALL" to msys(tarball), "OUT" to msys(out)),
                )
            }
            // The app's copy: the two programs and the libraries they load, side by side.
            target.deleteRecursively()
            target.mkdirs()
            val exe = if (mac) "" else ".exe"
            for (name in listOf("ffmpeg", "ffprobe")) {
                File(out, "bin/$name$exe").copyTo(File(target, "$name$exe")).setExecutable(true, false)
            }
            if (mac) {
                // The real files under the names the programs load (libavcodec.63.dylib), not the symlinks.
                File(out, "lib").listFiles()!!.filter { Regex("""lib[a-z]+\.\d+\.dylib""").matches(it.name) }
                    .forEach { it.canonicalFile.copyTo(File(target, it.name)) }
            } else {
                File(out, "bin").listFiles()!!.filter { it.name.endsWith(".dll") }.forEach { it.copyTo(File(target, it.name)) }
                // MinGW's runtime libraries the programs need (libwinpthread, zlib), found with ldd.
                val ldd = ProcessBuilder("$msysRoot\\usr\\bin\\bash.exe", "-lc", "ldd '${msys(File(target, "ffmpeg.exe"))}' '${msys(File(target, "ffprobe.exe"))}' ${File(target, "").listFiles()!!.filter { it.name.endsWith(".dll") }.joinToString(" ") { "'" + msys(it) + "'" }}")
                    .apply { environment().putAll(mapOf("MSYSTEM" to "MINGW64")) }.redirectErrorStream(true).start()
                val listed = ldd.inputStream.readAllBytes().decodeToString()
                ldd.waitFor()
                Regex("""=> (/mingw64/bin/[^ ]+\.dll)""").findAll(listed).map { it.groupValues[1] }.toSet().forEach { dll ->
                    File("$msysRoot\\mingw64\\bin\\" + dll.substringAfterLast('/')).copyTo(File(target, dll.substringAfterLast('/')), overwrite = true)
                }
            }
            File(work, "ffmpeg-$version/COPYING.LGPLv2.1").copyTo(File(target, "LICENSE.txt"))
            File(target, "SOURCE.txt").writeText(
                """
                |FFmpeg $version, built by KS StreamFlix from FFmpeg's own source under the LGPL 2.1 or later
                |(LICENSE.txt). It contains no GPL or non-free parts.
                |Source: $url
                |SHA-256: $sha
                |Configured with: ${flags.joinToString(" ")}
                |FFmpeg is a trademark of Fabrice Bellard, originator of the FFmpeg project.
                |""".trimMargin(),
            )
        }
    }
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundleFfmpeg) }
    // jpackage drops the execute bit of resources: give it back to ffmpeg and ffprobe in the finished
    // app, before the installer is made from it (the dmg is built from that app image).
    if (mac) {
        for (type in listOf("", "Release")) {
            val appDir = layout.buildDirectory.dir(if (type == "") "compose/binaries/main/app" else "compose/binaries/main-release/app").get().asFile
            val fix = tasks.register("ffmpegExecutable$type") {
                description = "Makes the bundled ffmpeg and ffprobe executable in the ${type.ifEmpty { "debug" }} app."
                dependsOn("create${type}Distributable")
                doLast {
                    appDir.walkTopDown().filter { it.isFile && it.parentFile.name == "ffmpeg" && it.name in setOf("ffmpeg", "ffprobe") }
                        .forEach { check(it.setExecutable(true, false)) { "cannot make $it executable" } }
                }
            }
            tasks.matching { it.name.startsWith("package$type") && it.name != "package${type}UberJarForCurrentOS" && (type != "" || !it.name.startsWith("packageRelease")) }
                .configureEach { dependsOn(fix) }
        }
    }
    // The module's tests check the bundled copy (BundledFfmpegChecks in shared/ffmpeg's test fixtures).
    tasks.named<Test>("test") {
        dependsOn(bundleFfmpeg)
        systemProperty("streamflix.bundledFfmpeg", target.path)
    }
}
