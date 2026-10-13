package com.testersfree.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.UUID
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var api: SupabaseApi
    private lateinit var ads: AdsManager
    private var adsReady = false
    private val testPrefs by lazy { getSharedPreferences("pending_test", MODE_PRIVATE) }
    private var testPromptShown = false
    private var screenGeneration = 0
    private var selectedIconBytes: ByteArray? = null
    private var selectedIconPreview: ImageView? = null
    private var iconPickerCallback: (() -> Unit)? = null
    private val ICON_PICK_REQUEST = 1201

    override fun onResume() {
        super.onResume()
        if (::api.isInitialized && testPrefs.contains("session_id") && !testPromptShown) {
            testPromptShown = true
            // Show explicit verification; do not automatically credit a reward.
            android.os.Handler(mainLooper).postDelayed({ if (!isFinishing) promptPendingTest() }, 400)
        }
    }

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun observedForegroundSeconds(pkg: String, started: Long): Int {
        val now = System.currentTimeMillis()
        if (pkg.isBlank() || started <= 0 || started > now) return 0
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(started, now)
        val event = android.app.usage.UsageEvents.Event()
        val activeActivities = mutableSetOf<String>()
        var began: Long? = null
        var elapsed = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val timestamp = event.timeStamp.coerceIn(started, now)
            // When any other package comes to the foreground, stop counting.
            if (event.packageName != pkg && event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                began?.let { elapsed += (timestamp - it).coerceAtLeast(0L) }
                began = null
                activeActivities.clear()
                continue
            }
            if (event.packageName != pkg) continue
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (activeActivities.isEmpty()) began = timestamp
                    activeActivities.add(event.className ?: "")
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                    activeActivities.remove(event.className ?: "")
                    if (activeActivities.isEmpty()) {
                        began?.let { elapsed += (timestamp - it).coerceAtLeast(0L) }
                        began = null
                    }
                }
            }
        }
        began?.let { elapsed += (now - it).coerceAtLeast(0L) }
        return (elapsed / 1000L).coerceIn(0L, 86400L).toInt()
    }

    private fun promptPendingTest() {
        val session = testPrefs.getString("session_id", null) ?: run { testPromptShown = false; return }
        val pkg = testPrefs.getString("package_name", "") ?: ""
        if (!usageAccessGranted()) {
            AlertDialog.Builder(this).setTitle(tr("Permiso de uso", "Usage access"))
                .setMessage(tr("Para comprobar los 30 segundos de uso, habilita el acceso a estadísticas de uso para 12TestersFree.", "Enable usage access for 12TestersFree to check 30 seconds of use."))
                .setPositiveButton(tr("Abrir ajustes", "Open settings")) { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .setNegativeButton(tr("Más tarde", "Later")) { _, _ -> testPromptShown = false }.show()
            return
        }
        val seconds = observedForegroundSeconds(pkg, testPrefs.getLong("started_at", System.currentTimeMillis()))
        AlertDialog.Builder(this).setTitle(tr("Comprobar prueba", "Check test"))
            .setMessage(tr("Tiempo de uso detectado: $seconds segundos. Se requieren 30. La recompensa queda pendiente de verificación.", "Detected foreground use: $seconds seconds. 30 required. Reward awaits verification."))
            .setPositiveButton(tr("Enviar", "Submit")) { _, _ ->
                if (seconds < 30) {
                    Toast.makeText(this, tr("Faltan segundos de uso", "Not enough usage time"), Toast.LENGTH_LONG).show()
                    testPromptShown = false
                } else call({ api.submitTest(session, seconds) }, { _ ->
                    testPrefs.edit().clear().apply()
                    testPromptShown = false
                    showScreen(0)
                })
            }
            .setNegativeButton(tr("Más tarde", "Later")) { _, _ -> testPromptShown = false }.show()
    }

    private fun beginTest(listingId: String, pkg: String, url: String) {
        if (!usageAccessGranted()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, tr("Activa acceso de uso y vuelve a pulsar Probar", "Enable usage access and tap Test again"), Toast.LENGTH_LONG).show()
            return
        }
        call({
            val id = api.startTest(listingId)
            id
        }, { id ->
            testPrefs.edit().putString("session_id", id).putString("package_name", pkg)
                .putLong("started_at", System.currentTimeMillis()).apply()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, tr("No hay navegador disponible", "No browser available"), Toast.LENGTH_LONG).show()
            }
        })
    }

    private val blue = Color.rgb(18, 112, 205)
    private lateinit var body: LinearLayout
    private fun spanish() = resources.configuration.locales[0].language == "es"
    private fun tr(es: String, en: String) = if (spanish()) es else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = SupabaseApi(this)
        ads = AdsManager(this)
        ads.initialize {
            adsReady = true
            if (!isFinishing && !isDestroyed)
                showScreen(if (api.isSignedIn()) 3 else 0)
        }
        showScreen(if (api.isSignedIn()) 3 else 0)
    }

    override fun onDestroy() {
        if (::ads.isInitialized) ads.destroy()
        super.onDestroy()
    }


    private fun label(text: String, size: Float = 17f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(35, 51, 70))
        setPadding(0, 12, 0, 12)
        if (bold) setTypeface(null, Typeface.BOLD)
    }


    private fun actionButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun call(action: () -> String, done: (String) -> Unit = { showScreen(1) }) {
        Thread {
            try {
                val result = action()
                runOnUiThread { Toast.makeText(this, tr("Operación completada", "Done"), Toast.LENGTH_SHORT).show(); done(result) }
            } catch (e: Exception) {
                runOnUiThread { AlertDialog.Builder(this).setMessage(e.message ?: "Error").setPositiveButton("OK", null).show() }
            }
        }.start()
    }

    private fun packageFromPlayUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url.trim())
            if (uri.scheme != "https" || uri.host != "play.google.com") null
            else uri.getQueryParameter("id")?.trim()?.takeIf {
                it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$"))
            }
        } catch (_: Exception) { null }
    }

    private fun prepareIcon(uri: Uri): ByteArray {
        val bitmap = contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException(tr("No se pudo leer el logo", "Could not read logo"))

        val maxSide = 512
        val ratio = minOf(
            1f,
            maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
        )
        val width = maxOf(1, (bitmap.width * ratio).toInt())
        val height = maxOf(1, (bitmap.height * ratio).toInt())
        val scaled = if (width != bitmap.width || height != bitmap.height)
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        else bitmap

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP, 88, out)
        val bytes = out.toByteArray()

        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        if (bytes.size > 1024 * 1024)
            throw IllegalStateException(tr("El logo es demasiado grande", "Logo is too large"))

        return bytes
    }

    private fun chooseIcon(callback: () -> Unit) {
        iconPickerCallback = callback
        startActivityForResult(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            ICON_PICK_REQUEST
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ICON_PICK_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                selectedIconBytes = prepareIcon(uri)
                selectedIconPreview?.setImageURI(uri)
                iconPickerCallback?.invoke()
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: tr("Logo no válido", "Invalid logo"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadIcon(path: String) {
        if (path.isBlank() || path == "null") return
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val size = (72 * resources.displayMetrics.density).toInt()
        body.addView(image, LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = (8 * resources.displayMetrics.density).toInt()
        })
        Thread {
            try {
                val bitmap = URL(api.iconUrl(path)).openStream().use { BitmapFactory.decodeStream(it) }
                runOnUiThread { if (!isFinishing) image.setImageBitmap(bitmap) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(tr("Confirmar", "Confirm")) { _, _ -> action() }.show()
    }

    private fun authDialog(signUp: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }

        val email = EditText(this).apply {
            hint = tr("Correo electrónico", "Email")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }

        val password = EditText(this).apply {
            hint = tr("Contraseña", "Password")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        layout.addView(email)
        layout.addView(password)

        val confirmPassword = if (signUp) {
            EditText(this).apply {
                hint = tr("Repite la contraseña", "Repeat password")
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                layout.addView(this)
            }
        } else null

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (signUp)
                    tr("Crear cuenta", "Create account")
                else
                    tr("Iniciar sesión", "Sign in")
            )
            .setView(layout)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(
                if (signUp)
                    tr("Crear cuenta", "Create account")
                else
                    tr("Entrar", "Sign in"),
                null
            )
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mail = email.text.toString().trim()
                val pass = password.text.toString()

                if (mail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
                    email.error = tr("Introduce un correo válido", "Enter a valid email")
                    return@setOnClickListener
                }

                if (pass.length < 6) {
                    password.error = tr(
                        "La contraseña debe tener al menos 6 caracteres",
                        "Password must be at least 6 characters"
                    )
                    return@setOnClickListener
                }

                if (signUp && pass != confirmPassword?.text.toString()) {
                    confirmPassword?.error = tr(
                        "Las contraseñas no coinciden",
                        "Passwords do not match"
                    )
                    return@setOnClickListener
                }

                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false

                Thread {
                    try {
                        if (signUp) {
                            val signedIn = api.signUp(mail, pass)

                            runOnUiThread {
                                if (signedIn) {
                                    dialog.dismiss()
                                    Toast.makeText(
                                        this,
                                        tr("Cuenta creada", "Account created"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    showScreen(3)
                                } else {
                                    dialog.dismiss()
                                    AlertDialog.Builder(this)
                                        .setMessage(
                                            tr(
                                                "Cuenta creada. Revisa tu correo y confirma tu dirección. Después inicia sesión.",
                                                "Account created. Check your email and confirm your address, then sign in."
                                            )
                                        )
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                        } else {
                            api.signIn(mail, pass)

                            runOnUiThread {
                                dialog.dismiss()
                                showScreen(3)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            button.isEnabled = true

                            val raw = e.message ?: ""
                            val message = when {
                                raw.contains("Invalid login credentials", true) ->
                                    tr(
                                        "Correo o contraseña incorrectos.",
                                        "Incorrect email or password."
                                    )

                                raw.contains("Email not confirmed", true) ->
                                    tr(
                                        "Confirma primero tu correo electrónico.",
                                        "Confirm your email first."
                                    )

                                raw.contains("User already registered", true) ->
                                    tr(
                                        "Ya existe una cuenta con ese correo.",
                                        "An account with that email already exists."
                                    )

                                else -> raw.ifBlank {
                                    tr(
                                        "No se pudo completar la operación.",
                                        "Could not complete the operation."
                                    )
                                }
                            }

                            AlertDialog.Builder(this)
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun addAppDialog() {
        selectedIconBytes = null

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }

        val url = EditText(this).apply {
            hint = "Google Play URL"
            setSingleLine(true)
        }
        val name = EditText(this).apply {
            hint = tr("Nombre", "Name")
            setSingleLine(true)
        }
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = (100 * resources.displayMetrics.density).toInt()
        }
        selectedIconPreview = preview

        val logoButton = actionButton(tr("Seleccionar logo", "Select logo")) {
            chooseIcon {
                Toast.makeText(this, tr("Logo seleccionado", "Logo selected"), Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(url)
        layout.addView(name)
        layout.addView(preview)
        layout.addView(logoButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Añadir aplicación", "Add app"))
            .setView(layout)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(tr("Guardar", "Save"), null)
            .create()

        dialog.setOnDismissListener {
            selectedIconPreview = null
            iconPickerCallback = null
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val playUrl = url.text.toString().trim()
                val appName = name.text.toString().trim()
                val pkg = packageFromPlayUrl(playUrl)
                val icon = selectedIconBytes

                if (pkg == null) {
                    url.error = tr("URL de Google Play no válida", "Invalid Google Play URL")
                    return@setOnClickListener
                }
                if (appName.isBlank()) {
                    name.error = tr("Introduce el nombre", "Enter the name")
                    return@setOnClickListener
                }
                if (icon == null) {
                    Toast.makeText(this, tr("Selecciona el logo de la aplicación", "Select the app logo"), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false

                Thread {
                    try {
                        val uid = api.currentUserId() ?: throw IllegalStateException(tr("Inicia sesión", "Sign in first"))
                        val iconPath = "$uid/${UUID.randomUUID()}.webp"
                        api.uploadIcon(iconPath, icon)
                        api.addApp(pkg, appName, playUrl, iconPath)

                        runOnUiThread {
                            dialog.dismiss()
                            showScreen(1)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            save.isEnabled = true
                            AlertDialog.Builder(this).setMessage(e.message ?: "Error").setPositiveButton("OK", null).show()
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun editAppDialog(item: org.json.JSONObject) {
        val active = !item.optString("active_listing_id").let { it == "null" || it.isBlank() }
        selectedIconBytes = null

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }

        val name = EditText(this).apply {
            hint = tr("Nombre", "Name")
            setText(item.optString("app_name"))
            setSingleLine(true)
            isEnabled = !active
        }
        val url = EditText(this).apply {
            hint = "Google Play URL"
            setText(item.optString("play_test_url"))
            setSingleLine(true)
            isEnabled = !active
        }
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = (100 * resources.displayMetrics.density).toInt()
        }
        selectedIconPreview = preview

        val currentIcon = item.optString("icon_path")

        val logoButton = actionButton(tr("Cambiar logo", "Change logo")) {
            chooseIcon {
                Toast.makeText(this, tr("Nuevo logo seleccionado", "New logo selected"), Toast.LENGTH_SHORT).show()
            }
        }.apply { isEnabled = !active }

        layout.addView(name)
        layout.addView(url)
        layout.addView(preview)
        layout.addView(logoButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Editar aplicación", "Edit app"))
            .setView(layout)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(tr("Guardar", "Save"), null)
            .create()

        dialog.setOnDismissListener {
            selectedIconPreview = null
            iconPickerCallback = null
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = !active
                setOnClickListener {
                    val appName = name.text.toString().trim()
                    val playUrl = url.text.toString().trim()
                    val pkg = packageFromPlayUrl(playUrl)

                    if (pkg == null) {
                        url.error = tr("URL de Google Play no válida", "Invalid Google Play URL")
                        return@setOnClickListener
                    }
                    if (appName.isBlank()) {
                        name.error = tr("Introduce el nombre", "Enter the name")
                        return@setOnClickListener
                    }

                    isEnabled = false

                    Thread {
                        try {
                            var iconPath = currentIcon
                            selectedIconBytes?.let { bytes ->
                                val uid = api.currentUserId() ?: throw IllegalStateException(tr("Inicia sesión", "Sign in first"))
                                iconPath = "$uid/${UUID.randomUUID()}.webp"
                                api.uploadIcon(iconPath, bytes)
                            }

                            if (iconPath.isBlank() || iconPath == "null")
                                throw IllegalStateException(tr("Selecciona un logo", "Select a logo"))

                            api.editApp(item.getString("app_id"), appName, playUrl, iconPath)

                            runOnUiThread {
                                dialog.dismiss()
                                showScreen(1)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                isEnabled = true
                                AlertDialog.Builder(this@MainActivity).setMessage(e.message ?: "Error").setPositiveButton("OK", null).show()
                            }
                        }
                    }.start()
                }
            }
        }

        dialog.show()
    }

    private fun loadLibraryUsage(view: TextView) {
        Thread {
            try {
                val apps = api.library()
                val profile = api.profile()
                val slots = if (profile.length() > 0) profile.getJSONObject(0).optInt("library_slots", 2) else 2
                val used = apps.length()
                runOnUiThread {
                    if (!isFinishing)
                        view.text = tr("Espacios: $used/$slots utilizados", "Slots: $used/$slots used")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing)
                        view.text = tr("Espacios: no disponibles", "Slots: unavailable")
                }
            }
        }.start()
    }

    private fun loadRows(page: Int, search: String = "") {
        if (!api.isSignedIn()) {
            body.addView(label(tr("Inicia sesión para ver datos reales.", "Sign in to see live data.")))
            return
        }
        val target = body
        val generation = screenGeneration
        val status = label(tr("Cargando…", "Loading…"))
        target.addView(status)
        Thread {
            try {
                val rows = when(page) { 0 -> api.tests(search); 1 -> api.library(); else -> api.profile() }
                runOnUiThread {
                    if (generation != screenGeneration || target !== body || isFinishing) return@runOnUiThread
                    target.removeView(status)
                    if (rows.length() == 0) target.addView(label(tr("Sin resultados", "No results")))
                    for (i in 0 until rows.length()) {
                        val item = rows.getJSONObject(i)
                        val line = when(page) {
                            0 -> item.optString("app_name")
                            1 -> item.optString("app_name") + " · " + item.optString("package_name")
                            else -> "${item.optInt("normal_coins")} monedas · ${item.optInt("boost_coins")} boost · ${item.optInt("library_slots")} espacios"
                        }
                        if (page == 0 || page == 1) loadIcon(item.optString("icon_path"))
                        target.addView(label(line, 17f, true))
                        if (page == 0) {
                            val expiry = item.optString("expires_at")
                            if (expiry.isNotBlank()) target.addView(label(tr("Disponible hasta: ", "Available until: ") + expiry.take(16).replace('T', ' '), 13f))
                            val listingId = item.optString("listing_id")
                            val pkg = item.optString("package_name")
                            val url = item.optString("play_test_url")
                            target.addView(actionButton(tr("Probar aplicación", "Test application")) {
                                beginTest(listingId, pkg, url)
                            })
                        }
                        if (page == 1) {
                            target.addView(actionButton(tr("Editar aplicación", "Edit app")) { editAppDialog(item) })
                            val appId = item.optString("app_id")
                            val activeId = item.optString("active_listing_id").takeUnless { it == "null" || it.isBlank() }
                            if (activeId == null) {
                                target.addView(actionButton(tr("Publicar · 60 monedas", "Publish · 60 coins")) {
                                    confirm(tr("¿Publicar durante 72 horas por 60 monedas?", "Publish for 72 hours for 60 coins?")) {
                                        call({ api.publish(appId) })
                                    }
                                })
                                target.addView(actionButton(tr("Eliminar de biblioteca", "Remove from library")) {
                                    confirm(tr("¿Eliminar esta aplicación de la biblioteca?", "Remove this app from library?")) {
                                        call({ api.deleteApp(appId) })
                                    }
                                })
                            } else {
                                target.addView(actionButton(tr("Impulsar · 20 monedas", "Boost · 20 coins")) {
                                    confirm(tr("¿Gastar 20 monedas normales para impulsar?", "Spend 20 regular coins to boost?")) {
                                        call({ api.boost(activeId, false) })
                                    }
                                })
                                target.addView(actionButton(tr("Impulsar con monedas boost", "Boost with boost coins")) {
                                    confirm(tr("¿Gastar 20 monedas boost?", "Spend 20 boost coins?")) {
                                        call({ api.boost(activeId, true) })
                                    }
                                })
                                target.addView(actionButton(tr("Retirar publicación", "Unpublish")) {
                                    confirm(tr("¿Retirar la publicación sin reembolso?", "Unpublish without refund?")) {
                                        call({ api.remove(activeId) })
                                    }
                                })
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == screenGeneration && target === body && !isFinishing)
                        status.text = tr("Error de conexión: ", "Connection error: ") + (error.message ?: "")
                }
            }
        }.start()
    }

    private fun feedbackDialog(appId: String) {
        val input = EditText(this).apply {
            hint = tr("Comentario privado (máximo 2000 caracteres)", "Private feedback (max 2000 characters)")
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Enviar comentario al desarrollador", "Send feedback to developer"))
            .setView(input)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(tr("Enviar", "Send")) { _, _ ->
                val message = input.text.toString().trim()
                if (message.isNotEmpty() && message.length <= 2000) {
                    call({ api.saveFeedback(appId, message) }, { showScreen(2) })
                } else {
                    Toast.makeText(this, tr("Escribe un comentario de hasta 2000 caracteres", "Write feedback up to 2000 characters"), Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun showCoinHistory() {
        val target = body
        val generation = screenGeneration
        val status = label(tr("Cargando movimientos…", "Loading transactions…"))
        target.addView(status)
        Thread {
            try {
                val rows = api.coinHistory()
                runOnUiThread {
                    if (isFinishing || generation != screenGeneration || target !== body) return@runOnUiThread
                    target.removeView(status)
                    if (rows.length() == 0) target.addView(label(tr("Sin movimientos", "No transactions")))
                    for (i in 0 until rows.length()) {
                        val item = rows.getJSONObject(i)
                        val amount = item.optInt("amount")
                        val currency = if (item.optString("currency") == "boost") "boost" else tr("normales", "regular")
                        val sign = if (amount > 0) "+" else ""
                        target.addView(label("$sign$amount $currency · ${item.optString("reason")}", 15f))
                        target.addView(label(item.optString("created_at").take(16).replace('T', ' '), 12f))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && generation == screenGeneration && target === body)
                        status.text = e.message ?: tr("Error de conexión", "Connection error")
                }
            }
        }.start()
    }

    private fun showReceivedFeedback() {
        val target = body
        val generation = screenGeneration
        val status = label(tr("Cargando comentarios privados…", "Loading private feedback…"))
        target.addView(status)
        Thread {
            try {
                val rows = api.receivedFeedback()
                runOnUiThread {
                    if (isFinishing || generation != screenGeneration || target !== body) return@runOnUiThread
                    target.removeView(status)
                    if (rows.length() == 0) target.addView(label(tr("No hay comentarios", "No feedback yet")))
                    for (i in 0 until rows.length()) {
                        val item = rows.getJSONObject(i)
                        target.addView(label(item.optString("app_name"), 17f, true))
                        target.addView(label(item.optString("body"), 15f))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && generation == screenGeneration && target === body)
                        status.text = e.message ?: tr("Error de conexión", "Connection error")
                }
            }
        }.start()
    }

    private fun showSubmissions() {
        val target = body
        val generation = screenGeneration
        val status = label(tr("Consultando pruebas…", "Checking tests…"))
        target.addView(status)
        Thread {
            try {
                val rows = api.submissions()
                runOnUiThread {
                    if (isFinishing || generation != screenGeneration || target !== body) return@runOnUiThread
                    target.removeView(status)
                    if (rows.length() == 0) target.addView(label(tr("No hay pruebas registradas", "No recorded tests")))
                    for (i in 0 until rows.length()) {
                        val item = rows.getJSONObject(i)
                        val state = when (item.optString("status")) {
                            "verified" -> tr("Verificada", "Verified")
                            "submitted" -> tr("Pendiente de revisión", "Awaiting review")
                            else -> tr("En curso", "In progress")
                        }
                        target.addView(label(item.optString("app_name") + " · " + state))
                        if (item.optString("status") == "verified") {
                            val appId = item.optString("app_id")
                            target.addView(actionButton(tr("Comentario privado", "Private feedback")) { feedbackDialog(appId) })
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && generation == screenGeneration && target === body)
                        status.text = e.message ?: tr("Error de conexión", "Connection error")
                }
            }
        }.start()
    }

    private fun showSearchResults(search: String) {
        showScreen(0, search)
    }

    private fun showScreen(page: Int, search: String = "") {
        screenGeneration++
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(246, 250, 254)); setPadding(22, 30, 22, 16) }
        root.addView(label("12TestersFree", 28f, true).apply { setTextColor(blue) })
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            0 -> {
                body.addView(label(tr("Encuentra aplicaciones para probar", "Discover apps to test"), 23f, true))
                val searchInput = EditText(this).apply { hint = tr("Buscar aplicación", "Search app"); setSingleLine(true); setText(search) }
                body.addView(searchInput)
                body.addView(actionButton(tr("Buscar", "Search")) { showSearchResults(searchInput.text.toString()) })
                loadRows(0, search)
                body.addView(label(tr("20 monedas por prueba válida · Una vez por aplicación", "20 coins per valid test · Once per app")))
            }
            1 -> {
                body.addView(label(tr("Mi biblioteca", "My library"), 23f, true))
                body.addView(label(tr("Espacios utilizados: consultando…", "Used slots: checking…")).also { libraryUsage -> loadLibraryUsage(libraryUsage) })
                body.addView(label(tr("Publicar: 60 monedas · Duración: 72 horas", "Publish: 60 coins · Duration: 72 hours")))
                if (api.isSignedIn()) {
                    body.addView(actionButton(tr("+ Añadir aplicación", "+ Add app")) { addAppDialog() })
                    body.addView(actionButton(tr("Desbloquear 1 espacio para siempre · 100 monedas", "Unlock 1 slot forever · 100 coins")) {
                        confirm(tr("¿Comprar un espacio por 100 monedas?", "Buy a slot for 100 coins?")) {
                            call({ api.unlockSlot() })
                        }
                    })
                }
                loadRows(1)
            }
            2 -> {
                body.addView(label(tr("Monedas", "Coins"), 23f, true))
                loadRows(2)
                if (api.isSignedIn()) {
                    val rewardInfo = label(tr("Máximo 2 anuncios recompensados cada 4 horas.", "Maximum 2 rewarded ads every 4 hours."))
                    body.addView(rewardInfo)

                    val rewardButton = actionButton(tr("Consultando disponibilidad…", "Checking availability…")) {}
                    rewardButton.isEnabled = false
                    body.addView(rewardButton)

                    Thread {
                        try {
                            val state = api.rewardedAdStatus()
                            val remaining = state.optInt("remaining", 0)
                            runOnUiThread {
                                if (!isFinishing) {
                                    if (remaining <= 0) {
                                        rewardButton.text = tr("Límite alcanzado · disponible más tarde", "Limit reached · available later")
                                        rewardButton.isEnabled = false
                                    } else {
                                        rewardButton.text = tr("Ver anuncio · +10 monedas de impulso", "Watch ad · +10 boost coins")
                                        rewardButton.isEnabled = true
                                        rewardButton.setOnClickListener {
                                            rewardButton.isEnabled = false
                                            val uid = api.currentUserId()
                                            if (uid == null) {
                                                rewardButton.isEnabled = true
                                                Toast.makeText(this, tr("Inicia sesión", "Sign in first"), Toast.LENGTH_LONG).show()
                                            } else {
                                                ads.showRewarded(uid, {
                                                    rewardButton.text = tr("Validando recompensa…", "Validating reward…")
                                                    Toast.makeText(this, tr("Anuncio completado. Validando recompensa.", "Ad completed. Validating reward."), Toast.LENGTH_LONG).show()
                                                    android.os.Handler(mainLooper).postDelayed({
                                                        if (!isFinishing) showScreen(2)
                                                    }, 4000)
                                                }, {
                                                    rewardButton.isEnabled = true
                                                    Toast.makeText(this, tr("Anuncio no disponible", "Ad unavailable"), Toast.LENGTH_LONG).show()
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                if (!isFinishing) {
                                    rewardButton.text = tr("No se pudo consultar el límite", "Could not check limit")
                                    rewardButton.isEnabled = false
                                }
                            }
                        }
                    }.start()
                }
                body.addView(label(tr("Las recompensas necesitan verificación segura del servidor.", "Rewards require secure server verification.")))
                if (api.isSignedIn()) {
                    body.addView(actionButton(tr("Consultar mis pruebas", "Check my tests")) { showSubmissions() })
                    body.addView(actionButton(tr("Movimientos de monedas", "Coin transactions")) { showCoinHistory() })
                    body.addView(actionButton(tr("Comentarios recibidos", "Received feedback")) { showReceivedFeedback() })
                }
            }
            else -> {
                body.addView(label(tr("Mi cuenta", "My account"), 23f, true))
                if (api.isSignedIn()) {
                    body.addView(label(tr("Sesión iniciada", "Signed in")))
                    body.addView(actionButton(tr("Solicitar eliminación de cuenta", "Request account deletion")) {
                        confirm(tr("¿Solicitar la eliminación de tu cuenta y datos personales? La solicitud será revisada.", "Request deletion of your account and personal data? Your request will be reviewed.")) {
                            call({ api.requestAccountDeletion() }, {
                                Toast.makeText(this, tr("Solicitud registrada", "Request recorded"), Toast.LENGTH_LONG).show()
                            })
                        }
                    })
                    body.addView(actionButton(tr("Cerrar sesión", "Sign out")) {
                        api.signOut()
                        testPrefs.edit().clear().apply()
                        testPromptShown = false
                        showScreen(3)
                    })
                } else {
                    body.addView(actionButton(tr("Iniciar sesión", "Sign in")) {
                        authDialog(false)
                    })
                    body.addView(actionButton(tr("Crear cuenta", "Create account")) {
                        authDialog(true)
                    })
                }
                body.addView(actionButton(tr("Opciones de privacidad de anuncios", "Ad privacy options")) {
                    ads.privacyOptions()
                })
                body.addView(actionButton(tr("Política de privacidad", "Privacy policy")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://12testersfree-privacy.boomurl.me/")))
                })
                body.addView(actionButton(tr("Unirme al grupo de testers", "Join the tester group")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://groups.google.com/g/12testersfree-community")))
                })
            }
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val names = if (spanish()) listOf("Probar", "Mis apps", "Monedas", "Cuenta") else listOf("Test", "My apps", "Coins", "Account")
        names.forEachIndexed { index, title ->
            nav.addView(Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 11f
                setOnClickListener { showScreen(index) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        if (adsReady) {
            val bannerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            root.addView(bannerContainer)
            ads.attachBanner(bannerContainer)
        }
        setContentView(root)
    }
}
