package com.unlock.move

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sm: SensorManager
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var baseline = -1f
    private var steps = 0
    private var lastStepAt = 0L
    private var lastMagnitude = 0.0
    private var peakSeen = false
    private var tracking = false
    private var weight = 65.0
    private var stride = 0.72
    private lateinit var stepsText: TextView
    private lateinit var distanceText: TextView
    private lateinit var caloriesText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var weightInput: EditText
    private lateinit var strideInput: EditText
    private val prefs by lazy { getSharedPreferences("unlock_move", Context.MODE_PRIVATE) }
    private val today: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (prefs.getString("day", "") != today) {
            prefs.edit().putString("day", today).putInt("steps", 0).apply()
        }
        steps = prefs.getInt("steps", 0)
        weight = prefs.getFloat("weight", 65f).toDouble()
        stride = prefs.getFloat("stride", 0.72f).toDouble()
        buildUi()
        refresh()
        statusText.text = if (stepSensor != null) "Capteur de pas disponible"
            else if (accelSensor != null) "Mode accéléromètre estimatif"
            else "Aucun capteur de mouvement disponible"
    }

    private fun buildUi() {
        val mint = Color.rgb(163, 255, 203)
        val white = Color.WHITE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 24)
            setBackgroundColor(Color.rgb(11, 16, 32))
        }
        fun label(s: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
        fun gap(dp: Int) = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (dp * resources.displayMetrics.density).toInt())
        }
        root.addView(label("UNLOCK MOVE", 27f, mint, true))
        root.addView(label("Chaque pas compte.", 15f, Color.LTGRAY))
        root.addView(gap(18))
        stepsText = label("0", 54f, white, true); root.addView(stepsText)
        root.addView(label("PAS AUJOURD’HUI", 13f, mint, true))
        root.addView(gap(15))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        distanceText = label("0,00 km\nDISTANCE", 17f, white, true)
        caloriesText = label("0 kcal\nESTIMÉES", 17f, white, true)
        row.addView(distanceText, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(caloriesText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row); root.addView(gap(16))
        statusText = label("", 13f, mint); root.addView(statusText)
        startButton = Button(this).apply {
            text = "Démarrer le suivi"
            setOnClickListener { if (tracking) stopTracking() else startTracking() }
        }
        root.addView(startButton); root.addView(gap(10))
        root.addView(label("PROFIL POUR LES ESTIMATIONS", 13f, mint, true))
        weightInput = EditText(this).apply {
            hint = "Poids en kg (ex. 65)"; setText(weight.toInt().toString())
            setSingleLine(); setTextColor(white); setHintTextColor(Color.LTGRAY)
        }
        strideInput = EditText(this).apply {
            hint = "Longueur d’un pas en mètres (ex. 0,72)"
            setText(String.format(Locale.US, "%.2f", stride))
            setSingleLine(); setTextColor(white); setHintTextColor(Color.LTGRAY)
        }
        root.addView(weightInput); root.addView(strideInput)
        root.addView(Button(this).apply {
            text = "Enregistrer mon profil"; setOnClickListener { saveProfile() }
        })
        root.addView(gap(8))
        root.addView(label("Distance et calories estimées, non médicales.", 12f, Color.LTGRAY))
        root.addView(label("Données locales • Aucun compte", 11f, Color.GRAY))
        setContentView(root)
    }

    private fun saveProfile() {
        val w = weightInput.text.toString().replace(',', '.').toDoubleOrNull()
        val s = strideInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (w == null || w !in 25.0..300.0 || s == null || s !in 0.3..1.5) {
            Toast.makeText(this, "Vérifie le poids et la longueur de pas.", Toast.LENGTH_LONG).show()
            return
        }
        weight = w; stride = s
        prefs.edit().putFloat("weight", w.toFloat()).putFloat("stride", s.toFloat()).apply()
        refresh()
        Toast.makeText(this, "Profil enregistré sur ce téléphone.", Toast.LENGTH_SHORT).show()
    }

    private fun startTracking() {
        val sensor = stepSensor ?: accelSensor
        if (sensor == null) { statusText.text = "Capteur incompatible."; return }
        tracking = true
        startButton.text = "Arrêter le suivi"
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        statusText.text = if (stepSensor != null) "Suivi par capteur de pas actif" else "Suivi accéléromètre actif"
    }

    private fun stopTracking() {
        sm.unregisterListener(this); tracking = false
        startButton.text = "Démarrer le suivi"
        statusText.text = "Suivi arrêté"
        prefs.edit().putInt("steps", steps).apply()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!tracking) return
        if (e.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val total = e.values[0]
            if (baseline < 0f) baseline = total - steps
            steps = (total - baseline).toInt().coerceAtLeast(0)
        } else if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
            val mag = sqrt(x*x + y*y + z*z); val delta = mag - lastMagnitude; val now = System.currentTimeMillis()
            if (delta > 1.2 && !peakSeen && now - lastStepAt > 300) {
                steps++; lastStepAt = now; peakSeen = true
            } else if (delta < 0) peakSeen = false
            lastMagnitude = mag
        }
        prefs.edit().putInt("steps", steps).putString("day", today).apply()
        refresh()
    }

    private fun refresh() {
        if (!::stepsText.isInitialized) return
        val km = steps * stride / 1000.0
        val kcal = weight * km * 0.45
        stepsText.text = String.format(Locale.getDefault(), "%,d", steps)
        distanceText.text = String.format(Locale.getDefault(), "%.2f km\nDISTANCE", km)
        caloriesText.text = String.format(Locale.getDefault(), "%.0f kcal\nESTIMÉES", kcal)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onPause() { super.onPause(); sm.unregisterListener(this) }
    override fun onResume() {
        super.onResume()
        if (tracking) (stepSensor ?: accelSensor)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
}
