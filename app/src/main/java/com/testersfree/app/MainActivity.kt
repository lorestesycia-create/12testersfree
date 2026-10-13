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
        val fields = listOf("Package ID (com.example.app)", tr("Nombre", "Name"),
            tr("Desarrollador", "Developer"), "Google Play URL", tr("Instrucciones", "Instructions"))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val inputs = fields.map { hint -> EditText(this).also { it.hint = hint; layout.addView(it) } }
        AlertDialog.Builder(this).setTitle(tr("Añadir aplicación", "Add app")).setView(layout)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(tr("Guardar", "Save")) { _, _ ->
                call({ api.addApp(inputs[0].text.toString(), inputs[1].text.toString(),
                    inputs[2].text.toString(), inputs[3].text.toString(), inputs[4].text.toString()) })
            }.show()
    }

    private fun editAppDialog(item: org.json.JSONObject) {
        val active = !item.optString("active_listing_id").let { it == "null" || it.isBlank() }
        val fields = listOf(tr("Nombre", "Name"), tr("Desarrollador", "Developer"), "Google Play URL", tr("Instrucciones", "Instructions"))
        val values = listOf(item.optString("app_name"), item.optString("developer_name"), item.optString("play_test_url"), item.optString("instructions"))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val inputs = fields.mapIndexed { i, hint -> EditText(this).also { it.hint = hint; it.setText(values[i]); it.isEnabled = !active || i == 3; layout.addView(it) } }
        AlertDialog.Builder(this).setTitle(tr("Editar aplicación", "Edit app")).setView(layout)
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .setPositiveButton(tr("Guardar", "Save")) { _, _ ->
                call({ api.editApp(item.getString("app_id"), inputs[0].text.toString(), inputs[1].text.toString(), inputs[2].text.toString(), inputs[3].text.toString()) })
            }.show()
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
                            0 -> item.optString("app_name") + " · " + item.optString("developer_name")
                            1 -> item.optString("app_name") + " · " + item.optString("package_name")
                            else -> "${item.optInt("normal_coins")} monedas · ${item.optInt("boost_coins")} boost · ${item.optInt("library_slots")} espacios"
                        }
                        target.addView(label(line, 17f, true))
                        if (page == 0) {
                            val instructions = item.optString("instructions").takeUnless { it.isBlank() || it == "null" }
                            if (instructions != null) target.addView(label(tr("Instrucciones: ", "Instructions: ") + instructions, 14f))
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
                val searchInput = EditText(this).apply { hint = tr("Buscar aplicación o desarrollador", "Search app or developer"); setSingleLine(true); setText(search) }
                body.addView(searchInput)
                body.addView(actionButton(tr("Buscar", "Search")) { showSearchResults(searchInput.text.toString()) })
                loadRows(0, search)
                body.addView(label(tr("20 monedas por prueba válida · Una vez por aplicación", "20 coins per valid test · Once per app")))
            }
            1 -> {
                body.addView(label(tr("Mi biblioteca", "My library"), 23f, true))
                body.addView(label(tr("2 espacios gratuitos · Hasta 10 espacios", "2 free slots · Up to 10 slots")))
                body.addView(label(tr("Publicar: 60 monedas · Duración: 72 horas", "Publish: 60 coins · Duration: 72 hours")))
                if (api.isSignedIn()) {
                    body.addView(actionButton(tr("+ Añadir aplicación", "+ Add app")) { addAppDialog() })
                    body.addView(actionButton(tr("Desbloquear espacio · 100 monedas", "Unlock slot · 100 coins")) {
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
                    body.addView(actionButton(tr("Ver anuncio · +10 monedas de impulso", "Watch ad · +10 boost coins")) {
                        val uid = api.currentUserId()
                        if (uid == null) {
                            Toast.makeText(this, tr("Inicia sesión", "Sign in first"), Toast.LENGTH_LONG).show()
                        } else {
                            ads.showRewarded(uid, {
                                Toast.makeText(this, tr("Anuncio completado. Recompensa pendiente de validación.", "Ad completed. Reward awaiting verification."), Toast.LENGTH_LONG).show()
                            }, {
                                Toast.makeText(this, tr("Anuncio no disponible", "Ad unavailable"), Toast.LENGTH_LONG).show()
                            })
                        }
                    })
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
                body.addView(label(tr("Grupo de testers: enlace pendiente.", "Tester group: link pending.")))
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
