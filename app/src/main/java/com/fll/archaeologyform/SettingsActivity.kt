package com.fll.archaeologyform

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fll.archaeologyform.databinding.ActivitySettingsBinding

class SettingsActivity : HandsFreeActivity() {

    override val screenName = "Settings"

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("marp_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.switchPhoneAudio.isChecked = prefs.getBoolean("phone_audio_mode", false)
        binding.switchPhoneAudio.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("phone_audio_mode", checked).apply()
        }

        binding.btnResetForm.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Field Form")
                .setMessage("This will remove your custom form and revert to the original default questions.")
                .setPositiveButton("Reset") { _, _ ->
                    prefs.edit().remove("custom_template_json").apply()
                    Toast.makeText(this, "Field form reset to original.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
