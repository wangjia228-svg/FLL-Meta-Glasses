package com.fll.archaeologyform

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.fll.archaeologyform.databinding.ActivityRecordsBinding
import org.json.JSONObject
import java.io.File

class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        loadRecords()
    }

    private fun loadRecords() {
        val dir = File(filesDir, "records")
        val files = dir.listFiles()
            ?.filter { it.extension == "json" || it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            binding.tvNoRecords.visibility = View.VISIBLE
            return
        }

        for (file in files) {
            try {
                addRecordCard(file)
            } catch (e: Exception) { /* skip malformed files */ }
        }
    }

    private fun addRecordCard(file: File) {
        val card = CardView(this).apply {
            radius = 20f
            cardElevation = 6f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(8, 8, 8, 8) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
        }

        if (file.extension == "json") {
            val json = JSONObject(file.readText())
            val type = json.optString("type", "form")

            val typeLabel = when (type) {
                "photo_documentation" -> "  Photo Documentation"
                else -> "  Voice Form"
            }
            addLabel(content, typeLabel, 12f, "#999999")
            addLabel(content, json.optString("datetime", "Unknown date"), 15f, "#222222")

            val lat = json.optString("latitude")
            val lon = json.optString("longitude")
            if (lat.isNotEmpty() && lon.isNotEmpty()) {
                addLabel(content, "GPS: $lat, $lon", 12f, "#666666")
            }

            json.optString("depth").takeIf { it.isNotEmpty() }
                ?.let { addLabel(content, "Depth: $it", 12f, "#555555") }
            json.optString("stratum").takeIf { it.isNotEmpty() }
                ?.let { addLabel(content, "Stratum: ${it.take(60)}...", 12f, "#555555") }
            json.optString("project").takeIf { it.isNotEmpty() }
                ?.let { addLabel(content, "Project: $it", 12f, "#555555") }
        } else {
            addLabel(content, "  Quick Note", 12f, "#999999")
            addLabel(content, file.readText().take(150), 13f, "#333333")
        }

        card.addView(content)
        binding.recordsContainer.addView(card)
    }

    private fun addLabel(parent: LinearLayout, text: String, sizeSp: Float, hexColor: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(android.graphics.Color.parseColor(hexColor))
            setPadding(0, 4, 0, 0)
        })
    }
}
