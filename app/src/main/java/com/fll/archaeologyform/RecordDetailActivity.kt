package com.fll.archaeologyform

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fll.archaeologyform.databinding.ActivityRecordDetailBinding
import org.json.JSONObject
import java.io.File

class RecordDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val path = intent.getStringExtra("file_path") ?: run { finish(); return }
        loadRecord(File(path))
    }

    private fun loadRecord(file: File) {
        if (file.extension == "csv") {
            loadCsvRecord(file)
            return
        }

        val json = JSONObject(file.readText())

        // Header date
        binding.tvDetailDate.text = json.optString("datetime", file.nameWithoutExtension)

        // Photo
        val photoPath = json.optString("photo_file")
        if (photoPath.isNotEmpty()) {
            val photoFile = File(photoPath)
            if (photoFile.exists()) {
                val bmp = BitmapFactory.decodeFile(photoPath)
                if (bmp != null) {
                    binding.ivDetailPhoto.setImageBitmap(bmp)
                    binding.ivDetailPhoto.visibility = View.VISIBLE
                }
            }
        }

        // GPS
        val lat = json.optString("latitude")
        val lon = json.optString("longitude")
        if (lat.isNotEmpty() && lon.isNotEmpty()) {
            binding.tvDetailGps.text = "$lat,  $lon"
            binding.cardGps.visibility = View.VISIBLE
        }

        // Field rows
        val type = json.optString("type")
        if (type == "custom_form") {
            val questionsArray = json.optJSONArray("questions")
            if (questionsArray != null) {
                for (i in 0 until questionsArray.length()) {
                    val q = questionsArray.getJSONObject(i)
                    val label = q.optString("label")
                    val answer = q.optString("answer")
                    if (label.isNotEmpty() && answer.isNotEmpty()) addField(label, answer)
                }
            }
        } else {
            val fields = listOf(
                "artifact"  to "Artifact",
                "depth"     to "Depth",
                "elevation" to "Elevation",
                "condition" to "Condition",
                "notes"     to "Notes"
            )
            for ((key, label) in fields) {
                val value = json.optString(key)
                if (value.isNotEmpty()) addField(label, value)
            }
        }
    }

    private fun loadCsvRecord(file: File) {
        val lines = file.readLines().filter { it.isNotBlank() }
        val headers = lines.getOrNull(0)?.split(",")?.map { it.trim('"') } ?: return
        val values = lines.getOrNull(1)?.split(",")?.map { it.trim('"') } ?: return

        fun value(key: String) = values.getOrNull(headers.indexOf(key))?.takeIf { it.isNotEmpty() }

        binding.tvDetailDate.text = value("datetime") ?: file.nameWithoutExtension

        val lat = value("latitude")
        val lon = value("longitude")
        if (lat != null && lon != null) {
            binding.tvDetailGps.text = "$lat,  $lon"
            binding.cardGps.visibility = View.VISIBLE
        }

        for ((i, header) in headers.withIndex()) {
            if (header == "datetime" || header == "latitude" || header == "longitude") continue
            val answer = values.getOrNull(i) ?: ""
            if (answer.isNotEmpty()) addField(header, answer)
        }
    }

    private fun addField(label: String, value: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        container.addView(TextView(this).apply {
            text = label.uppercase()
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8A9BB5"))
            letterSpacing = 0.12f
            setPadding(0, 0, 0, 4)
        })

        container.addView(TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(Color.parseColor("#2D3748"))
            setLineSpacing(0f, 1.4f)
        })

        // Divider (except last)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = 12 }
            setBackgroundColor(Color.parseColor("#EEF1F8"))
        })

        binding.layoutFields.addView(container)
    }
}
