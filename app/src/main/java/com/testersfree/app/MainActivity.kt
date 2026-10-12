package com.testersfree.app

import android.app.Activity
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
    private val blue = Color.rgb(18, 112, 205)
    private lateinit var body: LinearLayout
    private fun spanish() = resources.configuration.locales[0].language == "es"
    private fun tr(es: String, en: String) = if (spanish()) es else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showScreen(0)
    }

    private fun label(text: String, size: Float = 17f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(35, 51, 70))
        setPadding(0, 12, 0, 12)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun showScreen(page: Int) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(246, 250, 254)); setPadding(22, 30, 22, 16) }
        root.addView(label("12TestersFree", 28f, true).apply { setTextColor(blue) })
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            0 -> {
                body.addView(label(tr("Encuentra aplicaciones para probar", "Discover apps to test"), 23f, true))
                body.addView(label(tr("La lista de pruebas aparecerá cuando conectemos el servidor.", "Available tests will appear when the server is connected.")))
                body.addView(label(tr("20 monedas por prueba válida · Una vez por aplicación", "20 coins per valid test · Once per app")))
            }
            1 -> {
                body.addView(label(tr("Mi biblioteca", "My library"), 23f, true))
                body.addView(label(tr("2 espacios gratuitos · Hasta 10 espacios", "2 free slots · Up to 10 slots")))
                body.addView(label(tr("Publicar: 60 monedas · Duración: 72 horas", "Publish: 60 coins · Duration: 72 hours")))
                body.addView(label(tr("La edición y publicación estarán disponibles tras conectar tu cuenta.", "Editing and publishing will be available after connecting your account.")))
            }
            2 -> {
                body.addView(label(tr("Monedas", "Coins"), 23f, true))
                body.addView(label(tr("Monedas: 0", "Coins: 0"), 20f, true))
                body.addView(label(tr("Monedas boost: 0", "Boost coins: 0"), 20f, true))
                body.addView(label(tr("Las recompensas necesitan verificación segura del servidor.", "Rewards require secure server verification.")))
            }
            else -> {
                body.addView(label(tr("Mi cuenta", "My account"), 23f, true))
                body.addView(label(tr("Inicio de sesión con Google: pendiente de configuración.", "Google sign-in: awaiting configuration.")))
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
        setContentView(root)
    }
}
