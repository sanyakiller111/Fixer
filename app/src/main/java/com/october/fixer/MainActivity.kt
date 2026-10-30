package com.october.fixer

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var button: View
    private lateinit var mascot: ImageView
    private lateinit var glow: View
    private lateinit var ring: DotRingView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var tgSwitch: Switch
    private lateinit var tgSub: TextView
    private lateinit var mm2Sub: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
        }
    }

    private enum class Look { OFF, CONNECTING, ON }

    private var look: Look? = null
    private var pulse: AnimatorSet? = null
    private var connecting = false
    private var colorAnim: ValueAnimator? = null
    private var mascotAwake = false
    private var idle: ObjectAnimator? = null
    private var strokeColor = 0
    private var saturation = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        button = findViewById(R.id.toggle)
        mascot = findViewById(R.id.mascot)
        glow = findViewById(R.id.glow)
        ring = findViewById(R.id.ring)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        findViewById<View>(R.id.author).setOnClickListener {
            // straight into Telegram if it is installed, otherwise the browser (no app chooser)
            for (url in listOf(AUTHOR_TG, AUTHOR_URL)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    break
                } catch (_: Exception) {
                }
            }
        }
        findViewById<View>(R.id.donate).setOnClickListener { askDonate() }
        findViewById<View>(R.id.info).setOnClickListener { showInfo() }
        setupTelegram()
        mm2Sub = findViewById(R.id.mm2Sub)
        findViewById<View>(R.id.mm2Row).setOnClickListener { pickMm2() }
        strokeColor = getColor(R.color.stroke)
        glow.setLayerType(View.LAYER_TYPE_HARDWARE, null) // the big gradient is cached on the GPU, only alpha/scale animate
        preferSixtyHz()
        mascot.setLayerType(View.LAYER_TYPE_HARDWARE, null) // bobbing moves a cached texture
        button.setOnClickListener { onToggle() }
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false // let the click through
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (savedInstanceState == null && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) playIntro()
    }

    /**
     * Cold-start intro: the mascot drops from above into the button, squashes like jelly on landing,
     * and the screen opens from that spot as a growing circle. A copy of the mascot falls above the
     * content and becomes the real one once it settles, so only one head is ever on screen.
     */
    private fun playIntro() {
        val content = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val decor = window.decorView as ViewGroup
        // The content draws from the start, clipped to a dot: its first full frame (ring, glow) is
        // costly and would stutter right at the landing if it were held back until the reveal.
        val clip = Reveal(content)
        val head = ImageView(this)
        decor.addView(head, ViewGroup.LayoutParams(mascot.layoutParams.width, mascot.layoutParams.height))
        head.visibility = View.INVISIBLE
        decor.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                val at = IntArray(2).also { mascot.getLocationInWindow(it) }
                val from = IntArray(2).also { decor.getLocationInWindow(it) }
                // the state's look, set by render() in onResume: asleep and grey when off
                head.setImageDrawable(mascot.drawable?.constantState?.newDrawable()?.mutate() ?: getDrawable(R.drawable.mascot_icon))
                head.colorFilter = mascot.colorFilter
                head.alpha = if (FixVpnService.running) 1f else 0.8f // apply()'s target, it may still be fading
                head.setLayerType(View.LAYER_TYPE_HARDWARE, null) // squash scales a cached texture
                mascot.visibility = View.INVISIBLE // one head on screen: the falling one becomes it
                // the head is laid out at the decor's corner; translation alone places it
                head.translationX = (at[0] - from[0]).toFloat()
                head.translationY = -head.layoutParams.height.toFloat() // just above the screen
                head.pivotY = head.layoutParams.height.toFloat() // squash against the floor
                head.visibility = View.VISIBLE
                // the first frames after a cold start drop, so the fall waits them out instead of jumping
                head.animate().translationY((at[1] - from[1]).toFloat()).setDuration(INTRO_FALL_MS).setStartDelay(INTRO_DELAY_MS)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
                    .withEndAction { land(head, clip, decor) }
                    .start()
                return true
            }
        })
    }

    private fun land(head: ImageView, clip: Reveal, decor: ViewGroup) {
        buzz(VibrationEffect.EFFECT_TICK, 10)
        // One damped wave, like a water drop: it starts at full speed from the fall, squashes, rebounds
        // and settles. Two separate animations (squash, then wobble) stop dead in between and read as lag.
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_WOBBLE_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction.toDouble()
                val squash = (Math.exp(-4.5 * t) * Math.sin(3 * Math.PI * t) / 0.55).toFloat() // first peak ≈ 1
                head.scaleX = 1 + 0.2f * squash
                head.scaleY = 1 - 0.2f * squash
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // the real mascot bobbed and animated unseen meanwhile: start both from their first
                    // frame, which is what the copy shows, so the swap does not jump
                    idle?.let { i -> i.cancel(); mascot.translationY = 0f; i.start() }
                    (mascot.drawable as? AnimatedVectorDrawable)?.let { d -> d.stop(); d.start() }
                    mascot.visibility = View.VISIBLE // swap in a single frame, no cross-fade
                    decor.removeView(head)
                }
            })
            start()
        }
        val at = IntArray(2).also { mascot.getLocationInWindow(it) }
        val loc = IntArray(2).also { clip.view.getLocationInWindow(it) }
        clip.open(at[0] - loc[0] + mascot.width / 2, at[1] - loc[1] + mascot.height / 2)
    }

    /** Clips a view to a circle that grows from a point; an outline clip, so the view keeps drawing. */
    private class Reveal(val view: View) {
        private var cx = 0
        private var cy = 0
        private var r = 0f

        init {
            view.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    val k = maxOf(r, 1f).toInt() // an empty outline would not clip at all
                    o.setRoundRect(cx - k, cy - k, cx + k, cy + k, k.toFloat())
                }
            }
            view.clipToOutline = true
            view.invalidateOutline()
        }

        fun open(x: Int, y: Int) {
            cx = x
            cy = y
            val to = Math.hypot(maxOf(x, view.width - x).toDouble(), maxOf(y, view.height - y).toDouble()).toFloat()
            ValueAnimator.ofFloat(0f, to).apply {
                duration = INTRO_REVEAL_MS
                startDelay = 40
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { r = it.animatedValue as Float; view.invalidateOutline() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        view.clipToOutline = false
                        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    }
                })
                start()
            }
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // the flag only exists from 33
    override fun onStart() {
        super.onStart()
        val f = IntentFilter(FixVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        look = null // re-apply animations, they were stopped while paused
        render()
        Mm2.last?.let { mm2Sub.text = "Последний: $it" }
        pendingUpdate?.let { pendingUpdate = null; askUpdate(it) }
        checkUpdate()
    }

    /** At most every few hours; a release the user put off is offered again a day later. */
    private fun checkUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_CHECK_MS) return
        lastUpdateCheck = now
        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        val prefs = getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE)
        Thread({
            val r = Updater.check(current) ?: return@Thread
            val putOff = prefs.getInt(KEY_UPDATE_LATER, 0) == r.code && now - prefs.getLong(KEY_UPDATE_LATER_AT, 0) < UPDATE_LATER_MS
            android.util.Log.i("Fixer", "update ${r.name}: put off=$putOff")
            // the answer may land while the screen is paused (MIUI pauses it briefly at launch): keep it for onResume
            if (!putOff) runOnUiThread { if (resumed && !isFinishing) askUpdate(r) else pendingUpdate = r }
        }, "update-check").start()
    }

    private fun askUpdate(r: Updater.Release) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        val d = cardDialog(view)
        val go = view.findViewById<TextView>(R.id.updateGo)
        val notes = view.findViewById<TextView>(R.id.updateNotes)
        view.findViewById<TextView>(R.id.updateTitle).text = "Вышел Fixer ${r.name}"
        notes.text = r.notes.ifEmpty { "Настройки и прокси Telegram сохранятся." }
        var busy = false
        go.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!packageManager.canRequestPackageInstalls()) {
                // one-time switch in system settings; the dialog stays open for the second tap
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
                android.widget.Toast.makeText(this, "Разреши установку из Fixer и нажми «Обновить» ещё раз", android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            busy = true
            buzz(VibrationEffect.EFFECT_TICK, 15)
            go.text = "Загружаю…"
            Thread({
                try {
                    val apk = Updater.download(this, r) { p -> runOnUiThread { go.text = "Загружаю… $p%" } }
                    Updater.install(this, apk)
                    runOnUiThread { d.dismiss() }
                } catch (e: Exception) {
                    runOnUiThread {
                        busy = false
                        go.text = "Повторить"
                        notes.text = "Не загрузилось: ${e.message}. Проверь интернет и попробуй снова."
                    }
                }
            }, "update-download").start()
        }
        view.findViewById<View>(R.id.updateLater).setOnClickListener {
            getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_UPDATE_LATER, r.code).putLong(KEY_UPDATE_LATER_AT, System.currentTimeMillis()).apply()
            d.dismiss()
        }
        show(d, view)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        pulse?.cancel(); pulse = null
        idle?.cancel(); idle = null
        handler.removeCallbacks(glintTick)
        (mascot.drawable as? AnimatedVectorDrawable)?.stop()
        super.onPause()
    }

    private fun onToggle() {
        buzz(if (FixVpnService.running) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK, 15)
        if (FixVpnService.running) {
            connecting = false
            startService(Intent(this, FixVpnService::class.java).setAction(FixVpnService.ACTION_STOP))
        } else {
            note = null
            FixVpnService.lastError = null // the last attempt's error would cancel "connecting" in render()
            connecting = true
            render()
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, REQ_VPN) else startVpn()
        }
    }

    private fun startVpn() {
        val i = Intent(this, FixVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            connecting = false
            note = "Сервис: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            startVpn()
        } else {
            connecting = false
            note = "Разрешение на VPN не дано"
        }
        render()
    }

    private fun setupTelegram() {
        val prefs = getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE)
        tgSwitch = findViewById(R.id.tgSwitch)
        tgSub = findViewById(R.id.tgSub)
        tgSwitch.isChecked = prefs.getBoolean(FixVpnService.KEY_TG, false)
        tgSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(FixVpnService.KEY_TG, on).apply()
            onTelegramChanged()
            if (on && !FixVpnService.running) android.widget.Toast.makeText(
                this, "Прокси заработает, когда Fixer включён", android.widget.Toast.LENGTH_LONG
            ).show()
            if (on) addProxyToTelegram()
            else android.widget.Toast.makeText(
                this, "Выключи прокси в Telegram: Настройки → Данные и память → Прокси",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        findViewById<View>(R.id.tgRow).setOnClickListener { askDomain() }
        renderTelegram()
    }

    private fun tgDomain() = FixVpnService.tgDomain(this)

    private fun saveDomain(d: String) {
        val before = tgDomain()
        getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE).edit().apply {
            if (d.isEmpty() || d == FixVpnService.DEFAULT_TG_DOMAIN) remove(FixVpnService.KEY_TG_DOMAIN)
            else putString(FixVpnService.KEY_TG_DOMAIN, d)
        }.apply()
        if (tgDomain() != before && tgSwitch.isChecked) onTelegramChanged() else renderTelegram()
    }

    /** A card dialog in the app's style; [show] pops it in. */
    private fun cardDialog(view: View, keyboard: Boolean = false): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(view)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
            if (keyboard) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            decorView.setPadding(20f.dp(), 0, 20f.dp(), 0)
        }
        return d
    }

    private fun show(d: Dialog, view: View) {
        d.show()
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun showInfo() {
        val view = layoutInflater.inflate(R.layout.dialog_info, null)
        val d = cardDialog(view)
        view.findViewById<View>(R.id.infoOk).setOnClickListener { d.dismiss() }
        show(d, view)
    }

    private fun askDonate() {
        val view = layoutInflater.inflate(R.layout.dialog_donate, null)
        val d = cardDialog(view)
        view.findViewById<View>(R.id.donateGo).setOnClickListener {
            buzz(VibrationEffect.EFFECT_TICK, 15)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)))
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "Нет браузера", android.widget.Toast.LENGTH_SHORT).show()
            }
            d.dismiss()
        }
        view.findViewById<View>(R.id.donateLater).setOnClickListener { d.dismiss() }
        show(d, view)
    }

    private fun askDomain() {
        val view = layoutInflater.inflate(R.layout.dialog_domain, null)
        val d = cardDialog(view, keyboard = true)
        val input = view.findViewById<EditText>(R.id.domainInput)
        val custom = tgDomain().takeIf { it != FixVpnService.DEFAULT_TG_DOMAIN }
        input.setText(custom ?: "")
        input.setSelection(input.text.length)
        input.hint = FixVpnService.DEFAULT_TG_DOMAIN
        view.findViewById<TextView>(R.id.domainNote).text =
            if (custom == null) "Сейчас используется стандартный"
            else "Стандартный: ${FixVpnService.DEFAULT_TG_DOMAIN}"
        val note = view.findViewById<TextView>(R.id.domainNote)
        val save = {
            // accept a pasted URL or a kwsN / tg. host too
            val domain = input.text.toString().trim().lowercase()
                .removePrefix("https://").removePrefix("http://").substringBefore('/')
                .replace(Regex("^(kws\\d+|tg)\\."), "")
            if (domain.isEmpty() || DOMAIN.matches(domain)) {
                saveDomain(domain)
                d.dismiss()
            } else {
                note.text = "Это не похоже на домен: нужен вида example.com"
                note.setTextColor(getColor(R.color.text_secondary))
            }
        }
        view.findViewById<View>(R.id.domainSave).setOnClickListener { save() }
        input.setOnEditorActionListener { _, _, _ -> save(); true }
        view.findViewById<View>(R.id.domainReset).setOnClickListener {
            buzz(VibrationEffect.EFFECT_TICK, 15)
            saveDomain("")
            d.dismiss()
        }
        view.findViewById<View>(R.id.domainCancel).setOnClickListener { d.dismiss() }
        show(d, view)
        input.requestFocus()
    }

    /** Joins an MM2 server; the VPN then times it and offers the next one from a notification if it is far. */
    private fun pickMm2() {
        if (!FixVpnService.running) {
            android.widget.Toast.makeText(this, "Сначала включи Fixer, без него пинг не замерить", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // rapid joins make Roblox ask for a captcha; one pick per 20 s is plenty for a human
        val wait = (Mm2.lastPick + Mm2.COOLDOWN_MS - System.currentTimeMillis()) / 1000
        if (wait > 0) {
            android.widget.Toast.makeText(this, "Подожди $wait с, частые входы злят Roblox", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        Mm2.lastPick = System.currentTimeMillis()
        buzz(VibrationEffect.EFFECT_TICK, 15)
        mm2Sub.text = "Ищу сервер…"
        Thread({
            val s = try {
                Mm2.next()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (s == null) {
                    Mm2.lastPick = 0 // nothing was joined, so no captcha risk: no need to wait
                    mm2Sub.text = "Roblox не отдал список, попробуй позже"
                } else {
                    Mm2.arm()
                    mm2Sub.text = "Захожу, пинг придёт уведомлением"
                    joinMm2(s.id)
                }
            }
        }, "mm2-pick").start()
    }

    private fun joinMm2(jobId: String) {
        for (uri in listOf(Mm2.joinUri(jobId), Mm2.webJoinUri(jobId))) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FixVpnService.ROBLOX))
                return
            } catch (_: Exception) {
            }
        }
        android.widget.Toast.makeText(this, "Roblox не установлен", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Telegram reaches Fixer as an MTProto proxy; this link opens its "Connect proxy?" sheet. */
    private fun addProxyToTelegram() {
        val link = TgBridge.link(TgBridge.parseSecret(FixVpnService.tgSecret(this)))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Telegram не установлен", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** The VPN reads the setting at start, so a running one is restarted. */
    private fun onTelegramChanged() {
        renderTelegram()
        if (FixVpnService.running) {
            startService(Intent(this, FixVpnService::class.java).setAction(FixVpnService.ACTION_RESTART))
        }
    }

    /** The switch says it all; the line below only appears when the proxy failed to start. */
    private fun renderTelegram() {
        val err = FixVpnService.tgError?.takeIf { tgSwitch.isChecked && FixVpnService.running }
        tgSub.visibility = if (err != null) View.VISIBLE else View.GONE
        if (err != null) tgSub.text = "Прокси не запустился: $err"
    }

    private fun render() {
        val on = FixVpnService.running
        if (on || FixVpnService.lastError != null) connecting = false
        val next = when {
            on -> Look.ON
            connecting -> Look.CONNECTING
            else -> Look.OFF
        }
        if (next != look) apply(next)
        status.text = when (next) {
            Look.ON -> "Подключено"
            Look.CONNECTING -> "Подключаю…"
            Look.OFF -> "Отключено"
        }
        hint.text = when (next) {
            Look.ON -> ""
            Look.CONNECTING -> "Секунду"
            Look.OFF -> FixVpnService.lastError ?: note ?: "Нажми на кнопку, чтобы включить"
        }
        // INVISIBLE, not GONE: the layout keeps its height, so nothing jumps when switching
        hint.visibility = if (next == Look.ON) View.INVISIBLE else View.VISIBLE
        renderTelegram()
        handler.removeCallbacks(ticker)
        if (resumed) handler.postDelayed(ticker, 1000) // a broadcast while paused must not restart the ticker
    }

    private fun apply(next: Look) {
        val first = look == null
        look = next
        val on = next == Look.ON
        val accent = getColor(R.color.accent)
        val ms = if (first) 0L else 500L
        status.setTextColor(getColor(if (on) R.color.text_primary else R.color.text_secondary))
        val strokeTo = if (next == Look.OFF) getColor(R.color.stroke) else accent
        val strokeW = (if (on) 2f else 1.5f).dp()
        val core = button.background.mutate() as GradientDrawable
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            val strokeFrom = strokeColor
            val satFrom = saturation
            val satTo = if (next == Look.OFF) 0f else 1f
            val argb = ArgbEvaluator()
            duration = ms
            addUpdateListener {
                val t = it.animatedFraction
                strokeColor = argb.evaluate(t, strokeFrom, strokeTo) as Int
                saturation = satFrom + (satTo - satFrom) * t
                core.setStroke(strokeW, strokeColor)
                mascot.colorFilter = if (saturation >= 1f) null
                else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
            }
            start()
        }
        ring.setActive(next != Look.OFF)
        mascot.animate().alpha(if (next == Look.OFF) 0.8f else 1f).setDuration(ms).start()
        // asleep while off; wakes up (shades drop) as soon as connecting starts
        val awake = next != Look.OFF
        if (first || awake != mascotAwake) {
            val res = when {
                awake && first -> R.drawable.mascot_awake
                awake -> R.drawable.mascot_wake
                first -> R.drawable.mascot_asleep
                else -> R.drawable.mascot_sleep
            }
            (mascot.drawable as? AnimatedVectorDrawable)?.stop()
            val avd = getDrawable(res)!!.mutate() as AnimatedVectorDrawable
            mascot.setImageDrawable(avd)
            avd.start()
            mascotAwake = awake
            startIdle(awake)
        }
        handler.removeCallbacks(glintTick)
        if (on) handler.postDelayed(glintTick, GLINT_EVERY_MS)
        // the tap itself gives a click; a firmer one lands when the connection is actually up
        if (!first && on) buzz(VibrationEffect.EFFECT_HEAVY_CLICK, 30)

        pulse?.cancel(); pulse = null
        glow.animate().cancel()
        when (next) {
            Look.OFF -> glow.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(if (first) 0 else 450).start()
            Look.CONNECTING -> pulse = breathe(glow.alpha, 0.45f, glow.scaleX, 0.9f, 700)
            Look.ON -> {
                // burst, then a slow breathing halo
                // breathing starts exactly where the burst ends, so there is no jump
                glow.animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(if (first) 0 else 650)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .withEndAction { if (look == Look.ON && resumed) pulse = breathe(1f, 0.6f, 1.08f, 1f, 1800) }
                    .start()
            }
        }
    }

    private fun breathe(a0: Float, a1: Float, s0: Float, s1: Float, ms: Long) = AnimatorSet().apply {
        fun anim(prop: String, from: Float, to: Float) = ObjectAnimator.ofFloat(glow, prop, from, to).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = ms
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        playTogether(anim("alpha", a0, a1), anim("scaleX", s0, s1), anim("scaleY", s0, s1))
        start()
    }

    // Idle motion is a view transform on a cached layer (nearly free); looping the vector itself would
    // re-rasterise it on the CPU every frame. The glint is a short vector animation replayed now and then.
    private fun startIdle(awake: Boolean) {
        idle?.cancel()
        mascot.translationY = 0f
        idle = ObjectAnimator.ofFloat(mascot, View.TRANSLATION_Y, 0f, -(if (awake) 4f else 2.5f).dp().toFloat()).apply {
            duration = if (awake) 1600 else 2600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private val glintTick = object : Runnable {
        override fun run() {
            if (look != Look.ON || !resumed) return
            val avd = getDrawable(R.drawable.mascot_glint)!!.mutate() as AnimatedVectorDrawable
            mascot.setImageDrawable(avd)
            avd.start()
            handler.postDelayed(this, GLINT_EVERY_MS)
        }
    }

    /** Slow dots and a soft glow look the same at 60 Hz; on 120 Hz phones this halves RenderThread work. */
    private fun preferSixtyHz() {
        try {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= 30) display ?: return else windowManager.defaultDisplay
            val cur = d.mode
            val m = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId }
        } catch (_: Exception) {
        }
    }

    private fun buzz(effect: Int, fallbackMs: Long) {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            when {
                Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(effect))
                Build.VERSION.SDK_INT >= 26 -> v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
                else -> @Suppress("DEPRECATION") v.vibrate(fallbackMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun Float.dp() = (this * resources.displayMetrics.density).toInt()

    private var note: String? = null
    private var resumed = false

    companion object {
        private const val REQ_VPN = 1
        private const val KEY_UPDATE_LATER = "update_later"
        private const val KEY_UPDATE_LATER_AT = "update_later_at"
        private const val UPDATE_CHECK_MS = 6 * 3600_000L
        private const val UPDATE_LATER_MS = 24 * 3600_000L

        /** Per process: reopening the screen does not ask the server again. */
        private var lastUpdateCheck = 0L

        /** A release found while the screen was paused, shown when it is back. */
        private var pendingUpdate: Updater.Release? = null
        private const val GLINT_EVERY_MS = 3500L
        private const val INTRO_DELAY_MS = 350L
        private const val INTRO_FALL_MS = 520L
        private const val INTRO_REVEAL_MS = 650L
        private const val INTRO_WOBBLE_MS = 750L
        private const val AUTHOR_TG = "tg://resolve?domain=sanyakiller111"
        private const val AUTHOR_URL = "https://t.me/sanyakiller111"
        private val DOMAIN = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]*[a-z0-9]$")
        private const val DONATE_URL = "https://pay.cloudtips.ru/p/03c080d5"
    }
}
