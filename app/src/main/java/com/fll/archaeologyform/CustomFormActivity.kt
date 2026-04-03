package com.fll.archaeologyform

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.fll.archaeologyform.databinding.ActivityCustomFormBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomFormBinding

    // Each pair is (label, key)
    private val questions = mutableListOf<Pair<String, String>>()
    private val answerFields = mutableListOf<EditText>()

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) parseCsvAndBuildForm(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnImportCsv.setOnClickListener { openFilePicker() }
        binding.btnDownloadTemplate.setOnClickListener { downloadTemplate() }
        binding.btnChangeCsv.setOnClickListener {
            binding.scrollForm.visibility = View.GONE
            binding.scrollSetup.visibility = View.VISIBLE
        }
        binding.btnSaveRecord.setOnClickListener { saveRecord() }
    }

    private fun openFilePicker() {
        filePicker.launch(
            arrayOf(
                "text/csv",
                "text/comma-separated-values",
                "text/plain",
                "application/octet-stream"
            )
        )
    }

    private fun parseCsvAndBuildForm(uri: Uri) {
        try {
            val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: return
            questions.clear()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = splitCsvLine(trimmed)
                if (parts.size < 2) continue
                val label = parts[0].trim().removeSurrounding("\"")
                val key = parts[1].trim().removeSurrounding("\"")
                // Skip header row
                if (index == 0 && label.lowercase() == "question_label") continue
                if (label.isNotEmpty() && key.isNotEmpty()) {
                    questions.add(label to key)
                }
            }
            if (questions.isNotEmpty()) buildForm()
        } catch (e: Exception) {
            // Show error to user
            android.widget.Toast.makeText(this, "Error reading CSV: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun buildForm() {
        binding.layoutQuestions.removeAllViews()
        answerFields.clear()

        for ((label, _) in questions) {
            val labelView = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#444444"))
                setPadding(0, 12, 0, 4)
            }
            val editText = EditText(this).apply {
                hint = "Enter $label"
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#1B2A4A"))
                setPadding(0, 4, 0, 12)
                background = null
                val divider = View(this@CustomFormActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.topMargin = 2 }
                    setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"))
                }
                // divider added after
            }

            binding.layoutQuestions.addView(labelView)
            binding.layoutQuestions.addView(editText)
            answerFields.add(editText)

            // Divider between fields
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.topMargin = 4; it.bottomMargin = 4 }
                setBackgroundColor(android.graphics.Color.parseColor("#EEF1F8"))
            }
            binding.layoutQuestions.addView(divider)
        }

        binding.tvFormTitle.text = "Custom Form (${questions.size} fields)"
        binding.scrollSetup.visibility = View.GONE
        binding.scrollForm.visibility = View.VISIBLE
    }

    private fun saveRecord() {
        val datetime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val json = JSONObject().apply {
            put("type", "custom_form")
            put("datetime", datetime)
            val questionsArray = JSONArray()
            for ((index, pair) in questions.withIndex()) {
                val (label, key) = pair
                val answer = answerFields.getOrNull(index)?.text?.toString()?.trim() ?: ""
                val qObj = JSONObject().apply {
                    put("label", label)
                    put("key", key)
                    put("answer", answer)
                }
                questionsArray.put(qObj)
                // Also put flat key for compatibility
                put(key, answer)
            }
            put("questions", questionsArray)
        }

        val dir = File(filesDir, "records")
        dir.mkdirs()
        val filename = "custom_${System.currentTimeMillis()}.json"
        File(dir, filename).writeText(json.toString())

        android.widget.Toast.makeText(this, "Record saved", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun downloadTemplate() {
        val templateContent = "question_label,question_key\nArtifact Type,artifact_type\nDepth (cm),depth_cm\nCondition,condition\nNotes,notes\n"
        val file = File(getExternalFilesDir(null), "custom_form_template.csv")
        file.writeText(templateContent)

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Save template"))
    }
}
