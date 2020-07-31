package com.example.evey.camerav1v2

import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.evey.camerav1v2.DevLog.i
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    var isVideoPlaying = false

    lateinit var cameraModule: CameraModule

    // ------------------------------------ lifecycle ------------------------------------


    override fun onCreate(savedInstanceState: Bundle?) {
        i("")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraModule = CameraModule()
        cameraModule.setActivity(this)
        cameraModule.setTextureView(textureView)
        textureView.surfaceTextureListener = cameraModule.surfaceTextureListener
        cameraModule.requestPermissions()
        initListener()
        setV1V2ToggleButtonText()
    }

    private fun initListener() {
        i("")
        recordButton.setOnClickListener {
            i("")
            toggleCapture()
        }
        playButton.setOnClickListener {
            i("")
            when (cameraModule.cameraAPI) {
                CameraModule.CameraAPI.V1 -> {
                    cameraModule.outputFile?.absolutePath?.let { filePath ->
                        playVideo(filePath)
                    }
                }
                CameraModule.CameraAPI.V2 -> {
                    cameraModule.nextVideoAbsolutePath?.let { filePath ->
                        playVideo(filePath)
                    }
                }
            }
        }
        v1v2ToggleButton.setOnClickListener {
            i("")
            when (cameraModule.cameraAPI) {
                CameraModule.CameraAPI.V1 -> {
                    cameraModule.cameraAPI = CameraModule.CameraAPI.V2
                    setV1V2ToggleButtonText()
                    i("Camera API changed to V2")
                    cameraModule.cameraOnPause()
                    cameraModule.cameraOnResume()
                }
                CameraModule.CameraAPI.V2 -> {
                    cameraModule.cameraAPI = CameraModule.CameraAPI.V1
                    setV1V2ToggleButtonText()
                    i("Camera API changed to V1")
                    cameraModule.cameraOnPause()
                    cameraModule.cameraOnResume()
                }
            }
        }
    }

    private fun setV1V2ToggleButtonText() {
        i("")
        when (cameraModule.cameraAPI) {
            CameraModule.CameraAPI.V1 -> v1v2ToggleButton.text = "to V2"
            CameraModule.CameraAPI.V2 -> v1v2ToggleButton.text = "to V1"
        }
    }

    override fun onStart() {
        i("")
        super.onStart()
    }

    override fun onResume() {
        i("")
        super.onResume()
        cameraModule.isActivityForeground = true
        cameraModule.cameraOnResume()
    }


    override fun onRestart() {
        i("")
        super.onRestart()
    }

    override fun onPause() {
        i("")
        super.onPause()
        cameraModule.isActivityForeground = false
        cameraModule.cameraOnPause()
    }


    override fun onStop() {
        i("")
        super.onStop()
    }

    override fun onDestroy() {
        i("")
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        i("")
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        i("")
        super.onRestoreInstanceState(savedInstanceState)
    }


    // ------------------------------------ play video ------------------------------------

    private fun playVideo(urlString: String) {
        i("")
        isVideoPlaying = true
        videoView.apply {
            textureView.visibility = View.INVISIBLE
            visibility = View.VISIBLE
            window.setFormat(PixelFormat.TRANSLUCENT)
            val mediaCtrl = MediaController(this@MainActivity)
            mediaCtrl.setMediaPlayer(this)
            setMediaController(mediaCtrl)
            i("playVideo url = $urlString")
            val clip = Uri.parse(urlString)
            setVideoURI(clip)
            requestFocus()
            setOnPreparedListener {
                start()
            }
            setOnCompletionListener {
                visibility = View.GONE
                textureView.visibility = View.VISIBLE
                isVideoPlaying = false
            }
        }


    }

    // ------------------------------------ for v1v2 ------------------------------------


    private fun toggleCapture() {
        i("")
        if (cameraModule.isRecording) {
            cameraModule.isRecording = false
            i("isRecording = false")
            when (cameraModule.cameraAPI) {
                CameraModule.CameraAPI.V1 -> {
                    cameraModule.stopMediaRecorder()
                    cameraModule.releaseMediaRecorder()
                }
                CameraModule.CameraAPI.V2 -> {
                    cameraModule.stopRecordingVideoV2()
                }
            }
        } else {
            cameraModule.isRecording = true
            i("isRecording = true")
            Single.fromCallable {
                when (cameraModule.cameraAPI) {
                    CameraModule.CameraAPI.V1 -> {
                        cameraModule.startMediaRecorder()
                    }
                    CameraModule.CameraAPI.V2 -> {
                        cameraModule.startRecordingVideoV2()
                    }
                }
                true
            }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    if (it) {
                        Toast.makeText(this, "녹화 시작", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "녹화 실패", Toast.LENGTH_SHORT).show()
                    }
                }, {
                    Toast.makeText(this, "녹화 망함", Toast.LENGTH_SHORT).show()
                })
        }
    }


}