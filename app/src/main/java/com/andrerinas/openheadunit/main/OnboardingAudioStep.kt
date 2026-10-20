package com.andrerinas.openheadunit.main

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.decoder.audio.AudioOutputTester
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.utils.AudioStreamTester
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wizard's sound step, as a guided check instead of a wall of switches.
 *
 * The unit's amplifier is the part that decides which streams it unmutes, and it cannot be
 * queried. The only honest test is to play something and ask. This class runs that loop:
 * play a tone, ask how it sounded, and apply the one setting that fixes each answer. The
 * technical switches stay one level down for the cases the guided path cannot guess.
 *
 * Geometry follows the approved design: two columns when there is height, one column on
 * short or portrait panels, and a compact variant that keeps the answers on one row.
 */
class OnboardingAudioStep(
    private val activity: OnboardingActivity,
    private val settings: Settings,
    private val root: View,
) {

    private enum class Stage { IDLE, ASK, SILENT, CRACKLE, DONE }

    private enum class Tier { LARGE, MEDIUM, SHORT, TINY, PORTRAIT }

    private data class StreamRow(
        val card: MaterialCardView,
        val name: TextView,
        val sub: TextView,
        val play: MaterialButton,
        val use: MaterialButton,
        val stream: Int,
        val labelRes: Int,
    )

    private val density = activity.resources.displayMetrics.density
    private val audioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val main: LinearLayout = root.findViewById(R.id.onb_audio_main)
    private val intro: LinearLayout = root.findViewById(R.id.onb_audio_intro)
    private val header: LinearLayout = root.findViewById(R.id.onb_audio_header)
    private val title: TextView = root.findViewById(R.id.onb_audio_title)
    private val desc: TextView = root.findViewById(R.id.onb_audio_desc)
    private val chip: MaterialCardView = root.findViewById(R.id.onb_audio_chip)
    private val chipText: TextView = root.findViewById(R.id.onb_audio_chip_text)
    private val introSpacer: View = root.findViewById(R.id.onb_audio_intro_spacer)
    private val moreInline: MaterialButton = root.findViewById(R.id.onb_audio_more_inline)
    private val moreBottom: MaterialButton = root.findViewById(R.id.onb_audio_more_bottom)
    private val compactReplay: MaterialButton = root.findViewById(R.id.onb_audio_compact_replay)
    private val card: MaterialCardView = root.findViewById(R.id.onb_audio_card)
    private val cardContent: LinearLayout = root.findViewById(R.id.onb_audio_card_content)

    private val idle: LinearLayout = root.findViewById(R.id.onb_audio_idle)
    private val playCircle: MaterialButton = root.findViewById(R.id.onb_audio_play_circle)
    private val playLabel: TextView = root.findViewById(R.id.onb_audio_play_label)
    private val playSub: TextView = root.findViewById(R.id.onb_audio_play_sub)

    private val ask: LinearLayout = root.findViewById(R.id.onb_audio_ask)
    private val askHeader: LinearLayout = root.findViewById(R.id.onb_audio_ask_header)
    private val askTitle: TextView = root.findViewById(R.id.onb_audio_ask_title)
    private val replay: MaterialButton = root.findViewById(R.id.onb_audio_replay)
    private val answers: LinearLayout = root.findViewById(R.id.onb_audio_answers)
    private val answerOk: MaterialButton = root.findViewById(R.id.onb_audio_answer_ok)
    private val answerSilent: MaterialButton = root.findViewById(R.id.onb_audio_answer_silent)
    private val answerCrackle: MaterialButton = root.findViewById(R.id.onb_audio_answer_crackle)

    private val silent: LinearLayout = root.findViewById(R.id.onb_audio_silent)
    private val silentTitle: TextView = root.findViewById(R.id.onb_audio_silent_title)
    private val silentHelp: TextView = root.findViewById(R.id.onb_audio_silent_help)
    private val streamRow1: LinearLayout = root.findViewById(R.id.onb_audio_stream_row_1)
    private val streamRow2: LinearLayout = root.findViewById(R.id.onb_audio_stream_row_2)

    private val crackle: LinearLayout = root.findViewById(R.id.onb_audio_crackle)
    private val crackleTitle: TextView = root.findViewById(R.id.onb_audio_crackle_title)
    private val crackleHelp: TextView = root.findViewById(R.id.onb_audio_crackle_help)
    private val crackleReplay: MaterialButton = root.findViewById(R.id.onb_audio_crackle_replay)

    private val done: LinearLayout = root.findViewById(R.id.onb_audio_done)
    private val doneTitle: TextView = root.findViewById(R.id.onb_audio_done_title)
    private val focusContext: TextView = root.findViewById(R.id.onb_audio_focus_context)
    private val focusGroup: RadioGroup = root.findViewById(R.id.onb_audio_focus_group)
    private val focusAlways: MaterialRadioButton = root.findViewById(R.id.onb_audio_focus_always)
    private val focusNever: MaterialRadioButton = root.findViewById(R.id.onb_audio_focus_never)
    private val focusAuto: MaterialRadioButton = root.findViewById(R.id.onb_audio_focus_auto)
    private val focusAlwaysCard: MaterialCardView = root.findViewById(R.id.onb_audio_focus_always_card)
    private val focusNeverCard: MaterialCardView = root.findViewById(R.id.onb_audio_focus_never_card)
    private val focusAutoCard: MaterialCardView = root.findViewById(R.id.onb_audio_focus_auto_card)
    private val focusAlwaysTitle: TextView = root.findViewById(R.id.onb_audio_focus_always_title)
    private val focusNeverTitle: TextView = root.findViewById(R.id.onb_audio_focus_never_title)
    private val focusAutoTitle: TextView = root.findViewById(R.id.onb_audio_focus_auto_title)
    private val focusAlwaysHint: TextView = root.findViewById(R.id.onb_audio_focus_always_hint)
    private val focusNeverHint: TextView = root.findViewById(R.id.onb_audio_focus_never_hint)
    private val focusAutoHint: TextView = root.findViewById(R.id.onb_audio_focus_auto_hint)
    private val status: TextView = root.findViewById(R.id.onb_audio_status)

    private val advanced: LinearLayout = root.findViewById(R.id.onb_audio_advanced)
    private val advancedBack: MaterialButton = root.findViewById(R.id.onb_audio_advanced_back)
    private val advancedTitle: TextView = root.findViewById(R.id.onb_audio_advanced_title)
    private val advancedHelp: TextView = root.findViewById(R.id.onb_audio_advanced_help)
    private val advancedTestOk: TextView = root.findViewById(R.id.onb_audio_advanced_test_ok)
    private val separateSwitch: SwitchMaterial = root.findViewById(R.id.onb_audio_separate_switch)
    private val aaudioSwitch: SwitchMaterial = root.findViewById(R.id.onb_audio_aaudio_switch)
    private val aaudioStatus: TextView = root.findViewById(R.id.onb_audio_output_status)
    private val staticSwitch: SwitchMaterial = root.findViewById(R.id.onb_audio_static_focus_switch)
    private val staticHint: TextView = root.findViewById(R.id.onb_audio_static_focus_hint)
    private val mediaLabel: TextView = root.findViewById(R.id.onb_audio_media_label)
    private val mediaButton: MaterialButton = root.findViewById(R.id.onb_audio_media_button)
    private val mediaTest: MaterialButton = root.findViewById(R.id.onb_audio_media_test)
    private val guidanceRow: LinearLayout = root.findViewById(R.id.onb_audio_guidance_row)
    private val guidanceButton: MaterialButton = root.findViewById(R.id.onb_audio_guidance_button)
    private val guidanceTest: MaterialButton = root.findViewById(R.id.onb_audio_guidance_test)
    private val systemRow: LinearLayout = root.findViewById(R.id.onb_audio_system_row)
    private val systemButton: MaterialButton = root.findViewById(R.id.onb_audio_system_button)
    private val systemTest: MaterialButton = root.findViewById(R.id.onb_audio_system_test)

    private val streamRows = listOf(
        StreamRow(
            root.findViewById(R.id.onb_audio_stream_music_card),
            root.findViewById(R.id.onb_audio_stream_music_name),
            root.findViewById(R.id.onb_audio_stream_music_sub),
            root.findViewById(R.id.onb_audio_stream_music_play),
            root.findViewById(R.id.onb_audio_stream_music_use),
            AudioManager.STREAM_MUSIC,
            R.string.audio_stream_music,
        ),
        StreamRow(
            root.findViewById(R.id.onb_audio_stream_call_card),
            root.findViewById(R.id.onb_audio_stream_call_name),
            root.findViewById(R.id.onb_audio_stream_call_sub),
            root.findViewById(R.id.onb_audio_stream_call_play),
            root.findViewById(R.id.onb_audio_stream_call_use),
            AudioManager.STREAM_VOICE_CALL,
            R.string.audio_stream_voice_call,
        ),
        StreamRow(
            root.findViewById(R.id.onb_audio_stream_notification_card),
            root.findViewById(R.id.onb_audio_stream_notification_name),
            root.findViewById(R.id.onb_audio_stream_notification_sub),
            root.findViewById(R.id.onb_audio_stream_notification_play),
            root.findViewById(R.id.onb_audio_stream_notification_use),
            AudioManager.STREAM_NOTIFICATION,
            R.string.audio_stream_notification,
        ),
        StreamRow(
            root.findViewById(R.id.onb_audio_stream_alarm_card),
            root.findViewById(R.id.onb_audio_stream_alarm_name),
            root.findViewById(R.id.onb_audio_stream_alarm_sub),
            root.findViewById(R.id.onb_audio_stream_alarm_play),
            root.findViewById(R.id.onb_audio_stream_alarm_use),
            AudioManager.STREAM_ALARM,
            R.string.audio_stream_alarm,
        ),
    )

    private var stage = Stage.IDLE
    private var tier = Tier.MEDIUM
    private var advancedOpen = false
    private var job: Job? = null
    private var binding = false
    private var fixLabel: String? = null
    private var standardFix = false
    private var crackleWasAaudio = false
    private var lastWidthDp = 0
    private var lastHeightDp = 0

    fun bind() {
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val host = activity.findViewById<View>(R.id.onb_audio_scroll)
            val w = (host.width / density).toInt()
            val h = (host.height / density).toInt()
            if (w > 0 && h > 0 && (w != lastWidthDp || h != lastHeightDp)) applyLayout()
        }
        playCircle.setOnClickListener { playTestTone() }
        compactReplay.setOnClickListener { playTestTone() }
        replay.setOnClickListener { playTestTone() }
        crackleReplay.setOnClickListener { playTestTone() }

        answerOk.setOnClickListener {
            fixLabel = null
            stage = Stage.DONE
            render()
        }
        answerSilent.setOnClickListener {
            stage = Stage.SILENT
            render()
        }
        answerCrackle.setOnClickListener { onCrackle() }

        streamRows.forEach { row ->
            row.play.setOnClickListener { AudioStreamTester.play(activity, row.stream) }
            row.use.setOnClickListener { chooseStream(row) }
        }

        moreInline.setOnClickListener { showAdvanced(true) }
        moreBottom.setOnClickListener { showAdvanced(true) }
        advancedBack.setOnClickListener { showAdvanced(false) }

        separateSwitch.isChecked = settings.separateAudioStreams
        separateSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            settings.separateAudioStreams = checked
            updateChannelRows()
        }

        aaudioSwitch.isChecked = settings.useAAudioOutput
        aaudioSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            settings.useAAudioOutput = checked
        }

        staticSwitch.isChecked = settings.staticAudioFocus
        staticSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            settings.staticAudioFocus = checked
            staticHint.isVisible = checked
        }
        staticHint.isVisible = settings.staticAudioFocus

        bindChannelRow(mediaButton, mediaTest, { activity.getString(mediaPickerTitleRes()) },
            { settings.mediaAudioStream }, { settings.mediaAudioStream = it })
        bindChannelRow(guidanceButton, guidanceTest,
            { activity.getString(R.string.audio_channel_guidance) },
            { settings.guidanceAudioStream }, { settings.guidanceAudioStream = it })
        bindChannelRow(systemButton, systemTest,
            { activity.getString(R.string.audio_channel_system) },
            { settings.systemAudioStream }, { settings.systemAudioStream = it })

        focusAlwaysCard.setOnClickListener { selectFocusMode(PlaybackFocusPolicy.Mode.ALWAYS) }
        focusNeverCard.setOnClickListener { selectFocusMode(PlaybackFocusPolicy.Mode.NEVER) }
        focusAutoCard.setOnClickListener { selectFocusMode(PlaybackFocusPolicy.Mode.AUTO) }
        listOf(focusAlwaysCard, focusNeverCard, focusAutoCard).forEach { it.isFocusable = true }

        updateFocusCards()
        updateChannelRows()
        updateAdvanced()
        render()
    }

    fun onShown() {
        root.post { applyLayout() }
        render()
    }

    fun onHidden() {
        cancelTone()
    }

    fun onPause() {
        cancelTone()
    }

    fun release() {
        cancelTone()
    }

    fun save(outState: Bundle) {
        outState.putString(KEY_STAGE, stage.name)
        outState.putBoolean(KEY_ADVANCED, advancedOpen)
        outState.putBoolean(KEY_STANDARD_FIX, standardFix)
        outState.putBoolean(KEY_CRACKLE_WAS_AAUDIO, crackleWasAaudio)
        outState.putString(KEY_FIX_LABEL, fixLabel)
    }

    fun restore(state: Bundle?) {
        if (state == null) return
        stage = runCatching { Stage.valueOf(state.getString(KEY_STAGE) ?: "") }
            .getOrDefault(Stage.IDLE)
        advancedOpen = state.getBoolean(KEY_ADVANCED, false)
        standardFix = state.getBoolean(KEY_STANDARD_FIX, false)
        crackleWasAaudio = state.getBoolean(KEY_CRACKLE_WAS_AAUDIO, false)
        fixLabel = state.getString(KEY_FIX_LABEL)
        render()
    }

    private fun onCrackle() {
        val wasAaudio = settings.useAAudioOutput
        crackleWasAaudio = wasAaudio
        if (wasAaudio) {
            settings.useAAudioOutput = false
            binding = true
            aaudioSwitch.isChecked = false
            binding = false
        }
        standardFix = true
        stage = Stage.CRACKLE
        render()
    }

    private fun selectFocusMode(mode: PlaybackFocusPolicy.Mode) {
        if (settings.playbackFocusMode != mode) {
            settings.playbackFocusMode = mode
            // Re-picking the mode is the way back from an automatic veto.
            settings.playbackFocusSelfDefeating = false
        }
        updateFocusCards()
    }

    private fun chooseStream(row: StreamRow) {
        // The guided path has one goal: make Android Auto audible. Pointing every channel at
        // the stream the amplifier unmutes is the setting that achieves it; the advanced
        // switches can split them again afterwards.
        settings.mediaAudioStream = row.stream
        settings.guidanceAudioStream = row.stream
        settings.systemAudioStream = row.stream
        settings.separateAudioStreams = false
        binding = true
        separateSwitch.isChecked = false
        binding = false
        fixLabel = activity.getString(row.labelRes)
        standardFix = false
        updateChannelRows()
        stage = Stage.DONE
        render()
    }

    private fun playTestTone() {
        if (job?.isActive == true) return
        fixLabel = null
        setToneBusy(true, activity.getString(R.string.onb_audio_testing))
        job = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { AudioOutputTester.play(settings) }
                showOutputResult(result)
                status.isVisible = false
                stage = Stage.ASK
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = activity.getString(
                    R.string.onb_audio_test_failed,
                    e.message ?: e.javaClass.simpleName,
                )
                status.isVisible = true
            } finally {
                setToneBusy(false, null)
                job = null
            }
        }
    }

    private fun setToneBusy(busy: Boolean, message: String?) {
        playCircle.isEnabled = !busy
        replay.isEnabled = !busy
        crackleReplay.isEnabled = !busy
        compactReplay.isEnabled = !busy
        if (message != null) {
            status.text = message
            status.isVisible = true
        }
    }

    private fun showOutputResult(result: AudioOutputTester.Result) {
        val backend = if (result.backend == AudioOutputTester.Backend.AAUDIO) "AAudio" else "AudioTrack"
        aaudioStatus.text = when {
            result.fallbackReason != null -> activity.getString(R.string.onb_audio_output_fallback)
            result.underruns > 0 && result.backend == AudioOutputTester.Backend.AAUDIO ->
                activity.getString(R.string.onb_audio_output_skipped_aaudio)
            result.underruns > 0 -> activity.getString(R.string.onb_audio_output_skipped)
            else -> activity.getString(R.string.onb_audio_output_ok, backend)
        }
        aaudioStatus.isVisible = true
    }

    private fun cancelTone() {
        job?.cancel()
        job = null
        setToneBusy(false, null)
        status.isVisible = false
    }

    private fun showAdvanced(open: Boolean) {
        advancedOpen = open
        if (open) updateAdvanced()
        render()
        activity.findViewById<View>(R.id.onb_audio_scroll)?.scrollTo(0, 0)
    }

    private fun updateAdvanced() {
        binding = true
        separateSwitch.isChecked = settings.separateAudioStreams
        aaudioSwitch.isChecked = settings.useAAudioOutput
        staticSwitch.isChecked = settings.staticAudioFocus
        binding = false
        staticHint.isVisible = settings.staticAudioFocus
        advancedTestOk.isVisible = stage == Stage.DONE
        updateChannelRows()
    }

    private fun updateChannelRows() {
        val separate = settings.separateAudioStreams
        guidanceRow.isVisible = separate
        systemRow.isVisible = separate
        mediaLabel.setText(
            if (separate) R.string.audio_channel_media else R.string.audio_channel_all
        )
        mediaButton.text = AudioStreamTester.label(activity, settings.mediaAudioStream)
        guidanceButton.text = AudioStreamTester.label(activity, settings.guidanceAudioStream)
        systemButton.text = AudioStreamTester.label(activity, settings.systemAudioStream)
    }

    private fun mediaPickerTitleRes(): Int =
        if (settings.separateAudioStreams) R.string.audio_channel_media
        else R.string.audio_channel_all_short

    private fun bindChannelRow(
        button: MaterialButton,
        test: MaterialButton,
        title: () -> String,
        get: () -> Int,
        set: (Int) -> Unit,
    ) {
        button.text = AudioStreamTester.label(activity, get())
        button.setOnClickListener {
            val labels = AudioStreamTester.labels(activity)
            MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
                .setTitle(activity.getString(R.string.audio_stream_pick_title, title()))
                .setSingleChoiceItems(
                    labels,
                    AudioStreamTester.indexOf(activity, get()),
                ) { dialog, which ->
                    set(AudioStreamTester.streamAt(activity, which))
                    button.text = AudioStreamTester.label(activity, get())
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        test.setOnClickListener { AudioStreamTester.play(activity, get()) }
    }

    private fun updateFocusCards() {
        val mode = settings.playbackFocusMode
        focusAlways.isChecked = mode == PlaybackFocusPolicy.Mode.ALWAYS
        focusNever.isChecked = mode == PlaybackFocusPolicy.Mode.NEVER
        focusAuto.isChecked = mode == PlaybackFocusPolicy.Mode.AUTO

        val primary = colorAttr(com.google.android.material.R.attr.colorPrimary)
        val outline = colorAttr(com.google.android.material.R.attr.colorOutlineVariant)
        val surface = ContextCompat.getColor(activity, R.color.onb_audio_option_selected)
        val surfaceVariant = ContextCompat.getColor(activity, R.color.onb_audio_option)
        listOf(
            focusAlwaysCard to (mode == PlaybackFocusPolicy.Mode.ALWAYS),
            focusNeverCard to (mode == PlaybackFocusPolicy.Mode.NEVER),
            focusAutoCard to (mode == PlaybackFocusPolicy.Mode.AUTO),
        ).forEach { (card, selected) ->
            card.strokeColor = if (selected) primary else outline
            card.setCardBackgroundColor(if (selected) surface else surfaceVariant)
        }
    }

    private fun render() {
        if (advancedOpen) {
            main.isVisible = false
            advanced.isVisible = true
            // The technical screen is taller than the viewport on most panels. Let it grow
            // inside the ScrollView instead of pinning it to the viewport like the two-column
            // main layout does.
            activity.findViewById<android.widget.ScrollView>(R.id.onb_audio_scroll)
                ?.isFillViewport = false
            root.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            root.requestLayout()
            advancedTestOk.isVisible = stage == Stage.DONE
            return
        }
        main.isVisible = true
        advanced.isVisible = false

        idle.isVisible = stage == Stage.IDLE
        ask.isVisible = stage == Stage.ASK
        silent.isVisible = stage == Stage.SILENT
        crackle.isVisible = stage == Stage.CRACKLE
        done.isVisible = stage == Stage.DONE

        if (stage == Stage.CRACKLE) {
            crackleTitle.setText(
                if (crackleWasAaudio) R.string.onb_audio_crackle_title
                else R.string.onb_audio_crackle_title_already_standard
            )
            crackleHelp.setText(
                if (crackleWasAaudio) R.string.onb_audio_crackle_help
                else R.string.onb_audio_crackle_help_already_standard
            )
        }
        chipText.text = when {
            standardFix -> activity.getString(R.string.onb_audio_chip_standard)
            !fixLabel.isNullOrEmpty() ->
                activity.getString(R.string.onb_audio_chip_through, fixLabel)
            else -> activity.getString(R.string.onb_audio_chip_ok)
        }
        applyLayout()
    }

    private fun applyLayout() {
        val host = activity.findViewById<View>(R.id.onb_audio_scroll)
        val widthPx = (if (host.width > 0) host.width else root.width)
        val heightPx = (if (host.height > 0) host.height else root.height)
        if (widthPx <= 0 || heightPx <= 0) return
        val w = (widthPx / density).toInt()
        val h = (heightPx / density).toInt()
        lastWidthDp = w
        lastHeightDp = h
        tier = detectTier(w, h)

        val columns = tier == Tier.LARGE || tier == Tier.MEDIUM
        val compactRow = (tier == Tier.SHORT || tier == Tier.TINY) && w >= 420

        // Fill the viewport only when the card is meant to fill it. In the stacked layouts
        // the content is taller than the viewport on short panels, and fillViewport would
        // pin it to the viewport instead of letting the ScrollView scroll.
        (host as? android.widget.ScrollView)?.isFillViewport = columns

        main.orientation = if (columns) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (columns) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        main.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (columns) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        intro.layoutParams = LinearLayout.LayoutParams(
            if (columns) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            if (columns) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (columns) 1f else 0f,
        )
        card.layoutParams = LinearLayout.LayoutParams(
            if (columns) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            if (columns) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (columns) 2f else 0f,
        ).apply {
            if (columns) marginStart = dp(24) else topMargin = dp(10)
        }

        introSpacer.isVisible = columns
        moreBottom.isVisible = columns
        moreInline.isVisible = !columns
        compactReplay.isVisible = tier == Tier.TINY && stage == Stage.ASK
        askHeader.isVisible = tier != Tier.TINY
        doneTitle.isVisible = tier != Tier.TINY
        silentTitle.isVisible = tier != Tier.TINY
        chip.isVisible = stage == Stage.DONE && tier != Tier.TINY &&
            !(tier == Tier.PORTRAIT && h < 600)

        val showDesc = columns ||
            (tier == Tier.PORTRAIT && (stage == Stage.IDLE || h >= 700)) ||
            (tier == Tier.SHORT && stage == Stage.IDLE && w >= 640)
        desc.isVisible = showDesc

        title.setText(
            if (tier == Tier.TINY) stageTitleRes() else R.string.onb_audio_title
        )

        val titleSize = when (tier) {
            Tier.LARGE -> 32f
            Tier.MEDIUM -> 24f
            Tier.SHORT -> 20f
            Tier.TINY -> 17f
            Tier.PORTRAIT -> 26f
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize)
        desc.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when (tier) {
                Tier.LARGE -> 16f
                Tier.MEDIUM -> 14f
                Tier.SHORT -> 13f
                Tier.TINY -> 12f
                Tier.PORTRAIT -> 14f
            },
        )
        chipText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when (tier) {
                Tier.LARGE -> 15f
                Tier.MEDIUM -> 14f
                Tier.SHORT -> 13f
                Tier.TINY -> 12f
                Tier.PORTRAIT -> 13f
            },
        )

        val cardPadding = when (tier) {
            Tier.LARGE -> 30
            Tier.MEDIUM -> 20
            Tier.SHORT -> 10
            Tier.TINY -> if (lastHeightDp < 120) 6 else 8
            Tier.PORTRAIT -> 4
        }
        cardContent.setPadding(dp(cardPadding), dp(cardPadding), dp(cardPadding), dp(cardPadding))
        cardContent.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (columns) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        card.radius = dp(
            when (tier) {
                Tier.LARGE -> 28
                Tier.MEDIUM -> 22
                Tier.SHORT -> 18
                Tier.TINY -> 14
                Tier.PORTRAIT -> 20
            }
        ).toFloat()

        // The visible state fills the card on tall screens and wraps on short ones.
        listOf(idle, ask, silent, crackle, done).forEach { state ->
            state.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (columns) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (columns) 1f else 0f,
            )
        }

        applyIdleLayout(compactRow)
        applyAskLayout(compactRow)
        applySilentLayout(compactRow, w)
        applyDoneLayout(compactRow, w)
        applyFocusTexts()
        applyAnswerTexts()
    }

    private fun applyIdleLayout(compactRow: Boolean) {
        idle.orientation = if (compactRow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        idle.gravity = if (compactRow) Gravity.CENTER_VERTICAL else Gravity.CENTER

        val circle = when {
            compactRow -> 56
            tier == Tier.LARGE -> 112
            tier == Tier.MEDIUM -> 92
            tier == Tier.PORTRAIT -> 104
            else -> 72
        }
        playCircle.layoutParams = LinearLayout.LayoutParams(dp(circle), dp(circle)).apply {
            if (compactRow) marginEnd = dp(14)
        }
        playCircle.cornerRadius = dp(circle / 2)
        playCircle.iconSize = dp((circle * 0.4f).toInt())

        playLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                compactRow -> 16f
                tier == Tier.LARGE -> 22f
                tier == Tier.MEDIUM -> 19f
                tier == Tier.PORTRAIT -> 20f
                else -> 17f
            },
        )
        playSub.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (compactRow) 12f else if (tier == Tier.LARGE) 15f else 14f,
        )
        playSub.isVisible = !compactRow &&
            !(tier == Tier.SHORT && lastHeightDp < 360 && stage != Stage.IDLE)
        playLabel.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { if (compactRow) marginEnd = 0 }
    }

    private fun applyAskLayout(compactRow: Boolean) {
        askTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                tier == Tier.LARGE -> 22f
                tier == Tier.MEDIUM -> 19f
                tier == Tier.SHORT -> 17f
                tier == Tier.TINY -> 14f
                else -> 19f
            },
        )
        answers.orientation = if (compactRow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        (answers.layoutParams as LinearLayout.LayoutParams).topMargin =
            if (compactRow) 0 else if (tier == Tier.PORTRAIT) dp(4) else dp(12)
        val buttons = listOf(answerOk, answerSilent, answerCrackle)
        val answerSize = when {
            tier == Tier.LARGE -> 16f
            tier == Tier.MEDIUM -> 15f
            tier == Tier.SHORT || tier == Tier.TINY -> 13f
            else -> 15f
        }
        buttons.forEachIndexed { index, button ->
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, answerSize)
            button.layoutParams = LinearLayout.LayoutParams(
                if (compactRow) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (compactRow) 1f else 0f,
            ).apply {
                if (compactRow) {
                    if (index > 0) marginStart = dp(6)
                } else if (index > 0) {
                    topMargin = dp(8)
                }
            }
            button.minHeight = dp(
                when {
                    compactRow -> 48
                    tier == Tier.LARGE -> 64
                    tier == Tier.MEDIUM -> 56
                    tier == Tier.PORTRAIT -> 46
                    else -> 50
                }
            )
            button.insetTop = 0
            button.insetBottom = 0
            button.iconSize = dp(if (compactRow) 20 else 24)
            button.iconPadding = dp(if (compactRow) 6 else 10)
        }
    }

    private fun applyAnswerTexts() {
        val short = tier == Tier.SHORT || tier == Tier.TINY
        answerOk.setText(
            if (short) R.string.onb_audio_answer_ok_short else R.string.onb_audio_answer_ok
        )
        answerSilent.setText(
            if (short) R.string.onb_audio_answer_silent_short else R.string.onb_audio_answer_silent
        )
        answerCrackle.setText(
            if (short) R.string.onb_audio_answer_crackle_short else R.string.onb_audio_answer_crackle
        )
    }

    private fun applySilentLayout(compactRow: Boolean, widthDp: Int) {
        silentHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactRow) 12f else 14f)
        silentTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactRow) 17f else 20f)
        val twoColumns = when {
            tier == Tier.LARGE || tier == Tier.MEDIUM -> widthDp >= 900
            else -> compactRow && widthDp >= 460
        }
        streamRow1.orientation = if (twoColumns) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        streamRow2.orientation = if (twoColumns) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        streamRows.forEachIndexed { index, row ->
            row.name.text = activity.getString(row.labelRes)
            row.sub.text = if (row.stream == AudioManager.STREAM_MUSIC) {
                activity.getString(R.string.onb_audio_silent_recommended)
            } else {
                ""
            }
            val nameSize = when {
                tier == Tier.LARGE -> 18f
                tier == Tier.MEDIUM -> 16f
                tier == Tier.SHORT || tier == Tier.TINY -> 14f
                else -> 15f
            }
            row.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSize)
            row.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            row.sub.isVisible = tier == Tier.LARGE || tier == Tier.MEDIUM || tier == Tier.PORTRAIT
            row.use.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactRow) 12f else 14f)
            val inner = row.card.getChildAt(0)
            if (inner != null) {
                val vertical = if (compactRow) 0 else dp(6)
                inner.setPadding(inner.paddingLeft, vertical, inner.paddingRight, vertical)
            }
            row.play.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            row.use.minHeight = dp(44)
            row.card.layoutParams = LinearLayout.LayoutParams(
                if (twoColumns) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (twoColumns) 1f else 0f,
            ).apply {
                when {
                    twoColumns -> {
                        if (index % 2 == 0) marginEnd = dp(4) else marginStart = dp(4)
                        if (index >= 2) topMargin = dp(8)
                    }
                    else -> {
                        if (index > 0) topMargin = dp(8)
                    }
                }
            }
        }
    }

    private fun applyDoneLayout(compactRow: Boolean, widthDp: Int) {
        val veryTiny = lastHeightDp < 120
        focusContext.isVisible = !veryTiny
        focusContext.setText(
            if (compactRow) R.string.onb_audio_done_context_short
            else R.string.onb_audio_done_context
        )
        focusContext.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactRow) 12f else 14f)
        doneTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactRow) 17f else 20f)

        val rowOfOptions = compactRow && widthDp >= 420
        focusGroup.orientation = if (rowOfOptions) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        listOf(focusAlwaysCard, focusNeverCard, focusAutoCard).forEachIndexed { index, option ->
            option.layoutParams = LinearLayout.LayoutParams(
                if (rowOfOptions) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (rowOfOptions) 1f else 0f,
            ).apply {
                if (rowOfOptions) {
                    if (index > 0) marginStart = dp(6)
                } else if (index > 0) {
                    topMargin = dp(8)
                }
            }
            val inner = option.getChildAt(0)
            if (inner != null && veryTiny) {
                inner.setPadding(inner.paddingLeft, dp(4), inner.paddingRight, dp(4))
            }
        }

        val titleSize = if (compactRow) 13f else 15f
        focusAlwaysTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize)
        focusNeverTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize)
        focusAutoTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize)
        listOf(focusAlwaysHint, focusNeverHint, focusAutoHint).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactRow) 11f else 12f)
        }
    }

    private fun applyFocusTexts() {
        val tiny = tier == Tier.TINY
        val short = tier == Tier.SHORT || tiny
        focusAlwaysTitle.setText(
            if (short) R.string.onb_audio_focus_always_short else R.string.onb_audio_focus_always
        )
        focusNeverTitle.setText(R.string.onb_audio_focus_never)
        focusAutoTitle.setText(
            if (short) R.string.onb_audio_focus_auto_short else R.string.onb_audio_focus_auto
        )
        focusAlwaysHint.setText(
            if (short) R.string.onb_audio_focus_always_hint_short
            else R.string.onb_audio_focus_always_hint
        )
        focusNeverHint.setText(
            if (short) R.string.onb_audio_focus_never_hint_short
            else R.string.onb_audio_focus_never_hint
        )
        focusAutoHint.setText(
            if (tiny) R.string.onb_audio_focus_recommended
            else if (short) R.string.onb_audio_focus_auto_hint_short
            else R.string.onb_audio_focus_auto_hint
        )
        focusAlwaysHint.isVisible = !short
        focusNeverHint.isVisible = !short
        focusAutoHint.isVisible = true
    }

    private fun stageTitleRes(): Int = when (stage) {
        Stage.IDLE -> R.string.onb_audio_title
        Stage.ASK -> R.string.onb_audio_question
        Stage.SILENT -> R.string.onb_audio_silent_title
        Stage.CRACKLE -> R.string.onb_audio_crackle_title
        Stage.DONE -> R.string.onb_audio_done_title
    }

    private fun detectTier(widthDp: Int, heightDp: Int): Tier {
        if (activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT && widthDp < 900
        ) {
            return Tier.PORTRAIT
        }
        return when {
            heightDp >= 420 -> Tier.LARGE
            heightDp >= 300 -> Tier.MEDIUM
            heightDp >= 220 -> Tier.SHORT
            else -> Tier.TINY
        }
    }

    private fun colorAttr(attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(activity, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val KEY_STAGE = "onb_audio_stage"
        private const val KEY_ADVANCED = "onb_audio_advanced"
        private const val KEY_STANDARD_FIX = "onb_audio_standard_fix"
        private const val KEY_CRACKLE_WAS_AAUDIO = "onb_audio_crackle_was_aaudio"
        private const val KEY_FIX_LABEL = "onb_audio_fix_label"
    }
}
