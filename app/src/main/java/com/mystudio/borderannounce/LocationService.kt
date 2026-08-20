package com.mystudio.borderannounce

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class LocationService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var okHttpClient: OkHttpClient

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var toneGenerator: ToneGenerator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSpeechRunnable: Runnable? = null

    private val speechAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val muniMap = mutableMapOf<String, String>()

    private var lastApiLat: Double? = null
    private var lastApiLng: Double? = null

    private var lastRequestTimestamp: Long = 0

    private var lastPref: String = ""
    private var lastCity: String = ""
    private var lastDistrict: String = ""

    private var lastSpokenAddressText: String = ""
    private var lastSpokenErrorText: String = ""

    private var pendingStartupAnnouncement: String? = null

    companion object {
        private const val CHANNEL_ID = "location_service_channel"
        private const val NOTIFICATION_ID = 12345
        private const val MOVEMENT_THRESHOLD_SQ = 1e-8
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        loadMuniData()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    
                    if (!location.hasAccuracy() || location.accuracy > 30.0f) {
                        handleUnstableGps()
                        return
                    }

                    val lat = location.latitude
                    val lng = location.longitude

                    if (hasMovedEnoughFromLastApi(lat, lng)) {
                        fetchAddressFromApi(lat, lng)
                    }
                } else {
                    handleGpsFailure()
                }
            }
        }
    }

    private fun loadMuniData() {
        try {
            assets.open("muni.js").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.contains("GSI.MUNI_ARRAY[\"")) {
                        val startCode = line.indexOf("[\"") + 2
                        val endCode = line.indexOf("\"]")
                        val rawCode = line.substring(startCode, endCode)
                        val code = rawCode.padStart(5, '0')

                        val startData = line.indexOf("'") + 1
                        val endData = line.lastIndexOf("'")
                        val data = line.substring(startData, endData)
                        
                        muniMap[code] = data
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasMovedEnoughFromLastApi(newLat: Double, newLng: Double): Boolean {
        val refLat = lastApiLat ?: return true
        val refLng = lastApiLng ?: return true

        val diffLat = newLat - refLat
        val diffLng = newLng - refLng
        val distSq = (diffLat * diffLat) + (diffLng * diffLng)

        return distSq >= MOVEMENT_THRESHOLD_SQ
    }

    private fun fetchAddressFromApi(lat: Double, lng: Double) {
        val requestTime = System.currentTimeMillis()
        lastRequestTimestamp = requestTime

        val url = "https://mreversegeocoder.gsi.go.jp/reverse-geocoder/LonLatToAddress?lat=$lat&lon=$lng"
        val request = Request.Builder().url(url).build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 💡 通信失敗時は座標を更新せず、電波復旧を待って再試行を許可する
                handler.post { handleApiTimeoutFailure() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        handler.post { handleApiTimeoutFailure() }
                        return
                    }
                    
                    val body = res.body.string()
                    
                    handler.post {
                        if (requestTime < lastRequestTimestamp) return@post

                        try {
                            val json = JSONObject(body)
                            
                            // 💡 サーバーから「地名データなし」の200 OKが返ってきた場合：
                            // 調査完了として座標を更新し、止まっている間の無駄な通信リトライ嵐を防止する
                            if (json.isNull("results") || !json.has("results")) {
                                lastApiLat = lat
                                lastApiLng = lng
                                handleNoPlaceInfoFailure()
                                return@post
                            }

                            val results = json.optJSONObject("results")
                            if (results == null) {
                                lastApiLat = lat
                                lastApiLng = lng
                                handleNoPlaceInfoFailure()
                                return@post
                            }

                            val rawMuniCd = results.optString("muniCd", "")
                            if (rawMuniCd.isEmpty()) {
                                lastApiLat = lat
                                lastApiLng = lng
                                handleNoPlaceInfoFailure()
                                return@post
                            }

                            val rawLv01Nm = results.optString("lv01Nm", "").ifEmpty { "詳細地名なし" }

                            processAddress(rawMuniCd, rawLv01Nm, lat, lng)
                        } catch (e: Exception) {
                            lastApiLat = lat
                            lastApiLng = lng
                            handleNoPlaceInfoFailure()
                        }
                    }
                }
            }
        })
    }

    private fun getBaseTownName(name: String): String {
        return name.replace(Regex("(?:[一二三四五六七八九十百0-9０-９]+|〇)丁目$"), "")
    }

    private fun getChomePart(name: String): String {
        val match = Regex("(?:[一二三四五六七八九十百0-9０-９]+|〇)丁目$").find(name)
        return match?.value ?: ""
    }

    private fun getLevelRank(level: String): Int {
        return when (level) {
            "A" -> 1
            "B" -> 2
            "C" -> 3
            "D" -> 4
            else -> 4
        }
    }

    private fun hasChome(name: String): Boolean {
        return name.endsWith("丁目")
    }

    private fun processAddress(muniCd: String, rawDistrict: String, lat: Double, lng: Double) {
        lastSpokenErrorText = ""

        val normalizedCode = muniCd.padStart(5, '0')
        val muniData = muniMap[normalizedCode] ?: return
        val parts = muniData.split(",")
        if (parts.size < 4) return

        lastApiLat = lat
        lastApiLng = lng

        val currPref = parts[1]
        val currCity = parts[3]

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val maxDetail = prefs.getString("max_detail_level", "D") ?: "D"
        val verbosity = prefs.getString("verbosity_level", "C") ?: "C"
        val overridePref = prefs.getBoolean("override_pref", true)
        val overrideCity = prefs.getBoolean("override_city", true)
        val overrideTown = prefs.getBoolean("override_town", true)
        val omitSuffix = prefs.getBoolean("omit_suffix", false)

        val effectiveMaxDetail = if (maxDetail == "D" && !hasChome(rawDistrict)) "C" else maxDetail

        val currDistrict = when (effectiveMaxDetail) {
            "A", "B" -> ""
            "C" -> getBaseTownName(rawDistrict)
            "D" -> rawDistrict
            else -> rawDistrict
        }

        val isPrefChanged = lastPref.isNotEmpty() && currPref != lastPref
        val isCityChanged = lastCity.isNotEmpty() && currCity != lastCity
        
        val lastBase = getBaseTownName(lastDistrict)
        val currBase = getBaseTownName(currDistrict)
        
        val isTownChanged = lastDistrict.isNotEmpty() && (currBase != lastBase || isCityChanged || isPrefChanged)
        val isChomeOnlyChanged = lastDistrict.isNotEmpty() && currBase == lastBase && !isCityChanged && !isPrefChanged && currDistrict != lastDistrict

        val oldPref = lastPref
        val oldCity = lastCity
        val oldDistrict = lastDistrict

        lastPref = currPref
        lastCity = currCity
        lastDistrict = currDistrict

        if (oldPref.isEmpty()) {
            val welcomeText = buildAnnouncementText(
                pref = currPref,
                city = currCity,
                rawDistrict = currDistrict,
                startFrom = "A",
                maxDetail = effectiveMaxDetail
            )
            
            val welcomeTextSpeech = "現在、${welcomeText}です。"
            
            if (isTtsReady) {
                speak(welcomeTextSpeech)
            } else {
                pendingStartupAnnouncement = welcomeTextSpeech
            }
            return
        }

        var activeStartFrom = verbosity

        if (isPrefChanged && overridePref) {
            activeStartFrom = "A"
        } else if (isCityChanged && overrideCity) {
            if (getLevelRank("B") < getLevelRank(activeStartFrom)) {
                activeStartFrom = "B"
            }
        } else if (isTownChanged && overrideTown) {
            if (getLevelRank("C") < getLevelRank(activeStartFrom)) {
                activeStartFrom = "C"
            }
        }

        val shouldAnnounce = when (effectiveMaxDetail) {
            "A" -> isPrefChanged
            "B" -> isPrefChanged || isCityChanged
            "C" -> isPrefChanged || isCityChanged || isTownChanged
            "D" -> isPrefChanged || isCityChanged || isTownChanged || isChomeOnlyChanged
            else -> false
        }

        if (shouldAnnounce) {
            val announceText = buildAnnouncementText(
                pref = currPref,
                city = currCity,
                rawDistrict = currDistrict,
                startFrom = activeStartFrom,
                maxDetail = effectiveMaxDetail
            )
            
            if (omitSuffix) {
                speak(announceText)
            } else {
                speak("${announceText}に入りました。")
            }
        }
    }

    private fun buildAnnouncementText(
        pref: String,
        city: String,
        rawDistrict: String,
        startFrom: String,
        maxDetail: String
    ): String {
        val p1 = pref
        val p2 = city
        val p3 = getBaseTownName(rawDistrict)
        val p4 = getChomePart(rawDistrict)

        val maxRank = getLevelRank(maxDetail)
        val rawStartRank = getLevelRank(startFrom)
        val startRank = if (rawStartRank > maxRank) maxRank else rawStartRank

        val builder = StringBuilder()

        if (startRank <= 1 && maxRank >= 1 && p1.isNotEmpty()) {
            builder.append(p1)
        }
        if (startRank <= 2 && maxRank >= 2 && p2.isNotEmpty()) {
            builder.append(p2)
        }
        if (startRank <= 3 && maxRank >= 3 && p3.isNotEmpty()) {
            builder.append(p3)
        }
        if (startRank <= 4 && maxRank >= 4 && p4.isNotEmpty()) {
            builder.append(p4)
        }

        if (builder.isEmpty()) {
            return if (p3.isNotEmpty()) p3 else if (p2.isNotEmpty()) p2 else p1
        }

        return builder.toString()
    }

    private fun handleGpsFailure() {
        speakError("位置情報を取得できません。")
    }

    private fun handleUnstableGps() {
        speakError("位置情報が不安定です。")
    }

    private fun handleApiTimeoutFailure() {
        speakError("地名を取得できません。")
    }

    private fun handleNoPlaceInfoFailure() {
        speakError("地名情報がありません。")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(4000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "位置情報追跡サービス",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ボーダーアナウンス動作中")
            .setContentText("バックグラウンドで市境・地区境の検出を行っています。")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        okHttpClient.dispatcher.cancelAll()

        pendingSpeechRunnable?.let { handler.removeCallbacks(it) }
        pendingSpeechRunnable = null

        handler.removeCallbacksAndMessages(null)
        abandonMyAudioFocus()

        fusedLocationClient.removeLocationUpdates(locationCallback)
        tts?.stop()
        tts?.shutdown()
        
        toneGenerator?.release()
        toneGenerator = null
        
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.JAPANESE
            tts?.setAudioAttributes(speechAudioAttributes)
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                }

                override fun onDone(utteranceId: String?) {
                    checkAndAbandonAudioFocus()
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    checkAndAbandonAudioFocus()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    checkAndAbandonAudioFocus()
                }
            })
            
            isTtsReady = true

            pendingStartupAnnouncement?.let {
                speak(it)
                pendingStartupAnnouncement = null
            }
        }
    }

    private fun checkAndAbandonAudioFocus() {
        handler.postDelayed({
            if (pendingSpeechRunnable == null && tts?.isSpeaking == false) {
                abandonMyAudioFocus()
            }
        }, 100)
    }

    private fun speak(text: String) {
        if (!isTtsReady) return
        if (text == lastSpokenAddressText) return

        lastSpokenAddressText = text

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val useAudioFocus = prefs.getBoolean("audio_focus", true)

        if (useAudioFocus) {
            pendingSpeechRunnable?.let { handler.removeCallbacks(it) }

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAudioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()

            val res = audioManager.requestAudioFocus(focusRequest!!)
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val runnable = Runnable {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_speech")
                    pendingSpeechRunnable = null
                }
                pendingSpeechRunnable = runnable
                handler.postDelayed(runnable, 350)
            } else {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_speech")
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_speech")
        }
    }

    private fun speakError(errorText: String) {
        if (!isTtsReady) return
        if (errorText == lastSpokenErrorText) return

        lastSpokenErrorText = errorText

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val useAudioFocus = prefs.getBoolean("audio_focus", true)

        if (useAudioFocus) {
            if (pendingSpeechRunnable != null || tts?.isSpeaking == true) {
                tts?.speak(errorText, TextToSpeech.QUEUE_ADD, null, "service_error")
            } else {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(speechAudioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()

                val res = audioManager.requestAudioFocus(focusRequest!!)
                if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    try {
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val runnable = Runnable {
                        tts?.speak(errorText, TextToSpeech.QUEUE_ADD, null, "service_error")
                        pendingSpeechRunnable = null
                    }
                    pendingSpeechRunnable = runnable
                    handler.postDelayed(runnable, 350)
                } else {
                    tts?.speak(errorText, TextToSpeech.QUEUE_ADD, null, "service_error")
                }
            }
        } else {
            tts?.speak(errorText, TextToSpeech.QUEUE_ADD, null, "service_error")
        }
    }

    private fun abandonMyAudioFocus() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val useAudioFocus = prefs.getBoolean("audio_focus", true)
        if (!useAudioFocus) return

        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}