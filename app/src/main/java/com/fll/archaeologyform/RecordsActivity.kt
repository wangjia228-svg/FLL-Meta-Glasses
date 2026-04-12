package com.fll.archaeologyform

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.fll.archaeologyform.databinding.ActivityRecordsBinding
import org.json.JSONObject
import java.io.File

class RecordsActivity : HandsFreeActivity() {

    override val screenName = "Records"

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
            ?.filter { it.extension == "json" || it.extension == "txt" || it.extension == "csv" }
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
            val name = json.optString("name").takeIf { it.isNotEmpty() }
            val datetime = json.optString("datetime", "Unknown date")

            val typeLabel = when (type) {
                "photo_documentation" -> "  Photo Documentation"
                "custom_form" -> "  Custom Form"
                else -> "  Voice Form"
            }
            addLabel(content, typeLabel, 12f, "#666666")
            addLabel(content, name ?: datetime, 16f, "#888888")
            if (name != null) addLabel(content, datetime, 13f, "#222222")

            val lat = json.optString("latitude").toDoubleOrNull()
            val lon = json.optString("longitude").toDoubleOrNull()
            if (lat != null && lon != null) {
                addLabel(content, "GPS: ${"%.2f".format(lat)}, ${"%.2f".format(lon)}", 11f, "#999999")
            }
        } else if (file.extension == "csv") {
            val lines = file.readLines().filter { it.isNotBlank() }
            val headers = lines.getOrNull(0)?.split(",")?.map { it.trim('"') } ?: emptyList()
            val values = lines.getOrNull(1)?.split(",")?.map { it.trim('"') } ?: emptyList()
            fun col(key: String) = values.getOrNull(headers.indexOf(key))?.takeIf { it.isNotEmpty() }
            val name = col("name")
            val datetime = col("datetime")
            val lat = col("latitude")?.toDoubleOrNull()
            val lon = col("longitude")?.toDoubleOrNull()
            addLabel(content, "  Custom Form", 12f, "#666666")
            addLabel(content, name ?: datetime ?: file.nameWithoutExtension, 16f, "#888888")
            if (name != null && datetime != null) addLabel(content, datetime, 13f, "#222222")
            if (lat != null && lon != null) {
                addLabel(content, "GPS: ${"%.2f".format(lat)}, ${"%.2f".format(lon)}", 11f, "#999999")
            }
        } else {
            addLabel(content, "  Quick Note", 12f, "#999999")
            addLabel(content, file.readText().take(150), 13f, "#333333")
        }

        card.addView(content)
        card.isClickable = true
        card.isFocusable = true
        card.foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33000000),
            null, null
        )
        card.setOnClickListener {
            val intent = Intent(this, RecordDetailActivity::class.java)
            intent.putExtra("file_path", file.absolutePath)
            startActivity(intent)
        }
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
