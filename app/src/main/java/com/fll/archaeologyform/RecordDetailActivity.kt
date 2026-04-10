package com.fll.archaeologyform

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        val recordFile = File(path)
        loadRecord(recordFile)
        binding.btnExport.setOnClickListener { exportRecord(recordFile) }
    }

    private fun loadRecord(file: File) {
        if (file.extension == "csv") {
            loadCsvRecord(file)
            return
        }

        val json = JSONObject(file.readText())

        // Header: show name if available, otherwise datetime
        val recordName = json.optString("name").takeIf { it.isNotEmpty() }
        binding.tvDetailDate.text = recordName ?: json.optString("datetime", file.nameWithoutExtension)

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

        val name = value("name")
        binding.tvDetailDate.text = name ?: value("datetime") ?: file.nameWithoutExtension

        val lat = value("latitude")
        val lon = value("longitude")
        if (lat != null && lon != null) {
            binding.tvDetailGps.text = "$lat,  $lon"
            binding.cardGps.visibility = View.VISIBLE
        }

        val skip = setOf("name", "datetime", "latitude", "longitude")
        for ((i, header) in headers.withIndex()) {
            if (header in skip) continue
            val answer = values.getOrNull(i) ?: ""
            if (answer.isEmpty()) continue
            if (isImagePath(answer)) addPhotoField(header, answer)
            else addField(header, answer)
        }
    }

    private fun isImagePath(value: String): Boolean {
        val ext = value.substringAfterLast('.', "").lowercase()
        return (ext == "jpg" || ext == "jpeg" || ext == "png") && File(value).exists()
    }

    private fun addPhotoField(label: String, path: String) {
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
        val px = (220 * resources.displayMetrics.density).toInt()
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px
            ).apply { topMargin = 4; bottomMargin = 8 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#D0D8E8"))
        }
        BitmapFactory.decodeFile(path)?.let { imageView.setImageBitmap(it) }
        container.addView(imageView)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = 12 }
            setBackgroundColor(Color.parseColor("#EEF1F8"))
        })
        binding.layoutFields.addView(container)
    }

    private fun exportRecord(file: File) {
        try {
            fun esc(v: String) = if (v.contains(',') || v.contains('"') || v.contains('\n'))
                "\"${v.replace("\"", "\"\"")}\"" else v

            // Build CSV content and collect photos to export
            val csvName: String
            val csvContent: String
            val photos = mutableListOf<Pair<String, File>>() // dest filename -> source file

            if (file.extension == "csv") {
                csvName = file.name
                val lines = file.readLines().filter { it.isNotBlank() }
                val headers = lines.getOrNull(0)?.split(",")?.map { it.trim('"') } ?: return
                val values = lines.getOrNull(1)?.split(",")?.map { it.trim('"') }?.toMutableList() ?: return
                for (i in headers.indices) {
                    val v = values.getOrNull(i) ?: continue
                    if (isImagePath(v)) { photos.add(File(v).name to File(v)); values[i] = File(v).name }
                }
                csvContent = "${lines[0]}\n${values.joinToString(",") { esc(it) }}\n"
            } else if (file.extension == "json") {
                csvName = "${file.nameWithoutExtension}.csv"
                val json = JSONObject(file.readText())
                val photoPath = json.optString("photo_file")
                var exportPhotoName = ""
                if (photoPath.isNotEmpty() && File(photoPath).exists()) {
                    exportPhotoName = File(photoPath).name
                    photos.add(exportPhotoName to File(photoPath))
                }
                val skipKeys = setOf("type", "photo_file")
                val keys = json.keys().asSequence().filter { it !in skipKeys }.toList()
                val headerRow = (keys + listOfNotNull("photo".takeIf { exportPhotoName.isNotEmpty() }))
                    .joinToString(",") { esc(it) }
                val valueRow = (keys.map { json.optString(it) } +
                    listOfNotNull(exportPhotoName.takeIf { it.isNotEmpty() }))
                    .joinToString(",") { esc(it) }
                csvContent = "$headerRow\n$valueRow\n"
            } else return

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // API 29+: use MediaStore so files land in Downloads/MARP_Export
                fun insertDownload(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/MARP_Export/")
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    uri?.let { contentResolver.openOutputStream(it)?.use(write) }
                }
                insertDownload(csvName, "text/csv") { it.write(csvContent.toByteArray()) }
                for ((name, src) in photos) {
                    insertDownload(name, "image/jpeg") { src.inputStream().copyTo(it) }
                }
            } else {
                // API < 29: direct file write
                val exportDir = File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "MARP_Export")
                exportDir.mkdirs()
                File(exportDir, csvName).writeText(csvContent)
                for ((name, src) in photos) src.copyTo(File(exportDir, name), overwrite = true)
            }

            Toast.makeText(this, "Exported to Downloads/MARP_Export", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
