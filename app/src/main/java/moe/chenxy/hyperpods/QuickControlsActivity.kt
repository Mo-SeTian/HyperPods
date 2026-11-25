package moe.chenxy.hyperpods

import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.addCallback
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnPreDraw
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.ui.AppTheme
import moe.chenxy.hyperpods.ui.QuickControls
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import java.util.function.Consumer
import kotlin.math.roundToInt

class QuickControlsActivity : ComponentActivity() {
    private val blurListener = Consumer<Boolean>(::updateBackground)
    private var popupReady = false
    private var closing = false
    private var progress = 0f
    private var motion: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the popup's own animation should run, including when we open over the launcher.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        val address = intent.getStringExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS)
        if (intent.action != HyperPodsAction.ACTION_SHOW_QUICK_CONTROLS || address == null ||
            !BluetoothAdapter.checkBluetoothAddress(address)) {
            finish()
            return
        }
        setContent {
            val maxHeight = (resources.configuration.screenHeightDp - 40).coerceAtLeast(120).dp
            AppTheme(colorMode = 1) {
                QuickControls(address, maxHeight, canSend = { !closing }) {
                    dismiss {
                        startActivity(Intent(this, MainActivity::class.java).setAction(HyperPodsAction.ACTION_SHOW_PODS_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        val screenWidth = resources.configuration.screenWidthDp
        val horizontalMargins = if (screenWidth < 360) 48 else 80
        val width = (screenWidth - horizontalMargins).coerceIn(1, 324)
        window.setLayout(pixels(width), WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setFinishOnTouchOutside(true)
        window.setWindowAnimations(0)
        updateBackground(windowManager.isCrossWindowBlurEnabled)
        popupReady = true
        onBackPressedDispatcher.addCallback(this) { finish() }
        if (ValueAnimator.areAnimatorsEnabled()) {
            renderProgress(0f)
            window.decorView.doOnPreDraw { if (!closing) animateTo(1f, 200) }
        } else renderProgress(1f)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = closing || super.dispatchTouchEvent(event)

    override fun finish() = dismiss()

    private fun dismiss(onClosed: () -> Unit = {}) {
        if (!popupReady) {
            super.finish()
            return
        }
        if (closing) return
        closing = true
        animateTo(0f, 150) {
            onClosed()
            super.finish()
        }
    }

    private fun animateTo(target: Float, durationMs: Long, onEnd: () -> Unit = {}) {
        cancelMotion()
        if (!ValueAnimator.areAnimatorsEnabled() || progress == target) {
            renderProgress(target)
            onEnd()
            return
        }
        val animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = durationMs
            interpolator = if (target == 1f) PathInterpolator(.2f, 0f, 0f, 1f) else PathInterpolator(.4f, 0f, 1f, 1f)
            addUpdateListener { renderProgress(it.animatedValue as Float) }
            doOnEnd {
                if (motion === it) {
                    motion = null
                    onEnd()
                }
            }
        }
        motion = animator
        animator.start()
    }

    private fun renderProgress(value: Float) {
        progress = value
        val scale = .96f + .04f * value
        window.decorView.scaleX = scale
        window.decorView.scaleY = scale
        window.attributes = window.attributes.apply {
            alpha = value
            dimAmount = .18f * value
        }
    }

    private fun cancelMotion() {
        val active = motion
        motion = null
        active?.cancel()
    }

    override fun onDestroy() {
        cancelMotion()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val address = intent.getStringExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS)
        if (!closing && intent.action == HyperPodsAction.ACTION_SHOW_QUICK_CONTROLS && address != null &&
            BluetoothAdapter.checkBluetoothAddress(address) &&
            address != this.intent.getStringExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS)) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing) windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
    }

    override fun onStop() {
        windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        super.onStop()
    }

    private fun pixels(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun updateBackground(blur: Boolean) {
        // A light neutral scrim keeps the slate lettering readable in either system theme.
        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = pixels(26).toFloat()
            setColor(if (blur) Color.argb(170, 246, 247, 252) else Color.rgb(246, 247, 252))
            setStroke(pixels(1), Color.argb(96, 255, 255, 255))
        })
        window.setBackgroundBlurRadius(if (blur) pixels(30).coerceAtMost(120) else 0)
    }

    companion object {
        fun notificationIntent(address: String): Intent = Intent(HyperPodsAction.ACTION_SHOW_QUICK_CONTROLS)
            .setClassName(BuildConfig.APPLICATION_ID, QuickControlsActivity::class.java.name)
            .setIdentifier(address)
            .putExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
