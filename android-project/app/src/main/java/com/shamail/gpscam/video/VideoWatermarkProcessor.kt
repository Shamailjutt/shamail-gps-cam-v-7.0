package com.shamail.gpscam.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.watermark.WatermarkRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

object VideoWatermarkProcessor {

    private const val TAG = "VideoWatermarkProc"
    private const val TIMEOUT_US = 10_000L

    data class Result(
        val success: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val durationMillis: Long = 0L,
        val errorMessage: String? = null
    )

    fun processVideo(
        context: Context,
        sourceFile: File? = null,
        sourceUri: Uri? = null,
        outputFile: File,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        baseTimestampMillis: Long,
        replaceExistingWatermark: Boolean = false,
        oldGpsForRemoval: GpsSnapshot? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Result {
        if (sourceFile == null && sourceUri == null) {
            return Result(false, errorMessage = "No input video source provided")
        }

        val probe = probeVideoProperties(context, sourceFile, sourceUri)
        if (probe.rawWidth <= 0 || probe.rawHeight <= 0) {
            return Result(false, errorMessage = "Unable to inspect source video")
        }

        val uprightW = if (probe.rotation == 90 || probe.rotation == 270) probe.rawHeight else probe.rawWidth
        val uprightH = if (probe.rotation == 90 || probe.rotation == 270) probe.rawWidth else probe.rawHeight

        // Attempt primary resolution first (UHD or FHD), falling back to 1080p / 720p if hardware encoder refuses
        val candidates = buildDimensionCandidates(uprightW, uprightH)
        var lastError: String? = null

        for ((targetW, targetH) in candidates) {
            if (outputFile.exists()) outputFile.delete()
            try {
                runTranscodePass(
                    context = context,
                    sourceFile = sourceFile,
                    sourceUri = sourceUri,
                    outputFile = outputFile,
                    outWidth = targetW,
                    outHeight = targetH,
                    rotation = probe.rotation,
                    frameRate = probe.frameRate,
                    durationUs = probe.durationUs,
                    config = config,
                    gps = gps,
                    logoBitmap = logoBitmap,
                    baseTimestampMillis = baseTimestampMillis,
                    replaceExistingWatermark = replaceExistingWatermark,
                    oldGpsForRemoval = oldGpsForRemoval,
                    onProgress = onProgress
                )
                if (outputFile.exists() && outputFile.length() > 1024L) {
                    val lat = if (gps.hasFix) gps.latitude else null
                    val lon = if (gps.hasFix) gps.longitude else null
                    Mp4MetadataUpdater.writeMetadata(
                        mp4File = outputFile,
                        creationMillis = baseTimestampMillis,
                        latitude = lat,
                        longitude = lon
                    )
                    return Result(
                        success = true,
                        width = targetW,
                        height = targetH,
                        durationMillis = max(0L, probe.durationUs / 1000L)
                    )
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: e.javaClass.simpleName
                Log.w(TAG, "Transcode attempt ${targetW}x$targetH failed: $lastError")
            }
        }
        return Result(false, errorMessage = lastError ?: "Video processing failed")
    }

    private data class VideoProbe(
        val rawWidth: Int,
        val rawHeight: Int,
        val rotation: Int,
        val frameRate: Int,
        val durationUs: Long
    )

    private fun probeVideoProperties(context: Context, file: File?, uri: Uri?): VideoProbe {
        var w = 0
        var h = 0
        var rot = 0
        var fps = 30
        var durUs = 0L

        val retriever = MediaMetadataRetriever()
        try {
            if (file != null && file.exists()) {
                retriever.setDataSource(file.absolutePath)
            } else if (uri != null) {
                retriever.setDataSource(context, uri)
            }
            w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durUs = durMs * 1000L
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            if (file != null && file.exists()) {
                extractor.setDataSource(file.absolutePath)
            } else if (uri != null) {
                extractor.setDataSource(context, uri, null)
            }
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    if (w <= 0 && fmt.containsKey(MediaFormat.KEY_WIDTH)) w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    if (h <= 0 && fmt.containsKey(MediaFormat.KEY_HEIGHT)) h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    if (rot == 0 && fmt.containsKey(MediaFormat.KEY_ROTATION)) rot = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(15, 60)
                    }
                    if (durUs <= 0L && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durUs = fmt.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }
        return VideoProbe(w, h, ((rot % 360) + 360) % 360, fps, durUs)
    }

    private fun buildDimensionCandidates(uprightW: Int, uprightH: Int): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        val evenW = (uprightW and 1.inv()).coerceAtLeast(16)
        val evenH = (uprightH and 1.inv()).coerceAtLeast(16)
        list.add(evenW to evenH)

        val longEdge = max(evenW, evenH)
        if (longEdge > 1920) {
            val scale = 1920f / longEdge.toFloat()
            val w1080 = ((evenW * scale).toInt() and 1.inv()).coerceAtLeast(16)
            val h1080 = ((evenH * scale).toInt() and 1.inv()).coerceAtLeast(16)
            if (w1080 != evenW || h1080 != evenH) list.add(w1080 to h1080)
        }
        if (longEdge > 1280) {
            val scale = 1280f / longEdge.toFloat()
            val w720 = ((evenW * scale).toInt() and 1.inv()).coerceAtLeast(16)
            val h720 = ((evenH * scale).toInt() and 1.inv()).coerceAtLeast(16)
            if (!list.contains(w720 to h720)) list.add(w720 to h720)
        }
        return list
    }

    private fun pickBitrate(w: Int, h: Int): Int {
        val shortEdge = min(w, h)
        return when {
            shortEdge >= 2160 -> 24_000_000
            shortEdge >= 1080 -> 12_000_000
            shortEdge >= 720 -> 6_000_000
            else -> 3_500_000
        }
    }

    private fun runTranscodePass(
        context: Context,
        sourceFile: File?,
        sourceUri: Uri?,
        outputFile: File,
        outWidth: Int,
        outHeight: Int,
        rotation: Int,
        frameRate: Int,
        durationUs: Long,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        baseTimestampMillis: Long,
        replaceExistingWatermark: Boolean,
        oldGpsForRemoval: GpsSnapshot?,
        onProgress: ((Float) -> Unit)?
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var glSession: GlVideoWatermarkSession? = null

        try {
            if (sourceFile != null && sourceFile.exists()) {
                videoExtractor.setDataSource(sourceFile.absolutePath)
                audioExtractor.setDataSource(sourceFile.absolutePath)
            } else if (sourceUri != null) {
                videoExtractor.setDataSource(context, sourceUri, null)
                audioExtractor.setDataSource(context, sourceUri, null)
            }

            var videoTrackIndex = -1
            var videoInputFormat: MediaFormat? = null
            var audioTrackIndex = -1
            var audioInputFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoInputFormat = fmt
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioInputFormat = fmt
                }
            }
            if (videoTrackIndex < 0 || videoInputFormat == null) {
                throw IllegalStateException("No video track found in file")
            }

            videoExtractor.selectTrack(videoTrackIndex)
            if (audioTrackIndex >= 0) {
                audioExtractor.selectTrack(audioTrackIndex)
            }

            val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, pickBitrate(outWidth, outHeight))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = enc.createInputSurface()
            enc.start()

            val gl = GlVideoWatermarkSession(
                encoderSurface = encoderInputSurface,
                outWidth = outWidth,
                outHeight = outHeight,
                videoRotationDeg = rotation,
                config = config,
                gps = gps,
                logoBitmap = logoBitmap,
                baseTimestampMillis = baseTimestampMillis,
                replaceExistingWatermark = replaceExistingWatermark,
                oldGpsForRemoval = oldGpsForRemoval
            )
            glSession = gl

            val decoderMime = videoInputFormat.getString(MediaFormat.KEY_MIME)
                ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val dec = MediaCodec.createDecoderByType(decoderMime)
            decoder = dec
            dec.configure(videoInputFormat, gl.decoderSurface, null, 0)
            dec.start()

            val mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            mediaMuxer.setOrientationHint(0)
            if (gps.hasFix) {
                runCatching {
                    mediaMuxer.setLocation(gps.latitude.toFloat(), gps.longitude.toFloat())
                }
            }

            var muxVideoTrack = -1
            var muxAudioTrack = -1

            val decBufInfo = MediaCodec.BufferInfo()
            val encBufInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var signaledEndOfInput = false
            var lastReportedPct = -1

            while (!encoderDone) {
                // 1. Feed compressed samples to decoder
                if (!extractorDone) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val pts = videoExtractor.sampleTime
                                val flags = videoExtractor.sampleFlags
                                dec.queueInputBuffer(inIdx, 0, sampleSize, pts, flags)
                                videoExtractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain decoded frames -> GL render with watermark -> encoder surface
                if (!decoderDone) {
                    val outIdx = dec.dequeueOutputBuffer(decBufInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val doRender = decBufInfo.size > 0
                        val eos = (decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        dec.releaseOutputBuffer(outIdx, doRender)

                        if (doRender) {
                            gl.awaitAndDrawFrame(decBufInfo.presentationTimeUs)
                            if (durationUs > 0L && onProgress != null) {
                                val pct = ((decBufInfo.presentationTimeUs * 92L) / durationUs).toInt().coerceIn(0, 92)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                        if (eos) {
                            decoderDone = true
                            if (!signaledEndOfInput) {
                                enc.signalEndOfInputStream()
                                signaledEndOfInput = true
                            }
                        }
                    }
                }

                // 3. Drain encoded H.264 packets -> MediaMuxer
                var drainingEncoder = true
                while (drainingEncoder) {
                    val encOutIdx = enc.dequeueOutputBuffer(encBufInfo, if (decoderDone) TIMEOUT_US else 0L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            drainingEncoder = false
                        }
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxVideoTrack = mediaMuxer.addTrack(enc.outputFormat)
                                if (audioTrackIndex >= 0 && audioInputFormat != null) {
                                    muxAudioTrack = runCatching { mediaMuxer.addTrack(audioInputFormat) }.getOrDefault(-1)
                                }
                                mediaMuxer.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            val encodedBuf = enc.getOutputBuffer(encOutIdx)
                            if (encodedBuf != null && (encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                encBufInfo.size > 0 && muxerStarted
                            ) {
                                encodedBuf.position(encBufInfo.offset)
                                encodedBuf.limit(encBufInfo.offset + encBufInfo.size)
                                mediaMuxer.writeSampleData(muxVideoTrack, encodedBuf, encBufInfo)
                            }
                            val encEos = (encBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            enc.releaseOutputBuffer(encOutIdx, false)
                            if (encEos) {
                                encoderDone = true
                                drainingEncoder = false
                            }
                        }
                    }
                }
            }

            // 4. Losslessly copy original audio track (if any) into muxed MP4
            if (muxerStarted && muxAudioTrack >= 0) {
                val maxAudioBuf = if (audioInputFormat?.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) == true) {
                    audioInputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
                } else {
                    256 * 1024
                }
                val audioBuf = ByteBuffer.allocateDirect(maxAudioBuf)
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    audioBuf.clear()
                    val size = audioExtractor.readSampleData(audioBuf, 0)
                    if (size < 0) break
                    val pts = audioExtractor.sampleTime
                    if (pts >= 0L) {
                        audioInfo.set(0, size, pts, audioExtractor.sampleFlags)
                        mediaMuxer.writeSampleData(muxAudioTrack, audioBuf, audioInfo)
                    }
                    audioExtractor.advance()
                }
            }
            onProgress?.invoke(1f)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { glSession?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private class GlVideoWatermarkSession(
        encoderSurface: Surface,
        private val outWidth: Int,
        private val outHeight: Int,
        videoRotationDeg: Int,
        private val config: WatermarkConfig,
        private val gps: GpsSnapshot,
        private val logoBitmap: Bitmap?,
        private val baseTimestampMillis: Long,
        private val replaceExistingWatermark: Boolean,
        private val oldGpsForRemoval: GpsSnapshot?
    ) : SurfaceTexture.OnFrameAvailableListener {

        private val eglDisplay: EGLDisplay
        private val eglContext: EGLContext
        private val eglSurface: EGLSurface
        private val program: Int
        private val oesTexId: Int
        private val wmTexId: Int
        private val surfaceTexture: SurfaceTexture
        val decoderSurface: Surface

        private val frameSyncLock = Object()
        private var frameAvailable = false

        private val vertexData: FloatBuffer
        private val stMatrix = FloatArray(16)
        private val rotMatrix = FloatArray(16)
        private val texMatrix = FloatArray(16)

        private var lastRenderedMinute: Long = Long.MIN_VALUE
        private var stripTopNorm: Float = 1f
        private var stripHeightNorm: Float = 0.01f
        private var oldCardNorm = RectF(0f, 1f, 0f, 1f)
        private var oldLogoNorm = RectF(0f, 1f, 0f, 1f)
        private var hasOldLogo = false

        private val aPosLoc: Int
        private val aScreenUvLoc: Int
        private val uTexMatrixLoc: Int
        private val uVideoTexLoc: Int
        private val uWmTexLoc: Int
        private val uShowWmLoc: Int
        private val uStripTopLoc: Int
        private val uStripHeightLoc: Int
        private val uPatchOldLoc: Int
        private val uOldCardLoc: Int
        private val uOldLogoLoc: Int
        private val uHasOldLogoLoc: Int
        private val uTexelStepLoc: Int

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("EGL initialize failed")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            val eglConfig = configs[0] ?: throw RuntimeException("No EGLConfig")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, surfaceAttribs, 0)

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }

            // Build vertex array: (posX, posY, screenU, screenV) where screenV: 0=top, 1=bottom
            val quad = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
            vertexData = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(quad)
                    position(0)
                }

            val rotWork = FloatArray(16)
            Matrix.setIdentityM(rotWork, 0)
            Matrix.translateM(rotWork, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rotWork, 0, videoRotationDeg.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rotWork, 0, -0.5f, -0.5f, 0f)
            System.arraycopy(rotWork, 0, rotMatrix, 0, 16)

            val textures = IntArray(2)
            GLES20.glGenTextures(2, textures, 0)
            oesTexId = textures[0]
            wmTexId = textures[1]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            program = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aScreenUvLoc = GLES20.glGetAttribLocation(program, "aScreenUV")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uVideoTexLoc = GLES20.glGetUniformLocation(program, "sVideoTexture")
            uWmTexLoc = GLES20.glGetUniformLocation(program, "sWatermarkTexture")
            uShowWmLoc = GLES20.glGetUniformLocation(program, "uShowWatermark")
            uStripTopLoc = GLES20.glGetUniformLocation(program, "uStripTop")
            uStripHeightLoc = GLES20.glGetUniformLocation(program, "uStripHeight")
            uPatchOldLoc = GLES20.glGetUniformLocation(program, "uPatchOld")
            uOldCardLoc = GLES20.glGetUniformLocation(program, "uOldCardRect")
            uOldLogoLoc = GLES20.glGetUniformLocation(program, "uOldLogoCircle")
            uHasOldLogoLoc = GLES20.glGetUniformLocation(program, "uHasOldLogo")
            uTexelStepLoc = GLES20.glGetUniformLocation(program, "uTexelStep")

            surfaceTexture = SurfaceTexture(oesTexId).apply {
                setOnFrameAvailableListener(this@GlVideoWatermarkSession, Handler(Looper.getMainLooper()))
            }
            decoderSurface = Surface(surfaceTexture)

            // Seed old watermark geometry if re-editing an already watermarked video
            if (replaceExistingWatermark) {
                val oldStrip = WatermarkRenderer.renderVideoOverlayStrip(
                    videoWidth = outWidth,
                    videoHeight = outHeight,
                    config = config,
                    gps = oldGpsForRemoval ?: gps,
                    logoBitmap = logoBitmap,
                    captureMillis = baseTimestampMillis
                )
                if (oldStrip != null) {
                    val padX = 5f / outWidth.toFloat()
                    val padY = 5f / outHeight.toFloat()
                    oldCardNorm = RectF(
                        (oldStrip.cardNormRect.left - padX).coerceIn(0f, 1f),
                        (oldStrip.cardNormRect.top - padY).coerceIn(0f, 1f),
                        (oldStrip.cardNormRect.right + padX).coerceIn(0f, 1f),
                        (oldStrip.cardNormRect.bottom + padY).coerceIn(0f, 1f)
                    )
                    oldStrip.logoNormRect?.let { lr ->
                        oldLogoNorm = RectF(
                            lr.centerX(),
                            lr.centerY(),
                            lr.width() * 0.5f + padX,
                            lr.height() * 0.5f + padY
                        )
                        hasOldLogo = true
                    }
                    oldStrip.stripBitmap.recycle()
                }
            }

            updateWatermarkTextureIfNeeded(baseTimestampMillis)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            synchronized(frameSyncLock) {
                frameAvailable = true
                frameSyncLock.notifyAll()
            }
        }

        fun awaitAndDrawFrame(presentationTimeUs: Long) {
            synchronized(frameSyncLock) {
                val deadline = System.currentTimeMillis() + 2500L
                while (!frameAvailable) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    frameSyncLock.wait(remaining)
                }
                frameAvailable = false
            }

            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rotMatrix, 0)

            val frameWallMillis = baseTimestampMillis + (presentationTimeUs / 1000L).coerceAtLeast(0L)
            updateWatermarkTextureIfNeeded(frameWallMillis)

            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            vertexData.position(0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)

            vertexData.position(2)
            GLES20.glEnableVertexAttribArray(aScreenUvLoc)
            GLES20.glVertexAttribPointer(aScreenUvLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glUniform1i(uVideoTexLoc, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexId)
            GLES20.glUniform1i(uWmTexLoc, 1)

            GLES20.glUniform1i(uShowWmLoc, if (config.showWatermark) 1 else 0)
            GLES20.glUniform1f(uStripTopLoc, stripTopNorm)
            GLES20.glUniform1f(uStripHeightLoc, stripHeightNorm)

            GLES20.glUniform1i(uPatchOldLoc, if (replaceExistingWatermark) 1 else 0)
            GLES20.glUniform4f(
                uOldCardLoc,
                oldCardNorm.left,
                oldCardNorm.top,
                oldCardNorm.right,
                oldCardNorm.bottom
            )
            GLES20.glUniform4f(
                uOldLogoLoc,
                oldLogoNorm.left,
                oldLogoNorm.top,
                oldLogoNorm.right,
                oldLogoNorm.bottom
            )
            GLES20.glUniform1i(uHasOldLogoLoc, if (hasOldLogo) 1 else 0)
            GLES20.glUniform2f(uTexelStepLoc, 6f / outWidth.toFloat(), 6f / outHeight.toFloat())

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000L)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun updateWatermarkTextureIfNeeded(targetMillis: Long) {
            if (!config.showWatermark) return
            val minuteBucket = targetMillis / 60_000L
            if (minuteBucket == lastRenderedMinute) return
            lastRenderedMinute = minuteBucket

            val strip = WatermarkRenderer.renderVideoOverlayStrip(
                videoWidth = outWidth,
                videoHeight = outHeight,
                config = config,
                gps = gps,
                logoBitmap = logoBitmap,
                captureMillis = targetMillis
            ) ?: return

            stripTopNorm = strip.stripTopFraction
            stripHeightNorm = strip.stripHeightFraction.coerceAtLeast(0.005f)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, strip.stripBitmap, 0)
            strip.stripBitmap.recycle()
        }

        fun release() {
            runCatching { decoderSurface.release() }
            runCatching { surfaceTexture.release() }
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    private fun createGlProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aScreenUV;
        varying vec2 vScreenUV;
        void main() {
            gl_Position = aPosition;
            vScreenUV = aScreenUV;
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vScreenUV;
        uniform mat4 uTexMatrix;
        uniform samplerExternalOES sVideoTexture;
        uniform sampler2D sWatermarkTexture;
        uniform int uShowWatermark;
        uniform float uStripTop;
        uniform float uStripHeight;
        uniform int uPatchOld;
        uniform vec4 uOldCardRect;
        uniform vec4 uOldLogoCircle;
        uniform int uHasOldLogo;
        uniform vec2 uTexelStep;

        vec3 sampleVideoAtScreenUV(vec2 uv) {
            vec2 clamped = clamp(uv, vec2(0.002), vec2(0.998));
            vec4 glUv = uTexMatrix * vec4(clamped.x, 1.0 - clamped.y, 0.0, 1.0);
            return texture2D(sVideoTexture, glUv.xy).rgb;
        }

        void main() {
            vec3 baseColor = sampleVideoAtScreenUV(vScreenUV);

            if (uPatchOld == 1) {
                bool inCard = vScreenUV.x >= uOldCardRect.x && vScreenUV.x <= uOldCardRect.z &&
                              vScreenUV.y >= uOldCardRect.y && vScreenUV.y <= uOldCardRect.w;
                bool inLogo = false;
                if (uHasOldLogo == 1) {
                    vec2 d = (vScreenUV - uOldLogoCircle.xy) / max(uOldLogoCircle.zw, vec2(0.001));
                    inLogo = dot(d, d) <= 1.0;
                }
                if (inCard || inLogo) {
                    bool underLogoX = (uHasOldLogo == 1) &&
                        vScreenUV.x >= (uOldLogoCircle.x - uOldLogoCircle.z) &&
                        vScreenUV.x <= (uOldLogoCircle.x + uOldLogoCircle.z);
                    float topRefY = underLogoX
                        ? (uOldLogoCircle.y - uOldLogoCircle.w)
                        : uOldCardRect.y;

                    vec3 topCol = sampleVideoAtScreenUV(vec2(vScreenUV.x, topRefY - uTexelStep.y));
                    vec3 botCol = sampleVideoAtScreenUV(vec2(vScreenUV.x, uOldCardRect.w + uTexelStep.y));
                    vec3 leftCol = sampleVideoAtScreenUV(vec2(uOldCardRect.x - uTexelStep.x, vScreenUV.y));
                    vec3 rightCol = sampleVideoAtScreenUV(vec2(uOldCardRect.z + uTexelStep.x, vScreenUV.y));

                    float spanX = max(uOldCardRect.z - uOldCardRect.x, 0.01);
                    float spanY = max(uOldCardRect.w - topRefY, 0.01);
                    float u = clamp((vScreenUV.x - uOldCardRect.x) / spanX, 0.0, 1.0);
                    float v = clamp((vScreenUV.y - topRefY) / spanY, 0.0, 1.0);

                    float wTop = 1.0 / ((v + 0.04) * (v + 0.04));
                    float wBot = 1.0 / ((1.04 - v) * (1.04 - v));
                    float wLeft = 1.0 / ((u + 0.04) * (u + 0.04));
                    float wRight = 1.0 / ((1.04 - u) * (1.04 - u));
                    float wSum = wTop + wBot + wLeft + wRight;
                    baseColor = (topCol * wTop + botCol * wBot + leftCol * wLeft + rightCol * wRight) / wSum;
                }
            }

            if (uShowWatermark == 1 && vScreenUV.y >= uStripTop) {
                float wmV = (vScreenUV.y - uStripTop) / max(uStripHeight, 0.005);
                if (wmV >= 0.0 && wmV <= 1.0) {
                    vec4 wm = texture2D(sWatermarkTexture, vec2(vScreenUV.x, wmV));
                    // Premultiplied alpha SRC_OVER blending matching Android Canvas
                    baseColor = baseColor * (1.0 - wm.a) + wm.rgb;
                }
            }

            gl_FragColor = vec4(baseColor, 1.0);
        }
    """
}
