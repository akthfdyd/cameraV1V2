package com.example.evey.camerav1v2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import com.example.evey.camerav1v2.DevLog.i
import com.example.evey.camerav1v2.DevLog.printStackTrace
import com.example.evey.camerav1v2.DevLog.v
import com.example.evey.camerav1v2.DevLog.w
import com.gun0912.tedpermission.TedPermissionResult
import com.tedpark.tedpermission.rx2.TedRx2Permission
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

class CameraModule {
    enum class CameraAPI {
        V1, V2
    }

    enum class MediaRecorderState {
        NULL, CREATED, PREPARED, START, STOP, RELEASE
    }

    enum class CameraState {
        NULL, CREATED, START_PREVIEW, UNLOCK, SET_MEDIA_RECORDER, LOCK, RELEASE
    }

    var cameraAPI = CameraAPI.V2
    private var mediaRecorderState = MediaRecorderState.NULL
    private var cameraState = CameraState.NULL

    var isSurfaceCreated = false
    private var isPermissionChecked = false
    var isActivityForeground = false
    var isRecording = false

    private var mediaRecorder: MediaRecorder? = null

    // ------------------------------------ SurfaceTextureListener ------------------------------------

    private lateinit var textureView: TextureView

    fun setTextureView(view: TextureView) {
        textureView = view
    }

    private lateinit var activity: Activity

    fun setActivity(activity: Activity) {
        this.activity = activity
    }

    val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            i("")
            isSurfaceCreated = true
            when (cameraAPI) {
                CameraAPI.V1 -> checkForPrepareMediaRecorderV1()
                CameraAPI.V2 -> checkForPrepareMediaRecorderV2(width, height)
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            i("")
            when (cameraAPI) {
                CameraAPI.V2 -> configureTransformV2(width, height)
            }
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    }

    // ------------------------------------ lifecycle ------------------------------------

    fun cameraOnResume() {
        i("")
        when (cameraAPI) {
            CameraAPI.V1 -> checkForPrepareMediaRecorderV1()
            CameraAPI.V2 -> {
                startBackgroundThread()
                if (textureView.isAvailable) {
                    checkForPrepareMediaRecorderV2(textureView.width, textureView.height)
                } else {
                    textureView.surfaceTextureListener = surfaceTextureListener
                }
            }
        }
    }

    fun cameraOnPause() {
        i("")
        when (cameraAPI) {
            CameraAPI.V1 -> {
                releaseMediaRecorder()
                releaseCameraV1()
            }
            CameraAPI.V2 -> {
                closeCameraV2()
                stopBackgroundThread()
            }
        }
    }

    // ------------------------------------ permission ------------------------------------

    fun requestPermissions() {
        i("")
        TedRx2Permission.with(activity)
            .setDeniedMessage(
                "권한 비허용시 기능 사용에 제약이 있습니다. 추후 사용을 원하시면 폰 애플리케이션 설정에서 사람인 앱을 찾아 권한을 허용해 주세요"
            )
            .setPermissions(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            .request()
            .subscribe({ tedPermissionResult: TedPermissionResult ->
                if (tedPermissionResult.isGranted) {
                    w("카메라,녹음,저장소 권한 허용")
                    isPermissionChecked = true
                    when (cameraAPI) {
                        CameraAPI.V1 -> checkForPrepareMediaRecorderV1()
                        CameraAPI.V2 -> checkForPrepareMediaRecorderV2(
                            textureView.width,
                            textureView.height
                        )
                    }
                } else {
                    w("카메라,녹음,저장소 권한 비허용")
                }
            }) { throwable: Throwable ->
                printStackTrace(throwable)
                w("카메라,녹음,저장소 권한 획득 실패")
            }
    }


    // ------------------------------------ for v1v2 ------------------------------------

    fun startMediaRecorder() {
        i("")
        if (mediaRecorder == null) {
            when (cameraAPI) {
                CameraAPI.V1 -> {
                    prepareMediaRecorderV1().subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            if (it) {
                                w("초기화 성공")
                                mediaRecorder?.start()
                                mediaRecorderState = MediaRecorderState.START
                            } else {
                                w("초기화 실패")
                            }
                        }, {
                            printStackTrace(it)
                            w("초기화 망함")
                        })
                }
                CameraAPI.V2 -> {
                    mediaRecorder?.start()
                    mediaRecorderState = MediaRecorderState.START
                }
            }
        } else {
            mediaRecorder?.start()
            mediaRecorderState = MediaRecorderState.START
        }
    }

    fun stopMediaRecorder() {
        i("")
        try {
            mediaRecorder?.stop()
            mediaRecorderState = MediaRecorderState.STOP
        } catch (e: RuntimeException) {
            i("RuntimeException: stop() is called immediately after start() ${e.message}")
            printStackTrace(e)
            outputFile?.delete()
        }
    }

    fun releaseMediaRecorder() {
        i("")
        if (mediaRecorder != null) {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        }
        mediaRecorderState = MediaRecorderState.RELEASE
    }

    private fun getVideoFilePath(context: Context?): String {
        i("")
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = context?.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }


    // ------------------------------------ v1 ------------------------------------
    private var cameraV1Device: Camera? = null
    var outputFile: File? = null

    private fun checkForPrepareMediaRecorderV1() {
        i("")
        if (isSurfaceCreated && isPermissionChecked && isActivityForeground) {
            prepareMediaRecorderV1().subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    if (it) {
                        w("초기화 성공")
                    } else {
                        w("초기화 실패")
                    }
                }, {
                    printStackTrace(it)
                    w("초기화 망함")
                })
        }
    }


    private fun prepareMediaRecorderV1(): Single<Boolean> {
        i("")

        return Single.fromCallable {
            var result = true
            cameraV1Device = CameraHelper.defaultFrontFacingCameraInstance
            if (cameraV1Device == null) cameraV1Device = CameraHelper.defaultCameraInstance
            if (cameraV1Device == null) result = false

            cameraV1Device?.let { cameraV1Device ->
                cameraState = CameraState.CREATED
                val orientation = activity.resources.configuration.orientation

                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    cameraV1Device.setDisplayOrientation(0)
                } else {
                    cameraV1Device.setDisplayOrientation(90)
                }

                val parameters = cameraV1Device.parameters
                val mSupportedPreviewSizes = parameters.supportedPreviewSizes
                val mSupportedVideoSizes = parameters.supportedVideoSizes
                val optimalSize: Camera.Size?

                optimalSize = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    CameraHelper.getOptimalVideoSize(
                        mSupportedVideoSizes,
                        mSupportedPreviewSizes, textureView.width, textureView.height
                    )
                } else {
                    CameraHelper.getOptimalVideoSize(
                        mSupportedVideoSizes,
                        mSupportedPreviewSizes, textureView.height, textureView.width
                    )
                }

                val profile = CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
                profile.videoFrameWidth = optimalSize?.width ?: 1280
                profile.videoFrameHeight = optimalSize?.height ?: 720

                parameters.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight)
                cameraV1Device.parameters = parameters

                try {
                    cameraV1Device.setPreviewTexture(textureView.surfaceTexture)
                    cameraV1Device.startPreview()
                    cameraState = CameraState.START_PREVIEW
                } catch (e: IOException) {
                    i("Surface texture is unavailable or unsuitable" + e.message)
                    printStackTrace(e)
                    result = false
                }

                mediaRecorder = MediaRecorder()
                mediaRecorderState = MediaRecorderState.CREATED

                cameraV1Device.unlock()
                cameraState = CameraState.UNLOCK

                mediaRecorder?.setCamera(cameraV1Device)
                cameraState = CameraState.SET_MEDIA_RECORDER

                mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.CAMERA)

                mediaRecorder?.setProfile(profile)

                outputFile = File(getVideoFilePath(activity))
                if (outputFile == null) {
                    result = false
                }

                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mediaRecorder?.setOrientationHint(0)
                } else {
                    mediaRecorder?.setOrientationHint(270)
                }
                mediaRecorder?.setOutputFile(outputFile?.path)

                try {
                    mediaRecorder?.prepare()
                    mediaRecorderState = MediaRecorderState.PREPARED
                } catch (e: IllegalStateException) {
                    i("IllegalStateException preparing MediaRecorder: " + e.message)
                    printStackTrace(e)
                    releaseMediaRecorder()
                    result = false
                } catch (e: IOException) {
                    i("IOException preparing MediaRecorder: " + e.message)
                    printStackTrace(e)
                    releaseMediaRecorder()
                    result = false
                }
            }
            result
        }
    }

    private fun releaseCameraV1() {
        i("")
        cameraV1Device?.lock()
        cameraState = CameraState.LOCK
        if (cameraV1Device != null) {
            cameraV1Device?.release()
            cameraState = CameraState.RELEASE
            cameraV1Device = null
            cameraState = CameraState.NULL
        }
    }


    // ------------------------------------ v2 ------------------------------------

    private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
    private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }
    private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }

    var nextVideoAbsolutePath: String? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private var sensorOrientation = 0

    private var cameraV2Device: CameraDevice? = null
    private var cameraV2CaptureSession: CameraCaptureSession? = null
    private lateinit var cameraV2PreviewRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraV2PreviewSize: Size
    private lateinit var cameraV2VideoSize: Size

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            i("")
            v("stateCallback onOpened + $cameraDevice")
            cameraOpenCloseLock.release()
            this@CameraModule.cameraV2Device = cameraDevice
            cameraState = CameraState.CREATED
            startPreviewV2()
            configureTransformV2(textureView.width, textureView.height)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            i("")
            v("stateCallback onDisconnected + $cameraDevice")
            cameraOpenCloseLock.release()
            cameraDevice.close()
            cameraState = CameraState.RELEASE
            this@CameraModule.cameraV2Device = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            i("")
            v("stateCallback onError + $cameraDevice $error")
            cameraOpenCloseLock.release()
            cameraDevice.close()
            cameraState = CameraState.RELEASE
            this@CameraModule.cameraV2Device = null
            this@CameraModule.activity.finish()
        }

    }

    private fun checkForPrepareMediaRecorderV2(textureViewWidth: Int, textureViewHeight: Int) {
        i("")
        if (isSurfaceCreated && isPermissionChecked && isActivityForeground) {
            prepareMediaRecorderV2(textureViewWidth, textureViewHeight).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    if (it) {
                        w("초기화 성공")
                    } else {
                        w("초기화 실패")
                    }
                }, {
                    printStackTrace(it)
                    w("초기화 망함")
                })
        }
    }

    @SuppressLint("MissingPermission")
    private fun prepareMediaRecorderV2(
        textureViewWidth: Int,
        textureViewHeight: Int
    ): Single<Boolean> {
        i("")
        return Single.fromCallable {
            var result: Boolean

            val cameraActivity = this@CameraModule.activity
            if (cameraActivity == null || cameraActivity.isFinishing) result = false

            val manager = cameraActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    throw RuntimeException("Time out waiting to lock camera opening.")
                }

                val cameraId = getFrontFacingCameraIdV2(manager)
                cameraState = CameraState.CREATED

                val characteristics = manager.getCameraCharacteristics(cameraId)

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw RuntimeException("Cannot get available preview/video sizes")

                sensorOrientation =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                v("CameraRecordFragment sensorOrientation ${sensorOrientation}")

                cameraV2VideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
                v("CameraRecordFragment chooseVideoSize ${cameraV2VideoSize.width},${cameraV2VideoSize.height}")

                cameraV2PreviewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    cameraV2VideoSize
                )
                v("CameraRecordFragment chooseOptimalSize ${cameraV2PreviewSize.width},${cameraV2PreviewSize.height}")

// 카메라촬영은 가로방향이므로 가로세로 비율 반대로 표시
//                textureView.setAspectRatio(cameraV2PreviewSize.height, cameraV2PreviewSize.width)

                // 화면비와 무관한 스케일링이 되도록 프리뷰비디오 비율 유지
                configureTransformV2(textureViewWidth, textureViewHeight)

                mediaRecorder = MediaRecorder()
                mediaRecorderState = MediaRecorderState.CREATED

                this@CameraModule.activity.runOnUiThread {
                    manager.openCamera(cameraId, stateCallback, null)
                }
                result = true
            } catch (e: Exception) {
                printStackTrace(e)
                result = false
            }
            result

        }
    }

    private fun getFrontFacingCameraIdV2(cManager: CameraManager): String {
        i("")
        v("getFrontFacingCameraId + $cManager")
        for (cameraId in cManager.cameraIdList) {
            val characteristics: CameraCharacteristics = cManager.getCameraCharacteristics(cameraId)
            val cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cOrientation == CameraCharacteristics.LENS_FACING_FRONT) return cameraId
        }
        return if (cManager.cameraIdList.size > 1)
            cManager.cameraIdList[1]
        else
            cManager.cameraIdList[0]
    }

    private fun chooseVideoSize(
        choices: Array<Size>
    ): Size {
        i("")
        val bigEnough = choices.filter {
            it.height <= 720 && it.width <= 1280
        }
        for (item in bigEnough) {
            v("chooseVideoSize bigEnough ${item.width},${item.height}")
        }

        return if (bigEnough.isNotEmpty()) {
            Collections.max(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        aspectRatio: Size
    ): Size {
        i("")
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w
//                    && it.width >= width && it.height >= height
        }
        for (item in bigEnough) {
            v("chooseOptimalSize bigEnough ${item.width},${item.height}")
        }

        return if (bigEnough.isNotEmpty()) {
            Collections.max(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun configureTransformV2(viewWidth: Int, viewHeight: Int) {
        i("")
//        activity ?: return
        val rotation = this@CameraModule.activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0f, 0f, cameraV2PreviewSize.height.toFloat(), cameraV2PreviewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / cameraV2PreviewSize.height,
                viewWidth.toFloat() / cameraV2PreviewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        // 다양한 화면비 대응
        else {
            v("configureTransform rotation >> $rotation")
            v("configureTransform videoSize ${cameraV2VideoSize.width},${cameraV2VideoSize.height}")
            v("configureTransform previewSize >> ${cameraV2PreviewSize.width.toFloat()}, ${cameraV2PreviewSize.height.toFloat()}")
            v("configureTransform viewHeight.toFloat() >> ${viewHeight.toFloat()}")
            v("configureTransform viewWidth.toFloat() >> ${viewWidth.toFloat()}")
            v("configureTransform viewRect.centerX() >> ${viewRect.centerX()}")
            v("configureTransform viewRect.centerY() >> ${viewRect.centerY()}")

            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
            var scalex: Float
            var scaley: Float
            if (viewWidth.toFloat() / viewHeight.toFloat() > cameraV2VideoSize.height.toFloat() / cameraV2VideoSize.width.toFloat()) { // 넓은 화면
                v("configureTransform 넓은 화면")
                scalex = viewWidth.toFloat() / cameraV2PreviewSize.height.toFloat()
                v("configureTransform scalex >> ${scalex} = ${viewWidth.toFloat()} / ${cameraV2PreviewSize.height.toFloat()}")
                scaley =
                    scalex * ((cameraV2PreviewSize.width.toFloat() * viewWidth.toFloat()) / (cameraV2PreviewSize.height.toFloat() * viewHeight.toFloat()))
            } else { // 긴 화면
                v("configureTransform 긴 화면")
                scaley = viewHeight.toFloat() / cameraV2PreviewSize.width.toFloat()
                scalex = viewHeight.toFloat() / cameraV2PreviewSize.width.toFloat()
                scalex =
                    scaley * ((cameraV2PreviewSize.height.toFloat() * viewHeight.toFloat()) / (cameraV2PreviewSize.width.toFloat() * viewWidth.toFloat()))
            }
            with(matrix) {
                postScale(scalex, scaley, centerX, centerY)
            }
            v("configureTransform scalex >> ${scalex}")
            v("configureTransform scaley >> ${scaley}")
            v("configureTransform scaled x, y >> ${scalex * cameraV2PreviewSize.height.toFloat()}, ${scaley * cameraV2PreviewSize.width.toFloat()}")
        }
        //
        activity.runOnUiThread { textureView.setTransform(matrix) }
    }

    private fun startPreviewV2() {
        i("")
        if (cameraV2Device == null || !textureView.isAvailable) return

        try {
            closePreviewSessionV2()
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(cameraV2PreviewSize.width, cameraV2PreviewSize.height)
            cameraV2PreviewRequestBuilder =
                cameraV2Device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            cameraV2PreviewRequestBuilder.addTarget(previewSurface)
            cameraState = CameraState.SET_MEDIA_RECORDER

            cameraV2Device?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        i("")
                        cameraV2CaptureSession = session
                        updatePreviewV2()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        i("")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            printStackTrace(e)
        }

    }

    private fun closePreviewSessionV2() {
        i("")
        cameraV2CaptureSession?.close()
        cameraV2CaptureSession = null
    }

    private fun updatePreviewV2() {
        i("")
        if (cameraV2Device == null) return

        try {
            setUpCaptureRequestBuilder(cameraV2PreviewRequestBuilder)
            HandlerThread("CameraPreview").start()
            cameraV2CaptureSession?.setRepeatingRequest(
                cameraV2PreviewRequestBuilder.build(),
                null, backgroundHandler
            )
            cameraState = CameraState.START_PREVIEW

        } catch (e: CameraAccessException) {
            printStackTrace(e)
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        i("")
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private fun startBackgroundThread() {
        i("")
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun stopBackgroundThread() {
        i("")
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            printStackTrace(e)
        }
    }

    private fun closeCameraV2() {
        i("")
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSessionV2()
            cameraV2Device?.close()
            cameraState = CameraState.RELEASE
            cameraV2Device = null
            cameraState = CameraState.NULL
            releaseMediaRecorder()
        } catch (e: InterruptedException) {
            printStackTrace(e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun setUpMediaRecorder() {
        i("")
        nextVideoAbsolutePath = getVideoFilePath(activity)

        val rotation = activity.windowManager.defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        mediaRecorder?.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            } catch (e: Exception) {
                printStackTrace(e)
            }
            try {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
            } catch (e: Exception) {
                printStackTrace(e)
            }

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)
            setVideoEncodingBitRate(5000000)
            setVideoFrameRate(30)
            setVideoSize(cameraV2VideoSize.width, cameraV2VideoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            prepare()
        }
    }

    fun startRecordingVideoV2() {
        i("")
        if (cameraV2Device == null || !textureView.isAvailable) return

        try {
            closePreviewSessionV2()
            setUpMediaRecorder()

            val texture = textureView.surfaceTexture.apply {
                setDefaultBufferSize(cameraV2PreviewSize.width, cameraV2PreviewSize.height)
            }

            // Set up Surface for camera preview and MediaRecorder
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }

            cameraV2PreviewRequestBuilder =
                cameraV2Device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(recorderSurface)
                }

            cameraV2Device?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        i("")
                        cameraV2CaptureSession = cameraCaptureSession
                        updatePreviewV2()
                        this@CameraModule.activity.runOnUiThread {
                            isRecording = true
                            startMediaRecorder()
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        i("")
                        w("녹화 설정에 실패했습니다.")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            printStackTrace(e)
        } catch (e: IOException) {
            printStackTrace(e)
        } catch (e: Exception) {
            printStackTrace(e)
        }
    }

    fun stopRecordingVideoV2() {
        i("")
        mediaRecorder?.apply {
            try {
                cameraV2CaptureSession?.stopRepeating()
                cameraV2CaptureSession?.abortCaptures()
                stop()
                reset()
            } catch (e: Exception) {
                printStackTrace(e)
            }
        }

        startPreviewV2()
    }
}