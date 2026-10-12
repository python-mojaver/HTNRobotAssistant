package com.htnhome.robotassistant

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.csjbot.coshandler.core.CsjRobot
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var csjRobot: CsjRobot

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false


    // =========================================================
    // Native microphone -> WAV -> WebView -> FastAPI/Whisper
    // Avoids WebView getUserMedia(), which fails on this robot.
    // =========================================================

    @Volatile
    private var nativeWhisperMicRunning = false
    private var nativeWhisperAudioRecord: AudioRecord? = null
    private var nativeWhisperThread: Thread? = null

    private val nativeWhisperSampleRate = 16000
    private val nativeWhisperVadThreshold = 0.020

    // Keep the latest partial text as a fallback because the Google recognizer
    // on this robot sometimes returns an empty final result.
    private var lastPartialSpeechText: String? = null
    private val speechHandler = Handler(Looper.getMainLooper())
    private val forceStopListeningRunnable = Runnable {
        if (isListening) {
            Log.d("HTN_SPEECH", "Force stopListening after command window")
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e("HTN_SPEECH", "force stopListening failed", e)
            }
        }
    }

    private val microphonePermissionCode = 100

    private val serverBaseUrl = "http://127.0.0.1:8000"


    // =========================================================
    // CSJBOT RobotSdkService raw Binder bridge
    // =========================================================

    private val robotSdkPackage = "com.csjbot.robotsdk.ten"
    private val robotSdkService =
        "com.csjbot.robotsdk.service.RobotSdkService"

    private val robotAidlDescriptor =
        "com.csjbot.sdkhandler.IAarToSdkApp"

    private val transactionConnectToSdk = 1
    private val transactionSendMessage = 2

    private var robotBinder: IBinder? = null
    private var robotServiceBound = false
    private var automaticWaveTestDone = false

    private val robotHandler = Handler(Looper.getMainLooper())

    private val robotServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            robotBinder = service
            robotServiceBound = service != null

            Log.i(
                "HTN_ROBOT",
                "RobotSdkService connected: $name"
            )

            if (service == null) {
                Log.e("HTN_ROBOT", "RobotSdkService binder is NULL")
                return
            }

            // Same Binder handshake found in the original CSJBOT SDK:
            // transaction 1 -> connectToSDK(String appName)
            val handshakeOk = callRobotBinder(
                transactionConnectToSdk,
                packageName
            )

            Log.i(
                "HTN_ROBOT",
                "connectToSDK(packageName) result=$handshakeOk"
            )

            // TEST BUILD:
            // Run one short direct Timo head test after connection.
            // It starts only once per Activity instance.
            if (!automaticWaveTestDone) {
                automaticWaveTestDone = true

                robotHandler.postDelayed(
                    {
                        triggerRobotHeadTest()
                    },
                    1000L
                )
            }
        }

        override fun onServiceDisconnected(
            name: ComponentName?
        ) {
            Log.w(
                "HTN_ROBOT",
                "RobotSdkService disconnected: $name"
            )

            robotBinder = null
            robotServiceBound = false
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize official CSJBOT SDK
        csjRobot = CsjRobot.getInstance()
        csjRobot.init(applicationContext)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // -----------------------------
        // WebView settings
        // -----------------------------

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = WebViewClient()

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest?) {

                runOnUiThread {

                    if (request == null) {
                        return@runOnUiThread
                    }

                    val audioRequested =
                        request.resources.contains(
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        )

                    val androidPermissionGranted =
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                    Log.d(
                        "HTN_MIC",
                        "WebView permission request: " +
                                "audioRequested=$audioRequested " +
                                "androidPermission=$androidPermissionGranted"
                    )

                    if (
                        audioRequested &&
                        androidPermissionGranted
                    ) {

                        request.grant(
                            arrayOf(
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            )
                        )

                        Log.d(
                            "HTN_MIC",
                            "WebView microphone permission GRANTED"
                        )

                    } else {

                        request.deny()

                        Log.e(
                            "HTN_MIC",
                            "WebView microphone permission DENIED"
                        )
                    }
                }
            }

            override fun onConsoleMessage(
                consoleMessage: ConsoleMessage?
            ): Boolean {

                consoleMessage?.let {

                    Log.d(
                        "HTN_JS",
                        "${it.message()} | " +
                                "line=${it.lineNumber()} | " +
                                "source=${it.sourceId()}"
                    )
                }

                return true
            }
        }


        // -----------------------------
        // JavaScript ↔ Android Bridge
        // -----------------------------

        webView.addJavascriptInterface(
            AndroidBridge(),
            "AndroidRobot"
        )


        // -----------------------------
        // CSJBOT Robot SDK bridge
        // -----------------------------

        bindRobotSdkService()


        // -----------------------------
        // Microphone permission
        // -----------------------------

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphonePermissionCode
            )

        } else {

            // Native Android SpeechRecognizer is intentionally disabled.
            // WebView + PrimeMicrophone + Whisper owns the microphone.
            Log.d(
                "HTN_SPEECH",
                "Native SpeechRecognizer disabled; using WebView/Whisper microphone path"
            )
        }


        // -----------------------------
        // FastAPI page
        // -----------------------------

        webView.loadUrl(serverBaseUrl)


        // -----------------------------
        // Back button
        // -----------------------------

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }


    // =========================================================
    // Permission result
    // =========================================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == microphonePermissionCode) {

            val granted =
                grantResults.isNotEmpty() &&
                        grantResults[0] ==
                        PackageManager.PERMISSION_GRANTED

            if (granted) {

                Log.d(
                    "HTN_SPEECH",
                    "RECORD_AUDIO permission granted"
                )

                Toast.makeText(
                    this,
                    "دسترسی میکروفون فعال شد",
                    Toast.LENGTH_SHORT
                ).show()

                // Do not create/start Android SpeechRecognizer.
                // The microphone is reserved for WebView getUserMedia -> Whisper.
                Log.d(
                    "HTN_SPEECH",
                    "RECORD_AUDIO granted; native recognizer remains disabled"
                )

            } else {

                Log.e(
                    "HTN_SPEECH",
                    "RECORD_AUDIO permission denied"
                )

                Toast.makeText(
                    this,
                    "دسترسی میکروفون داده نشده.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    // =========================================================
    // SpeechRecognizer setup
    // =========================================================

    private fun setupSpeechRecognizer() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                "HTN_SPEECH",
                "setupSpeechRecognizer: microphone permission missing"
            )

            return
        }


        val available =
            SpeechRecognizer.isRecognitionAvailable(this)

        Log.d(
            "HTN_SPEECH",
            "SpeechRecognizer.isRecognitionAvailable=$available"
        )


        if (!available) {

            Log.e(
                "HTN_SPEECH",
                "Speech recognition service is not available"
            )

            return
        }


        if (speechRecognizer != null) {
            return
        }


        try {

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            Log.d(
                "HTN_SPEECH",
                "SpeechRecognizer created successfully"
            )

        } catch (e: Exception) {

            Log.e(
                "HTN_SPEECH",
                "createSpeechRecognizer failed",
                e
            )

            return
        }


        speechRecognizer?.setRecognitionListener(

            object : RecognitionListener {


                override fun onReadyForSpeech(
                    params: Bundle?
                ) {

                    Log.d(
                        "HTN_SPEECH",
                        "onReadyForSpeech"
                    )

                    sendSpeechStatusToWeb("ready")
                }


                override fun onBeginningOfSpeech() {

                    Log.d(
                        "HTN_SPEECH",
                        "onBeginningOfSpeech"
                    )

                    sendSpeechStatusToWeb("speaking")

                    // Commands such as "برو اتاق آقای خدیوزاد" are short.
                    // Some Google Speech builds on this robot do not finish the
                    // session after silence, so force finalization after 6 sec.
                    speechHandler.removeCallbacks(forceStopListeningRunnable)
                    speechHandler.postDelayed(forceStopListeningRunnable, 6000L)
                }


                override fun onRmsChanged(
                    rmsdB: Float
                ) {
                    // فعلاً نیازی نداریم
                }


                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {
                }


                override fun onEndOfSpeech() {

                    Log.d(
                        "HTN_SPEECH",
                        "onEndOfSpeech"
                    )

                    speechHandler.removeCallbacks(forceStopListeningRunnable)
                }


                override fun onError(
                    error: Int
                ) {

                    isListening = false
                    speechHandler.removeCallbacks(forceStopListeningRunnable)

                    // On this robot Google Speech can return NO_MATCH/TIMEOUT
                    // even after delivering useful partial Persian text.
                    val partialFallback = lastPartialSpeechText?.trim()
                    if (
                        (error == SpeechRecognizer.ERROR_NO_MATCH ||
                         error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                        !partialFallback.isNullOrBlank()
                    ) {
                        Log.w(
                            "HTN_SPEECH",
                            "Using partial result as final fallback after error=$error: '$partialFallback'"
                        )

                        lastPartialSpeechText = null
                        sendTranscriptionToWeb(partialFallback)
                        handleRobotVoiceCommand(partialFallback)
                        return
                    }

                    lastPartialSpeechText = null

                    val message = when (error) {

                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "زمان اتصال به سرویس تشخیص گفتار تمام شد."

                        SpeechRecognizer.ERROR_NETWORK ->
                            "خطا در اتصال اینترنت برای تشخیص گفتار."

                        SpeechRecognizer.ERROR_AUDIO ->
                            "خطا در دریافت صدا از میکروفون."

                        SpeechRecognizer.ERROR_SERVER ->
                            "خطای سرویس تشخیص گفتار."

                        SpeechRecognizer.ERROR_CLIENT ->
                            "خطای داخلی در شروع تشخیص گفتار."

                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "صدایی شنیده نشد."

                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "صدای شما تشخیص داده نشد."

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "سرویس تشخیص گفتار مشغول است."

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "اجازه دسترسی به میکروفون داده نشده."

                        else ->
                            "خطای تشخیص گفتار با کد $error"
                    }


                    Log.e(
                        "HTN_SPEECH",
                        "onError code=$error message=$message"
                    )


                    sendSpeechErrorToWeb(
                        error,
                        message
                    )
                }


                override fun onResults(
                    results: Bundle?
                ) {

                    isListening = false
                    speechHandler.removeCallbacks(forceStopListeningRunnable)

                    val matches =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    val finalText =
                        matches
                            ?.firstOrNull()
                            ?.trim()

                    val fallbackText =
                        lastPartialSpeechText
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }

                    val text =
                        finalText
                            ?.takeIf { it.isNotBlank() }
                            ?: fallbackText

                    Log.d(
                        "HTN_SPEECH",
                        "onResults final='$finalText' fallback='$fallbackText' selected='$text'"
                    )

                    lastPartialSpeechText = null

                    if (!text.isNullOrBlank()) {

                        sendTranscriptionToWeb(text)

                        // Voice command: "برو اتاق آقای خدیوزاد"
                        handleRobotVoiceCommand(text)

                    } else {

                        sendSpeechErrorToWeb(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            "صدایی تشخیص داده نشد."
                        )
                    }
                }


                override fun onPartialResults(
                    partialResults: Bundle?
                ) {

                    val matches =
                        partialResults?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    val text =
                        matches
                            ?.firstOrNull()
                            ?.trim()


                    if (!text.isNullOrBlank()) {

                        lastPartialSpeechText = text

                        Log.d(
                            "HTN_SPEECH",
                            "onPartialResults='$text'"
                        )

                        sendPartialTranscriptionToWeb(
                            text
                        )
                    }
                }


                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {
                }
            }
        )
    }


    // =========================================================
    // JavaScript Bridge
    // =========================================================

    inner class AndroidBridge {


        /*
         * index.html با این تابع بررسی می‌کند که
         * Native Speech Recognition قابل استفاده هست یا نه
         */

        @JavascriptInterface
        fun isSpeechRecognitionAvailable(): Boolean {

            val permissionGranted =
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED


            val recognitionAvailable =
                SpeechRecognizer.isRecognitionAvailable(
                    this@MainActivity
                )


            Log.d(
                "HTN_SPEECH",
                "isSpeechRecognitionAvailable: " +
                        "permission=$permissionGranted " +
                        "service=$recognitionAvailable"
            )


            // Native recognition is disabled on purpose so prime.js uses
            // PrimeMicrophone/getUserMedia and sends audio to Whisper.
            return false
        }


        /*
         * شروع SpeechRecognizer
         */

        @JavascriptInterface
        fun startNativeSpeechRecognition() {

            // Intentionally disabled: WebView + PrimeMicrophone + Whisper
            // is the only speech-recognition path.
            Log.d(
                "HTN_SPEECH",
                "Native speech disabled; WebView/Whisper owns microphone"
            )

            return
        }


        /*
         * لغو SpeechRecognizer
         */

        @JavascriptInterface
        fun cancelNativeSpeechRecognition() {

            Log.d(
                "HTN_SPEECH",
                "cancelNativeSpeechRecognition called"
            )


            runOnUiThread {

                try {

                    speechRecognizer?.cancel()

                } catch (e: Exception) {

                    Log.e(
                        "HTN_SPEECH",
                        "cancelNativeSpeechRecognition error",
                        e
                    )
                }

                speechHandler.removeCallbacks(forceStopListeningRunnable)
                lastPartialSpeechText = null
                isListening = false
            }
        }


        /*
         * برای Debug
         */

        @JavascriptInterface
        fun isNativeListening(): Boolean {

            return isListening
        }


        // Native AudioRecord path used by Whisper on Timo.
        @JavascriptInterface
        fun startNativeWhisperMic(): Boolean {
            return startNativeWhisperMicrophone()
        }

        @JavascriptInterface
        fun stopNativeWhisperMic() {
            stopNativeWhisperMicrophone()
        }

        @JavascriptInterface
        fun isNativeWhisperMicRunning(): Boolean {
            return nativeWhisperMicRunning
        }

        /*
         * وضعیت اتصال به RobotSdkService
         */

        @JavascriptInterface
        fun isRobotSdkConnected(): Boolean {

            return robotServiceBound &&
                    robotBinder != null
        }


        /*
         * تست دستی فرمان مستقیم حرکت سر Timo از سمت WebView:
         * AndroidRobot.testRobotWave()
         */

        @JavascriptInterface
        fun testRobotWave() {

            runOnUiThread {

                triggerRobotHeadTest()
            }
        }

        /*
         * حرکت واقعی به اتاق آقای خدیوزاد از WebView:
         * AndroidRobot.goToKhodivzadRoom()
         */
        @JavascriptInterface
        fun goToKhodivzadRoom() {

            runOnUiThread {

                triggerKhodivzadRoomNavigation()
            }
        }

        /*
         * Alias قدیمی برای اینکه تست‌های قبلی هم خراب نشوند:
         * AndroidRobot.testNavigateNearPoint()
         */
        @JavascriptInterface
        fun testNavigateNearPoint() {

            goToKhodivzadRoom()
        }
    }


    // =========================================================
    // CSJBOT Robot SDK Binder bridge
    // =========================================================

    private fun bindRobotSdkService() {

        if (robotServiceBound) {
            return
        }

        val intent = Intent().apply {
            component = ComponentName(
                robotSdkPackage,
                robotSdkService
            )
        }

        try {

            val result = bindService(
                intent,
                robotServiceConnection,
                Context.BIND_AUTO_CREATE
            )

            Log.i(
                "HTN_ROBOT",
                "bindService result=$result"
            )

            if (!result) {

                Toast.makeText(
                    this,
                    "اتصال به RobotSdkService برقرار نشد.",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {

            Log.e(
                "HTN_ROBOT",
                "bindRobotSdkService failed",
                e
            )

            Toast.makeText(
                this,
                "خطا در اتصال به سرویس ربات: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    /*
     * Raw equivalent of:
     *
     * IAarToSdkApp.connectToSDK(String)      -> transaction 1
     * IAarToSdkApp.aarMsgToSDKApp(String)   -> transaction 2
     */

    private fun callRobotBinder(
        transactionCode: Int,
        value: String
    ): Boolean {

        val binder = robotBinder ?: run {

            Log.e(
                "HTN_ROBOT",
                "callRobotBinder: robotBinder is NULL"
            )

            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        return try {

            data.writeInterfaceToken(
                robotAidlDescriptor
            )

            data.writeString(value)

            val transactOk = binder.transact(
                transactionCode,
                data,
                reply,
                0
            )

            if (!transactOk) {

                Log.e(
                    "HTN_ROBOT",
                    "Binder transact returned false. code=$transactionCode"
                )

                false

            } else {

                reply.readException()

                Log.d(
                    "HTN_ROBOT",
                    "Binder transaction succeeded. code=$transactionCode value=$value"
                )

                true
            }

        } catch (e: Exception) {

            Log.e(
                "HTN_ROBOT",
                "Binder transaction failed. code=$transactionCode",
                e
            )

            false

        } finally {

            reply.recycle()
            data.recycle()
        }
    }


    /*
     * Direct Timo head diagnostic test.
     *
     * Move head slightly to the right:
     * {"msg_id":"ALICE_NEW_ACTION_CTRL_REQ","ctrl_type":"head_left_right","ctrl_angle":60}
     *
     * Reset after 3 seconds:
     * {"msg_id":"ALICE_NEW_ACTION_CTRL_RESET_REQ"}
     */

    private fun triggerRobotHeadTest() {

        if (!robotServiceBound || robotBinder == null) {

            Log.e(
                "HTN_ROBOT",
                "Timo direct head test requested but RobotSdkService is not connected"
            )

            Toast.makeText(
                this,
                "سرویس حرکتی ربات هنوز متصل نیست.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        // SDK mapping:
        // head_left_right = 0..100
        // 0 = left, 100 = right. 60 is a small movement away from center.
        val headMoveJson =
            JSONObject()
                .put(
                    "msg_id",
                    "ALICE_NEW_ACTION_CTRL_REQ"
                )
                .put(
                    "ctrl_type",
                    "head_left_right"
                )
                .put(
                    "ctrl_angle",
                    60
                )
                .toString()

        val resetActionJson =
            JSONObject()
                .put(
                    "msg_id",
                    "ALICE_NEW_ACTION_CTRL_RESET_REQ"
                )
                .toString()

        Log.i(
            "HTN_ROBOT",
            "Sending TIMO HEAD LEFT/RIGHT DIRECT: $headMoveJson"
        )

        val sendOk = callRobotBinder(
            transactionSendMessage,
            headMoveJson
        )

        Toast.makeText(
            this,
            if (sendOk)
                "فرمان تست حرکت سر به Timo ارسال شد."
            else
                "ارسال فرمان تست حرکت سر ناموفق بود.",
            Toast.LENGTH_SHORT
        ).show()

        robotHandler.postDelayed(
            {

                Log.i(
                    "HTN_ROBOT",
                    "Sending TIMO ACTION RESET: $resetActionJson"
                )

                callRobotBinder(
                    transactionSendMessage,
                    resetActionJson
                )

            },
            3000L
        )
    }



    // =========================================================
    // Voice command routing
    // =========================================================

    private fun normalizePersianCommand(raw: String): String {
        return raw
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace("\u200c", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun handleRobotVoiceCommand(rawText: String): Boolean {
        val text = normalizePersianCommand(rawText)

        val mentionsKhodivzad =
            text.contains("خدیوزاد") ||
            text.contains("خديوزاد")

        val isNavigationCommand =
            text.contains("برو") ||
            text.contains("حرکت کن") ||
            text.contains("بریم") ||
            text.contains("برو به")

        if (mentionsKhodivzad && isNavigationCommand) {
            Log.i(
                "HTN_VOICE_CMD",
                "Matched Khodivzad room command: '$text'"
            )

            runOnUiThread {
                Toast.makeText(
                    this,
                    "چشم، می‌رم اتاق آقای خدیوزاد.",
                    Toast.LENGTH_SHORT
                ).show()

                triggerKhodivzadRoomNavigation()
            }

            return true
        }

        return false
    }

    // =========================================================
    // CSJBOT Navigation - Khodivzad room
    // =========================================================

    private fun triggerKhodivzadRoomNavigation() {

        val targetJson = JSONObject()
            .put("x", 7.024)
            .put("y", 9.470)
            .put("z", 0.0)
            .put("rotation", 1.251)
            .toString()

        Log.i(
            "HTN_NAVI",
            "Khodivzad room navigation test requested. target=$targetJson"
        )

        try {

            val getActionMethod =
                csjRobot.javaClass.methods.firstOrNull {
                    it.name == "getAction" && it.parameterCount == 0
                } ?: throw NoSuchMethodException("CsjRobot.getAction() not found")

            val actionObject =
                getActionMethod.invoke(csjRobot)
                    ?: throw IllegalStateException("CsjRobot.getAction() returned null")

            val naviMethod =
                actionObject.javaClass.methods.firstOrNull {
                    it.name == "navi" &&
                            it.parameterCount == 1 &&
                            it.parameterTypes[0] == String::class.java
                } ?: throw NoSuchMethodException(
                    "navi(String) not found on ${actionObject.javaClass.name}"
                )

            Log.i(
                "HTN_NAVI",
                "Calling ${actionObject.javaClass.name}.navi(String)"
            )

            naviMethod.invoke(
                actionObject,
                targetJson
            )

            Toast.makeText(
                this,
                "فرمان حرکت به اتاق آقای خدیوزاد ارسال شد.",
                Toast.LENGTH_SHORT
            ).show()

            Log.i(
                "HTN_NAVI",
                "Navigation command invoked successfully"
            )

        } catch (e: Exception) {

            Log.e(
                "HTN_NAVI",
                "Khodivzad room navigation failed",
                e
            )

            Toast.makeText(
                this,
                "خطا در Navigation: ${e.cause?.message ?: e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }



    // =========================================================
    // Native Whisper microphone (AudioRecord)
    // =========================================================

    private fun startNativeWhisperMicrophone(): Boolean {
        if (nativeWhisperMicRunning) {
            return true
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("HTN_NATIVE_MIC", "RECORD_AUDIO permission missing")
            sendNativeWhisperErrorToWeb("دسترسی میکروفن فعال نیست.")
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            nativeWhisperSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            Log.e("HTN_NATIVE_MIC", "Invalid minBuffer=$minBuffer")
            sendNativeWhisperErrorToWeb("راه‌اندازی میکروفن ربات ناموفق بود.")
            return false
        }

        val bufferSize = maxOf(minBuffer * 2, 4096)

        return try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                nativeWhisperSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                Log.e("HTN_NATIVE_MIC", "AudioRecord not initialized")
                sendNativeWhisperErrorToWeb("میکروفن ربات باز نشد.")
                return false
            }

            recorder.startRecording()

            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                Log.e("HTN_NATIVE_MIC", "AudioRecord did not enter RECORDING state")
                sendNativeWhisperErrorToWeb("ضبط صدای ربات شروع نشد.")
                return false
            }

            nativeWhisperAudioRecord = recorder
            nativeWhisperMicRunning = true

            nativeWhisperThread = Thread(
                { nativeWhisperLoop(recorder) },
                "HTN-NativeWhisperMic"
            ).apply {
                start()
            }

            Log.i("HTN_NATIVE_MIC", "Native Whisper microphone started")
            true

        } catch (e: Exception) {
            Log.e("HTN_NATIVE_MIC", "startNativeWhisperMicrophone failed", e)
            nativeWhisperMicRunning = false
            sendNativeWhisperErrorToWeb(
                "خطا در شروع میکروفن: ${e.message ?: "unknown"}"
            )
            false
        }
    }

    private fun stopNativeWhisperMicrophone() {
        nativeWhisperMicRunning = false

        val recorder = nativeWhisperAudioRecord
        nativeWhisperAudioRecord = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        nativeWhisperThread = null
        Log.i("HTN_NATIVE_MIC", "Native Whisper microphone stopped")
    }

    private fun nativeWhisperLoop(recorder: AudioRecord) {
        val samples = ShortArray(1024)
        var segment = ByteArrayOutputStream()

        var cycleStartedAt = SystemClock.elapsedRealtime()
        var loudSince = -1L
        var lastLoudAt = cycleStartedAt
        var speechDetected = false

        try {
            while (nativeWhisperMicRunning) {
                val count = recorder.read(samples, 0, samples.size)

                if (count <= 0) {
                    continue
                }

                var squareSum = 0.0

                for (i in 0 until count) {
                    val value = samples[i].toInt()
                    squareSum += value.toDouble() * value.toDouble()

                    // PCM16 little-endian
                    segment.write(value and 0xff)
                    segment.write((value shr 8) and 0xff)
                }

                val rms = kotlin.math.sqrt(squareSum / count) / 32768.0
                val now = SystemClock.elapsedRealtime()

                if (rms > nativeWhisperVadThreshold) {
                    lastLoudAt = now

                    if (loudSince < 0L) {
                        loudSince = now
                    }

                    if (!speechDetected && now - loudSince >= 120L) {
                        speechDetected = true
                        notifyNativeWhisperSpeechStarted()
                    }
                } else {
                    loudSince = -1L
                }

                val silenceFinished =
                    speechDetected && now - lastLoudAt > 900L

                val maxSegmentReached =
                    now - cycleStartedAt > 25000L

                val noSpeechWindowExpired =
                    !speechDetected && now - cycleStartedAt > 2500L

                if (
                    silenceFinished ||
                    maxSegmentReached ||
                    noSpeechWindowExpired
                ) {
                    if (speechDetected && segment.size() > 0) {
                        val wav = pcm16ToWav(segment.toByteArray())
                        sendNativeWhisperAudioToWeb(wav)
                    }

                    segment.close()
                    segment = ByteArrayOutputStream()
                    cycleStartedAt = now
                    loudSince = -1L
                    lastLoudAt = now
                    speechDetected = false
                }
            }
        } catch (e: Exception) {
            if (nativeWhisperMicRunning) {
                Log.e("HTN_NATIVE_MIC", "Native microphone loop failed", e)
                sendNativeWhisperErrorToWeb(
                    "خطا هنگام دریافت صدای ربات: ${e.message ?: "unknown"}"
                )
            }
        } finally {
            try {
                segment.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun pcm16ToWav(pcm: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(44 + pcm.size)
        val dataSize = pcm.size
        val byteRate = nativeWhisperSampleRate * 2

        fun writeAscii(value: String) {
            output.write(value.toByteArray(Charsets.US_ASCII))
        }

        fun writeLe16(value: Int) {
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
        }

        fun writeLe32(value: Int) {
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
            output.write((value shr 16) and 0xff)
            output.write((value shr 24) and 0xff)
        }

        writeAscii("RIFF")
        writeLe32(36 + dataSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLe32(16)
        writeLe16(1) // PCM
        writeLe16(1) // mono
        writeLe32(nativeWhisperSampleRate)
        writeLe32(byteRate)
        writeLe16(2) // block align
        writeLe16(16) // bits/sample
        writeAscii("data")
        writeLe32(dataSize)
        output.write(pcm)

        return output.toByteArray()
    }

    private fun notifyNativeWhisperSpeechStarted() {
        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onNativeWhisperSpeechStarted) window.onNativeWhisperSpeechStarted();",
                null
            )
        }
    }

    private fun sendNativeWhisperAudioToWeb(wav: ByteArray) {
        val encoded = Base64.encodeToString(wav, Base64.NO_WRAP)
        val quoted = JSONObject.quote(encoded)

        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onNativeWhisperAudio) window.onNativeWhisperAudio($quoted);",
                null
            )
        }
    }

    private fun sendNativeWhisperErrorToWeb(message: String) {
        val quoted = JSONObject.quote(message)

        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onNativeWhisperError) window.onNativeWhisperError($quoted);",
                null
            )
        }
    }

    // =========================================================
    // Start listening
    // =========================================================

    private fun startListeningNative() {


        if (isListening) {

            Log.d(
                "HTN_SPEECH",
                "Already listening"
            )

            return
        }


        // بررسی Permission

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                "HTN_SPEECH",
                "Microphone permission missing"
            )


            sendSpeechErrorToWeb(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                "دسترسی میکروفون فعال نیست."
            )


            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphonePermissionCode
            )

            return
        }


        // Clear state from any previous recognition session.
        speechHandler.removeCallbacks(forceStopListeningRunnable)
        lastPartialSpeechText = null

        // اطمینان از ساخته شدن SpeechRecognizer

        setupSpeechRecognizer()


        if (speechRecognizer == null) {

            Log.e(
                "HTN_SPEECH",
                "speechRecognizer is NULL"
            )


            sendSpeechErrorToWeb(
                -1,
                "سرویس تشخیص گفتار در دسترس نیست."
            )

            return
        }


        // -----------------------------
        // Recognition Intent
        // -----------------------------

        val intent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {


                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )


                // فارسی ایران

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "fa-IR"
                )


                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "fa-IR"
                )


                putExtra(
                    RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,
                    false
                )


                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )


                // متن هنگام صحبت کردن

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )


                // چند نتیجه برای افزایش دقت

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    5
                )


                // بعد از مکث کوتاه نتیجه نهایی بده

                putExtra(
                    "android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS",
                    800L
                )


                putExtra(
                    "android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS",
                    600L
                )


                putExtra(
                    "android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS",
                    500L
                )
            }


        try {

            Log.d(
                "HTN_SPEECH",
                "Calling startListening..."
            )


            speechRecognizer?.startListening(
                intent
            )


            isListening = true


            Log.d(
                "HTN_SPEECH",
                "startListening called successfully"
            )


        } catch (e: Exception) {

            isListening = false


            Log.e(
                "HTN_SPEECH",
                "startListening exception",
                e
            )


            sendSpeechErrorToWeb(
                -1,
                "خطا در شروع تشخیص گفتار: ${e.message}"
            )
        }
    }


    // =========================================================
    // Send SpeechRecognizer status to JavaScript
    // =========================================================

    private fun sendSpeechStatusToWeb(state: String) {

        val safeState = JSONObject.quote(state)

        runOnUiThread {

            webView.evaluateJavascript(
                """
                if (window.onNativeSpeechStatus) {
                    window.onNativeSpeechStatus($safeState);
                }
                """.trimIndent(),
                null
            )
        }
    }


    // =========================================================
    // Send FINAL result to JavaScript
    // =========================================================

    private fun sendTranscriptionToWeb(
        text: String
    ) {

        val safeText =
            JSONObject.quote(text)


        runOnUiThread {

            webView.evaluateJavascript(

                """
                if (window.onNativeTranscription) {
                    window.onNativeTranscription($safeText);
                } else {
                    console.error("onNativeTranscription is not defined");
                }
                """.trimIndent(),

                null
            )
        }
    }


    // =========================================================
    // Send PARTIAL result to JavaScript
    // =========================================================

    private fun sendPartialTranscriptionToWeb(
        text: String
    ) {

        val safeText =
            JSONObject.quote(text)


        runOnUiThread {

            webView.evaluateJavascript(

                """
                if (window.onNativePartialTranscription) {
                    window.onNativePartialTranscription($safeText);
                }
                """.trimIndent(),

                null
            )
        }
    }


    // =========================================================
    // Send SpeechRecognizer error to JavaScript
    // =========================================================

    private fun sendSpeechErrorToWeb(
        errorCode: Int,
        message: String
    ) {

        val safeMessage =
            JSONObject.quote(message)


        runOnUiThread {

            webView.evaluateJavascript(

                """
                if (window.onNativeSpeechError) {
                    window.onNativeSpeechError(
                        $errorCode,
                        $safeMessage
                    );
                } else {
                    console.error(
                        "Native Speech Error: " + $safeMessage
                    );
                }
                """.trimIndent(),

                null
            )
        }
    }


    // =========================================================
    // Destroy
    // =========================================================

    override fun onDestroy() {

        stopNativeWhisperMicrophone()

        try {

            speechRecognizer?.cancel()
            speechRecognizer?.destroy()

        } catch (_: Exception) {
        }


        speechRecognizer = null
        isListening = false
        lastPartialSpeechText = null

        speechHandler.removeCallbacksAndMessages(null)
        robotHandler.removeCallbacksAndMessages(null)

        if (robotServiceBound) {

            try {

                unbindService(
                    robotServiceConnection
                )

            } catch (e: Exception) {

                Log.w(
                    "HTN_ROBOT",
                    "unbindService warning",
                    e
                )
            }
        }

        robotBinder = null
        robotServiceBound = false


        super.onDestroy()
    }
}
