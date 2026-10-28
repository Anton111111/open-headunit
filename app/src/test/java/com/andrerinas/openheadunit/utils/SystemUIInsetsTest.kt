package com.andrerinas.openheadunit.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.ProjectionViewScaler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1000dp-h600dp-land-mdpi")
class SystemUIInsetsTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var child: View
    private var originalTarget = 0
    private var callbacks = 0

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        originalTarget = app.applicationInfo.targetSdkVersion
        app.applicationInfo.targetSdkVersion = 36
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        root = FrameLayout(activity)
        child = View(activity)
        root.addView(child, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        Settings(activity).apply {
            insetLeft = 3
            insetTop = 5
            insetRight = 7
            insetBottom = 11
        }
    }

    @After
    fun tearDown() {
        HeadUnitScreenConfig.onMarginsDiverged = null
        HeadUnitScreenConfig.clearAnnouncedMargins()
        HeadUnitScreenConfig.unlockResolution()
        controller.pause().stop().destroy()
        RuntimeEnvironment.getApplication().applicationInfo.targetSdkVersion = originalTarget
    }

    private fun apply(mode: Settings.FullscreenMode = Settings.FullscreenMode.NONE) {
        // These views stand in for settings/home/projection roots; only projection/home record a canvas.
        SystemUI.apply(controller.get().window, root, mode, notesCanvas = false) { callbacks++ }
    }

    private fun insets(
        status: Insets = Insets.of(0, 24, 0, 0),
        navigation: Insets = Insets.of(0, 0, 0, 48),
        cutout: Insets = Insets.of(30, 32, 0, 0),
        ime: Insets = Insets.NONE,
    ): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), status)
        .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
        .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
        .setInsets(WindowInsetsCompat.Type.ime(), ime)
        .build()

    private fun dispatch(insets: WindowInsetsCompat): WindowInsetsCompat =
        ViewCompat.dispatchApplyWindowInsets(root, insets)

    private fun assertGeometry(left: Int, top: Int, right: Int, bottom: Int, width: Int = 1000, height: Int = 600) {
        assertEquals(listOf(left, top, right, bottom), listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
        assertEquals(listOf(left, top, right, bottom), listOf(
            HeadUnitScreenConfig.systemInsetLeft, HeadUnitScreenConfig.systemInsetTop,
            HeadUnitScreenConfig.systemInsetRight, HeadUnitScreenConfig.systemInsetBottom,
        ))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        assertEquals(left, child.left)
        assertEquals(top, child.top)
        assertEquals(width - right, child.right)
        assertEquals(height - bottom, child.bottom)
        // HeadUnitScreenConfig subtracts the reported root padding from the content, not again
        // from this already-reduced child. Its canvas must match the view's drawable rectangle.
        val canvas = ProjectionCanvasPolicy.canvas(width, height, left + right, top + bottom)
        assertEquals(child.width, canvas.width)
        assertEquals(child.height, canvas.height)
    }

    @Test
    fun `enforced NONE uses the union of bars cutout and keyboard plus each manual margin once`() {
        apply()
        assertTrue(dispatch(insets()).isConsumed)
        assertGeometry(33, 37, 7, 59)

        val before = callbacks
        assertTrue(dispatch(insets(ime = Insets.of(0, 0, 0, 240))).isConsumed)
        assertEquals(before + 1, callbacks)
        assertGeometry(33, 37, 7, 251)

        dispatch(insets())
        assertGeometry(33, 37, 7, 59)
        // Repeated focus/application and inset dispatch must not accumulate manual or system padding.
        apply()
        dispatch(insets())
        dispatch(insets())
        assertGeometry(33, 37, 7, 59)
    }

    @Test
    fun `NONE follows changing visible bars and cutout edges without changing the root bounds`() {
        apply()
        dispatch(insets())
        assertGeometry(33, 37, 7, 59)
        dispatch(insets(status = Insets.NONE, navigation = Insets.of(0, 0, 42, 0), cutout = Insets.of(0, 0, 50, 0)))
        assertGeometry(3, 5, 57, 11)
        dispatch(insets(status = Insets.NONE, navigation = Insets.NONE, cutout = Insets.NONE))
        assertGeometry(3, 5, 7, 11)
    }

    @Test
    fun `API 35 target 35 also owns NONE fitting`() {
        controller.get().applicationInfo.targetSdkVersion = 35
        apply()
        assertTrue(dispatch(insets()).isConsumed)
        assertGeometry(33, 37, 7, 59)
    }

    @Test
    fun `API 35 with older target leaves NONE automatic fitting intact`() {
        controller.get().applicationInfo.targetSdkVersion = 34
        apply()
        assertFalse(dispatch(insets()).isConsumed)
        // The OS already excluded its bars from this supplied content rectangle.
        assertGeometry(3, 5, 7, 11, width = 970, height = 520)
    }

    @Test
    @Config(sdk = [34])
    fun `older runtime leaves NONE system fitting and keyboard resize intact`() {
        apply()
        assertFalse(dispatch(insets()).isConsumed)
        assertGeometry(3, 5, 7, 11, width = 970, height = 520)
        assertFalse(dispatch(insets(ime = Insets.of(0, 0, 0, 240))).isConsumed)
        assertGeometry(3, 5, 7, 11, width = 970, height = 328)
    }

    @Test
    fun `immersive still overlays bars and cutout but avoids the keyboard`() {
        apply(Settings.FullscreenMode.IMMERSIVE)
        assertTrue(dispatch(insets()).isConsumed)
        assertGeometry(3, 5, 7, 11)
        dispatch(insets(ime = Insets.of(0, 0, 0, 240)))
        assertGeometry(3, 5, 7, 251)
    }

    @Test
    fun `immersive with notch still reserves only cutout and keyboard`() {
        apply(Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH)
        dispatch(insets())
        assertGeometry(33, 37, 7, 11)
        dispatch(insets(ime = Insets.of(0, 0, 0, 240)))
        assertGeometry(33, 37, 7, 251)
    }

    @Test
    fun `status only still reserves navigation cutout and keyboard without status bar`() {
        apply(Settings.FullscreenMode.STATUS_ONLY)
        dispatch(insets(status = Insets.of(0, 80, 0, 0)))
        assertGeometry(33, 37, 7, 59)
        dispatch(insets(status = Insets.of(0, 80, 0, 0), ime = Insets.of(0, 0, 0, 240)))
        assertGeometry(33, 37, 7, 251)
    }

    // Only the app/decoder dependencies are mocked; the SurfaceView, scaler and screen config
    // are the production path that a fixed-size video buffer uses.
    private fun prepareProjection(): Settings {
        val activity = controller.get()
        val settings = Settings(activity).apply {
            screenOrientation = Settings.ScreenOrientation.LANDSCAPE
            fullscreenMode = Settings.FullscreenMode.IMMERSIVE
            viewMode = Settings.ViewMode.SURFACE
            forcedScale = true
            videoFitMode = Settings.VideoFitMode.FILL
            insetLeft = 0
            insetTop = 0
            insetRight = 0
            insetBottom = 0
        }
        val app = mock<App>()
        val component = mock<AppComponent>()
        whenever(component.settings).thenReturn(settings)
        whenever(component.videoDecoder).thenReturn(mock<VideoDecoder>())
        App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
            .set(app, lazyOf(component))
        val context = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = app
        }
        root.removeAllViews()
        child = ProjectionView(context).apply {
            ownsDecoder = false
            appliesProjectionScale = false
        }
        root.addView(child, FrameLayout.LayoutParams(-1, -1))
        activity.findViewById<View>(android.R.id.content).layout(0, 0, 1000, 600)
        layoutProjection()
        HeadUnitScreenConfig.init(activity, activity.resources.displayMetrics, settings)
        applyProjection(Settings.FullscreenMode.IMMERSIVE)
        dispatch(insets(cutout = Insets.NONE))
        HeadUnitScreenConfig.updateSurfaceDimensions(1000, 600)
        return settings
    }

    private fun applyProjection(mode: Settings.FullscreenMode) {
        SystemUI.apply(controller.get().window, root, mode) { scaleProjection() }
    }

    private fun scaleProjection() {
        ProjectionViewScaler.updateScale(
            child, HeadUnitScreenConfig.getNegotiatedWidth(), HeadUnitScreenConfig.getNegotiatedHeight(),
        )
    }

    private fun layoutProjection() {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 600)
    }

    private fun assertProjection(left: Int, top: Int, right: Int, bottom: Int) {
        assertGeometry(left, top, right, bottom)
        assertEquals(child.width, child.layoutParams.width)
        assertEquals(child.height, child.layoutParams.height)
        assertEquals(child.width, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(child.height, HeadUnitScreenConfig.getUsableHeight())
        val point = TouchCoordinateMapper.map(
            rawX = child.width.toFloat(),
            rawY = child.height.toFloat(),
            inputSurfaceWidth = child.width.toFloat(),
            inputSurfaceHeight = child.height.toFloat(),
            negotiatedWidth = HeadUnitScreenConfig.getNegotiatedWidth(),
            negotiatedHeight = HeadUnitScreenConfig.getNegotiatedHeight(),
            marginWidth = HeadUnitScreenConfig.getWidthMargin().toFloat(),
            marginHeight = HeadUnitScreenConfig.getHeightMargin().toFloat(),
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = false,
        )
        assertEquals(HeadUnitScreenConfig.getNegotiatedWidth() - HeadUnitScreenConfig.getWidthMargin(), point.x)
        assertEquals(HeadUnitScreenConfig.getNegotiatedHeight() - HeadUnitScreenConfig.getHeightMargin(), point.y)
    }

    @Test
    fun `forced surface follows local NONE override and immersive restore without renegotiation`() {
        val settings = prepareProjection()
        val hash = HeadUnitScreenConfig.computeSettingsHash(settings)
        HeadUnitScreenConfig.lockResolution()
        val videoSize = HeadUnitScreenConfig.getNegotiatedWidth() to HeadUnitScreenConfig.getNegotiatedHeight()
        applyProjection(Settings.FullscreenMode.NONE)
        // Re-entering init from a margin notification must also see the new canvas, not just the
        // explicit onInsetsChanged callback at the end of the dispatch.
        val callbackHeights = mutableListOf<Int>()
        HeadUnitScreenConfig.recordAnnouncedMargins(1, 1)
        HeadUnitScreenConfig.onMarginsDiverged = {
            scaleProjection()
            callbackHeights += HeadUnitScreenConfig.getUsableHeight()
            false
        }
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 24, 0, 48)
        assertTrue(callbackHeights.isNotEmpty())
        assertTrue(callbackHeights.all { it == 528 })
        HeadUnitScreenConfig.onMarginsDiverged = null
        assertFalse(HeadUnitScreenConfig.updateSurfaceDimensions(videoSize.first, videoSize.second))
        scaleProjection()
        assertProjection(0, 24, 0, 48)
        HeadUnitScreenConfig.updateSurfaceDimensions(1000, 528)

        applyProjection(Settings.FullscreenMode.IMMERSIVE)
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 0, 0, 0)
        assertEquals(hash, HeadUnitScreenConfig.computeSettingsHash(settings))
        assertTrue(HeadUnitScreenConfig.isResolutionLocked)
        assertEquals(videoSize, HeadUnitScreenConfig.getNegotiatedWidth() to HeadUnitScreenConfig.getNegotiatedHeight())
    }

    @Test
    fun `fixed root follows visible inset changes but preserves a surface in an unchanged window`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 24, 0, 48)
        HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
        dispatch(insets(cutout = Insets.NONE))
        assertEquals(960, child.layoutParams.width)
        assertEquals(500, child.layoutParams.height)

        dispatch(insets(navigation = Insets.of(0, 0, 42, 0), cutout = Insets.NONE))
        assertProjection(0, 24, 42, 0)
    }

    @Test
    fun `settings change keeps the bars already reported before scaler init`() {
        val settings = prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        settings.insetTop = 5
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 29, 0, 48)
        HeadUnitScreenConfig.noteWindowContent(1000, 600)
        scaleProjection()
        assertProjection(0, 29, 0, 48)
    }

    @Test
    fun `keyboard dispatch does not replace the permanent projection canvas`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        HeadUnitScreenConfig.updateSurfaceDimensions(1000, 528)
        for (ime in listOf(Insets.of(0, 0, 0, 240), Insets.of(180, 0, 0, 0))) {
            dispatch(insets(cutout = Insets.NONE, ime = ime))
            scaleProjection()
            assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
        }
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 24, 0, 48)
    }

    @Test
    fun `non projection window cannot retire or publish the projection canvas`() {
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val otherRoot = FrameLayout(other.get())
            other.get().setContentView(otherRoot)
            other.get().findViewById<View>(android.R.id.content).layout(0, 0, 400, 300)
            // Keep Activity creation/layout outside the ownership check: both windows exist
            // before the measured projection surface is installed.
            val settings = prepareProjection()
            applyProjection(Settings.FullscreenMode.NONE)
            dispatch(insets(cutout = Insets.NONE))
            HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
            SystemUI.apply(other.get().window, otherRoot, Settings.FullscreenMode.NONE, notesCanvas = false)
            ViewCompat.dispatchApplyWindowInsets(otherRoot, insets(cutout = Insets.NONE))
            otherRoot.layout(0, 0, 400, 300)
            HeadUnitScreenConfig.init(controller.get(), controller.get().resources.displayMetrics, settings)
            assertEquals(960, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(500, HeadUnitScreenConfig.getUsableHeight())
            dispatch(insets(cutout = Insets.NONE))
            assertEquals(960, child.layoutParams.width)
            assertEquals(500, child.layoutParams.height)
        } finally {
            other.pause().stop().destroy()
        }
    }
}
