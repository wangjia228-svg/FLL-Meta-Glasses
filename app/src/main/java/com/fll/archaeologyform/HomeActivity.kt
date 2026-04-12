package com.fll.archaeologyform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fll.archaeologyform.databinding.ActivityHomeBinding
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val prefs by lazy { getSharedPreferences("marp_prefs", Context.MODE_PRIVATE) }
    private var handsFreeMode
        get() = prefs.getBoolean("hands_free_mode", false)
        set(value) { prefs.edit().putBoolean("hands_free_mode", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupUI()
        observeGlassesConnection()
    }

    override fun onResume() {
        super.onResume()
        updateHandsFreeUI()
    }

    private fun checkPermissions() {
        val readMediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            readMediaPermission
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    private fun setupUI() {
        binding.cardVoiceForm.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.cardViewRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }
        binding.cardCustomForm.setOnClickListener {
            startActivity(Intent(this, CustomFormActivity::class.java))
        }
        binding.cardGlassesStream.setOnClickListener {
            startActivity(Intent(this, GlassesStreamActivity::class.java))
        }
        binding.btnConnectGlasses.setOnClickListener {
            Wearables.startRegistration(this)
        }
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnHandsFreeToggle.setOnClickListener {
            handsFreeMode = !handsFreeMode
            updateHandsFreeUI()
        }
    }

    private fun updateHandsFreeUI() {
        if (handsFreeMode) {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 ON"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#22AA22"))
            binding.tvHandsFreeBar.text = "Hands-free Mode On"
            binding.tvHandsFreeBar.visibility = View.VISIBLE
        } else {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 OFF"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#555555"))
            binding.tvHandsFreeBar.visibility = View.GONE
        }
    }

    private fun observeGlassesConnection() {
        lifecycleScope.launch {
            try {
                Wearables.registrationState.collectLatest { state ->
                    when (state) {
                        is RegistrationState.Registered -> {
                            binding.tvGlassesIcon.text = "\uD83D\uDFE2"
                            binding.tvGlassesStatus.text = "Meta Glasses connected"
                            binding.btnConnectGlasses.visibility = View.GONE
                        }
                        else -> {
                            binding.tvGlassesIcon.text = "⚫"
                            binding.tvGlassesStatus.text = "Meta Glasses not connected"
                            binding.btnConnectGlasses.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeActivity", "Observe error", e)
            }
        }
    }
}
