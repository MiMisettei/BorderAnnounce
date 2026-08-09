package com.mystudio.borderannounce

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    
    private val handler = Handler(Looper.getMainLooper())

    private val muniMap = mutableMapOf<String, String>()

    private var lastApiLat: Double? = null
    private var lastApiLng: Double? = null

    private var lastPref: String = ""
    private var lastCity: String = ""
    private var lastDistrict: String = ""

    private var isGpsLost = false
    private var isNetworkLost = false
    private var isGpsUnstable = false

    private var pendingStartupAnnouncement: String? = null

    companion object {
        private const val CHANNEL_ID = "location_service_channel"
        private const val NOTIFICATION_ID = 12345
        private const val MOVEMENT_THRESHOLD_SQ = 1e-8
    }

    override fun onCreate() {
        super.onCreate()
        loadMuniData()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    
                    val accuracy = if (location.hasAccuracy()) location.accuracy else 0.0f
                    if (accuracy > 30.0f) {
                        handleUnstableGps()
                        return
                    }

                    if (isGpsUnstable) {
                        isGpsUnstable = false
                    }

                    if (isGpsLost) {
                        isGpsLost = false
                    }
                    
                    val lat = location.latitude
                    val lng = location.longitude

                    if (hasMovedEnoughFromLastApi(lat, lng)) {
                        lastApiLat = lat
                        lastApiLng = lng
                        fetchAddressFromApi(lat, lng)
                    }
                } else {
                    handleGpsFailure()
                }
            }
        }

        createNotificationChannel()
    }

    private fun loadMuniData() {
        try {
            assets.open("muni.js").use { inputStream ->
                inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.contains("GSI.MUNI_ARRAY[\"")) {
                            val startCode = line.indexOf("[\"") + 2
                            val endCode = line.indexOf("\"]")
                            val code = line.substring(startCode, endCode)

                            val startData = line.indexOf("'") + 1
                            val endData = line.lastIndexOf("'")
                            val data = line.substring(startData, endData)
                            
                            muniMap[code] = data
                        }
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
        val url = "https://mreversegeocoder.gsi.go.jp/reverse-geocoder/LonLatToAddress?lat=$lat&lon=$lng"
        val request = Request.Builder().url(url).build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handleApiFailure()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    handleApiFailure()
                    return
                }
                
                val body = response.body.string()
                
                try {
                    val json = JSONObject(body)
                    val results = json.optJSONObject("results")
                    if (results != null) {
                        val muniCd = results.optString("muniCd")
                        val lv01Nm = results.optString("lv01Nm")
                        
                        isNetworkLost = false
                        processAddress(muniCd, lv01Nm)
                    } else {
                        handleApiFailure()
                    }
                } catch (e: Exception) {
                    handleApiFailure()
                }
            }
        })
    }

    private fun getBaseTownName(name: String): String {
        return name.replace(Regex("(?:[一二三四五六七八九十百]+|〇)丁目$"), "")
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

    private fun processAddress(muniCd: String, rawDistrict: String) {
        val muniData = muniMap[muniCd] ?: return
        val parts = muniData.split(",")
        if (parts.size < 4) return

        val currPref = parts[1]
        val currCity = parts[3]

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val maxDetail = prefs.getString("max_detail_level", "D") ?: "D"
        val verbosity = prefs.getString("verbosity_level", "C") ?: "C"
        val overridePref = prefs.getBoolean("override_pref", true)
        val overrideCity = prefs.getBoolean("override_city", true)
        val overrideTown = prefs.getBoolean("override_town", true)
        
        // 💡 「〜に入りました」を省略するフラグを取得
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
        val isTownChanged = lastDistrict.isNotEmpty() && currBase != lastBase
        val isChomeOnlyChanged = lastDistrict.isNotEmpty() && currBase == lastBase && currDistrict != lastDistrict

        val oldPref = lastPref
        val oldCity = lastCity
        val oldDistrict = lastDistrict

        lastPref = currPref
        lastCity = currCity
        lastDistrict = currDistrict

        if (oldPref.isEmpty()) {
            lastPref = currPref
            lastCity = currCity
            lastDistrict = currDistrict
            
            val welcomeText = buildAnnouncementText(
                pref = currPref,
                city = currCity,
                district = currDistrict,
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
            activeStartFrom = "B"
        } else if (isTownChanged && overrideTown) {
            activeStartFrom = "C"
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
                district = currDistrict,
                startFrom = activeStartFrom,
                maxDetail = effectiveMaxDetail
            )
            
            // 💡 省略フラグがONなら純粋な地名だけを喋らせる。OFFなら通常通り「に入りました」を付け足す
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
        district: String,
        startFrom: String,
        maxDetail: String
    ): String {
        val builder = StringBuilder()
        
        val includePref = getLevelRank(startFrom) <= 1 && getLevelRank(maxDetail) >= 1
        val includeCity = getLevelRank(startFrom) <= 2 && getLevelRank(maxDetail) >= 2
        val includeDistrict = getLevelRank(startFrom) <= 3 && getLevelRank(maxDetail) >= 3

        if (includePref) {
            builder.append(pref)
        }
        if (includeCity) {
            builder.append(city)
        }
        if (includeDistrict && district.isNotEmpty()) {
            builder.append(district)
        }

        if (builder.isEmpty()) {
            return when (maxDetail) {
                "A" -> pref
                "B" -> city
                "C" -> getBaseTownName(district).ifEmpty { district }
                else -> district
            }
        }

        return builder.toString()
    }

    private fun handleGpsFailure() {
        if (!isGpsLost) {
            isGpsLost = true
            speak("位置情報を取得できません。")
        }
    }

    private fun handleUnstableGps() {
        if (!isGpsUnstable) {
            isGpsUnstable = true
            speak("位置情報が不安定です。")
        }
    }

    private fun handleApiFailure() {
        if (!isNetworkLost) {
            isNetworkLost = true
            speak("地名を取得できません。")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
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
        fusedLocationClient.removeLocationUpdates(locationCallback)
        tts?.stop()
        tts?.shutdown()
        isGpsUnstable = false
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.JAPANESE
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                }

                override fun onDone(utteranceId: String?) {
                    abandonMyAudioFocus()
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    abandonMyAudioFocus()
                }
            })
            
            isTtsReady = true

            pendingStartupAnnouncement?.let {
                speak(it)
                pendingStartupAnnouncement = null
            }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady) return

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val useAudioFocus = prefs.getBoolean("audio_focus", true)

        if (useAudioFocus) {
            requestAudioFocusAndSpeak(text)
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_speech")
        }
    }

    private fun requestAudioFocusAndSpeak(text: String) {
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()

        val res = audioManager.requestAudioFocus(focusRequest!!)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            handler.postDelayed({
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_speech")
            }, 350)
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_speech")
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