package com.fll.archaeologyform

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.fll.archaeologyform.databinding.ActivityCustomFormBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CustomFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomFormBinding
    private data class FormField(val label: String, val description: String, val type: String)
    private val fields = mutableListOf<FormField>()

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) parseCsv(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnImportCsv.setOnClickListener {
            filePicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        }
        binding.btnDownloadTemplate.setOnClickListener { downloadTemplate() }
        binding.btnBackToUpload.setOnClickListener {
            binding.scrollPreview.visibility = View.GONE
            binding.scrollSetup.visibility = View.VISIBLE
        }
        binding.btnConfirmTemplate.setOnClickListener { saveTemplateAndFinish() }
    }

    // ── CSV parsing ──────────────────────────────────────────────────────────

    private fun parseCsv(uri: Uri) {
        try {
            val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: return
            fields.clear()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = splitCsvLine(trimmed)
                // col0=identifier, col1=label, col2=description, col3=type
                val label = parts.getOrNull(1)?.trim()?.removeSurrounding("\"") ?: continue
                if (index == 0 && label.lowercase() == "question label") continue
                val description = parts.getOrNull(2)?.trim()?.removeSurrounding("\"") ?: ""
                val type = parts.getOrNull(3)?.trim()?.removeSurrounding("\"")?.lowercase() ?: continue
                if (label.isNotEmpty() && type.isNotEmpty()) {
                    fields.add(FormField(label, description, type))
                }
            }
            if (fields.isNotEmpty()) showPreview()
            else Toast.makeText(this, "No valid fields found in CSV", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    private fun showPreview() {
        binding.layoutPreviewQuestions.removeAllViews()

        for ((index, field) in fields.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 14, 0, 14)
            }

            val labelView = TextView(this).apply {
                text = "Q${index + 1}: ${field.label}"
                textSize = 15f
                setTextColor(Color.parseColor("#1B2A4A"))
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(labelView)

            if (field.description.isNotEmpty()) {
                val descView = TextView(this).apply {
                    text = field.description
                    textSize = 13f
                    setTextColor(Color.parseColor("#666666"))
                    setPadding(0, 3, 0, 3)
                }
                row.addView(descView)
            }

            val typeColor = when (field.type) {
                "photo"  -> "#E67E22"
                "number" -> "#8E44AD"
                "long"   -> "#2980B9"
                else     -> "#27AE60"
            }
            val typeView = TextView(this).apply {
                text = field.type
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(typeColor))
                setPadding(16, 4, 16, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }
            row.addView(typeView)

            if (index < fields.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 12 }
                    setBackgroundColor(Color.parseColor("#EEF1F8"))
                }
                row.addView(divider)
            }

            binding.layoutPreviewQuestions.addView(row)
        }

        binding.tvPreviewCount.text = "${fields.size} question${if (fields.size == 1) "" else "s"}"
        binding.scrollSetup.visibility = View.GONE
        binding.scrollPreview.visibility = View.VISIBLE
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private fun saveTemplateAndFinish() {
        val arr = JSONArray()
        for (field in fields) {
            arr.put(JSONObject().apply {
                put("label", field.label)
                put("description", field.description)
                put("type", field.type)
            })
        }
        getSharedPreferences("marp_prefs", Context.MODE_PRIVATE)
            .edit().putString("custom_template_json", arr.toString()).apply()
        Toast.makeText(this, "Template saved! New Field Record will now use these questions.", Toast.LENGTH_LONG).show()
        finish()
    }

    // ── Template download ────────────────────────────────────────────────────

    private fun downloadTemplate() {
        val content = buildString {
            appendLine(",Question Label,Question Description (optional),\"Question Type (choose short, long, number, or photo)\"")
            for (i in 1..20) appendLine("Question #$i,,,")
        }
        val file = File(getExternalFilesDir(null), "custom_form_template.csv")
        file.writeText(content)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Save template"
        ))
    }
}
