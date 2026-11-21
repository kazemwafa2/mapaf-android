package af.mapaf.app.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import af.mapaf.app.App
import af.mapaf.app.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app voice guidance (like Balad's): bundled MP3 prompts (Dari/Pashto/English)
 * generated into assets/voice/, played back-to-back for distance+maneuver.
 * Falls back to the device TextToSpeech engine if a prompt file is missing,
 * and finally to a sound chime. UI cues (turn/arrival/reroute) are bundled WAVs.
 */
class VoiceEngine(private val context: Context) {

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val speaking = AtomicBoolean(false)
    private var soundPool: SoundPool? = null
    private val chimeIds = HashMap<Int, Int>()
    private var soundOn = true

    init {
        soundPool = SoundPool.Builder().setMaxStreams(2).build().also { sp ->
            chimeIds[CHIME_TURN] = sp.load(context, R.raw.chime_turn, 1)
            chimeIds[CHIME_ARRIVAL] = sp.load(context, R.raw.chime_arrival, 1)
            chimeIds[CHIME_REROUTE] = sp.load(context, R.raw.chime_reroute, 1)
            chimeIds[CHIME_WAYPOINT] = sp.load(context, R.raw.chime_waypoint, 1)
        }
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) setTtsLanguage(voiceLangCode())
        }
    }

    fun setSoundOn(on: Boolean) { soundOn = on }

    private fun voiceLangCode(): String = App.instance.settings.voiceLanguage

    private fun setTtsLanguage(lang: String): Boolean {
        val locale = when (lang) {
            "ps" -> Locale("ps", "AF")
            "en" -> Locale.US
            else -> Locale("fa", "AF")
        }
        val r = tts?.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // generic fallbacks — some devices carry fa-IR only
            val r2 = tts?.setLanguage(Locale("fa", "IR"))
            if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
                return false
            }
        }
        return true
    }

    private fun promptAsset(lang: String, key: String): String = "voice/${lang}_${key}.mp3"

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).use { true }
    } catch (_: Exception) { false }

    /** plays a sequence of prompt keys (each from assets/voice/{lang}_{key}.mp3) */
    fun playPrompts(keys: List<String>) {
        if (!soundOn || keys.isEmpty()) return
        val lang = voiceLangCode()
        val available = keys.filter { assetExists(promptAsset(lang, it)) }
        if (available.isEmpty()) {
            speakText(keys)
            return
        }
        speaking.set(true)
        playNext(available, 0, lang)
    }

    private fun playNext(keys: List<String>, idx: Int, lang: String) {
        if (idx >= keys.size) { speaking.set(false); return }
        try {
            val afd = context.assets.openFd(promptAsset(lang, keys[idx]))
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setOnCompletionListener { playNext(keys, idx + 1, lang) }
                prepare()
                start()
            }
        } catch (_: Exception) {
            speakText(keys.drop(idx))
        }
    }

    private fun speakText(keys: List<String>) {
        if (!ttsReady) { playChime(CHIME_TURN); return }
        val lang = voiceLangCode()
        setTtsLanguage(lang)
        val text = keys.joinToString(" ") { PROMPT_TEXT[lang]?.get(it) ?: "" }.trim()
        if (text.isEmpty()) { playChime(CHIME_TURN); return }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { speaking.set(false) }
            override fun onError(id: String?) { speaking.set(false) }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mapaf_nav")
    }

    fun playChime(type: Int) {
        if (!soundOn || !App.instance.settings.chimesEnabled) return
        val id = chimeIds[type] ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).toFloat()
        soundPool?.play(id, vol, vol, 1, 0, 1f)
    }

    /** distance bucket → prompt key */
    fun distanceKey(meters: Int): String = when {
        meters >= 900 -> "d1000"
        meters >= 450 -> "d500"
        meters >= 180 -> "d200"
        meters >= 80 -> "d100"
        else -> "d50"
    }

    fun release() {
        try { player?.release() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        soundPool?.release()
        speaking.set(false)
    }

    companion object {
        const val CHIME_TURN = 1
        const val CHIME_ARRIVAL = 2
        const val CHIME_REROUTE = 3
        const val CHIME_WAYPOINT = 4

        /** embedded TTS fallback texts (same wording as the generated MP3s) */
        val PROMPT_TEXT: Map<String, Map<String, String>> = mapOf(
            "fa" to mapOf(
                "d50" to "پنجاه متری", "d100" to "صد متری", "d200" to "دویست متری",
                "d500" to "پانصد متری", "d1000" to "یک کیلومتر",
                "left" to "چپ بپیچید", "right" to "راست بپیچید",
                "slight_left" to "کمی چپ بپیچید", "slight_right" to "کمی راست بپیچید",
                "sharp_left" to "تند چپ بپیچید", "sharp_right" to "تند راست بپیچید",
                "uturn" to "دور برگردید", "straight" to "مستقیم ادامه دهید",
                "roundabout" to "در چهارراه گرد وارد شوید",
                "keep_left" to "در مسیر چپ بمانید", "keep_right" to "در مسیر راست بمانید",
                "enter_highway" to "وارد بزرگراه شوید", "exit_highway" to "از بزرگراه خارج شوید",
                "start" to "مسیریابی آغاز شد. لطفاً دستورهای صوتی را دنبال کنید.",
                "reroute" to "مسیر دوباره محاسبه شد.",
                "offroute" to "شما از مسیر خارج شده اید. مسیر دوباره محاسبه می‌شود.",
                "arrival" to "به مقصد رسیدید.", "approaching" to "به مقصد نزدیک می‌شوید.",
                "tunnel" to "تونل پیش روی شماست.", "waypoint" to "به مقصد میانی رسیدید.",
                "gps_lost" to "سیگنال موقعیت‌یاب ضعیف است."
            ),
            "ps" to mapOf(
                "d50" to "پنځوس متره", "d100" to "سل متره", "d200" to "دوه سوه متره",
                "d500" to "پینځه سوه متره", "d1000" to "یو کیلومتره",
                "left" to "کیڼ ته تاو شئ", "right" to "ښې ته تاو شئ",
                "slight_left" to "لږ کیڼ ته تاو شئ", "slight_right" to "لږ ښې ته تاو شئ",
                "sharp_left" to "تند کیڼ ته تاو شئ", "sharp_right" to "تند ښې ته تاو شئ",
                "uturn" to "بېرته تاو شئ", "straight" to "مستقیم لاړ شئ",
                "roundabout" to "چاپېره سړک ته ننوځئ",
                "keep_left" to "په کیڼ لور کې پاتې شئ", "keep_right" to "په ښې لور کې پاتې شئ",
                "enter_highway" to "لویه لارې ته ننوځئ", "exit_highway" to "له لویې لارې ووځئ",
                "start" to "لارښوونه پیل شوه. لطفاً غږیز لارښوونې تعقیب کړئ.",
                "reroute" to "لار بیا محاسبه شوه.",
                "offroute" to "تاسو له لارې ووتلئ. لار بیا محاسبه کیږي.",
                "arrival" to "تاسو موخې ته ورسیدلئ.", "approaching" to "موخې ته نږدې کیږئ.",
                "tunnel" to "ټونل په وړاندې دی.", "waypoint" to "منځنۍ موخې ته ورسیدلئ.",
                "gps_lost" to "د موقعیت سیګنال ضعیف دی."
            ),
            "en" to mapOf(
                "d50" to "In fifty meters", "d100" to "In one hundred meters",
                "d200" to "In two hundred meters", "d500" to "In five hundred meters",
                "d1000" to "In one kilometer",
                "left" to "turn left", "right" to "turn right",
                "slight_left" to "bear left", "slight_right" to "bear right",
                "sharp_left" to "turn sharp left", "sharp_right" to "turn sharp right",
                "uturn" to "make a U turn", "straight" to "continue straight",
                "roundabout" to "enter the roundabout",
                "keep_left" to "keep left", "keep_right" to "keep right",
                "enter_highway" to "enter the highway", "exit_highway" to "exit the highway",
                "start" to "Navigation started. Please follow the voice instructions.",
                "reroute" to "Route recalculated.",
                "offroute" to "You are off route. Recalculating.",
                "arrival" to "You have arrived at your destination.",
                "approaching" to "Approaching your destination.",
                "tunnel" to "Tunnel ahead.", "waypoint" to "You have arrived at a waypoint.",
                "gps_lost" to "G P S signal is weak."
            )
        )
    }
}
