package com.andrerinas.openheadunit.utils

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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
}
