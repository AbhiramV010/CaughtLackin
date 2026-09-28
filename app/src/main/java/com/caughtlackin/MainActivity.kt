package com.caughtlackin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.caughtlackin.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** System picker exposes only the chosen number, never the full contact list. */
    private val pickPhone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use
            val member = SquadMember(c.getString(0) ?: "Unknown", c.getString(1) ?: return@use)
            val squad = prefs.squad
            when {
                squad.any { it.phone.normalized() == member.phone.normalized() } -> toast("${member.name} is already in the squad")
                squad.size >= Prefs.MAX_SQUAD -> toast("Squad is full")
                else -> prefs.squad = squad + member
            }
        }
        renderSquad()
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Only notifications are optional.
        if (missingPermissions().all { it == Manifest.permission.POST_NOTIFICATIONS }) {
            startSession()
        } else {
            toast("Camera and SMS permissions are needed to run a session")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        prefs = Prefs(this)

        binding.messages.setText(prefs.messages.joinToString("\n"))
        binding.resetMessages.setOnClickListener { binding.messages.setText(Prefs.DEFAULT_MESSAGES.joinToString("\n")) }
        binding.lockEndsSession.isChecked = prefs.lockEndsSession
        binding.lockEndsSession.setOnCheckedChangeListener { _, checked -> prefs.lockEndsSession = checked }
        binding.calibration.isChecked = prefs.calibrationMode
        binding.calibration.setOnCheckedChangeListener { _, checked ->
            prefs.calibrationMode = checked
            renderHint()
        }
        binding.addMember.setOnClickListener {
            pickPhone.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
        }
        binding.start.setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        renderSquad()
    }

    override fun onPause() {
        super.onPause()
        saveMessages()
    }

    private fun saveMessages() {
        prefs.messages = binding.messages.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun renderSquad() {
        val list = binding.squadList
        list.removeAllViews()
        prefs.squad.forEach { member ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    text = "${member.name}\n${member.phone}"
                    textSize = 15f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = "Remove"
                    setOnClickListener {
                        prefs.squad = prefs.squad.filterNot { it == member }
                        renderSquad()
                    }
                },
            )
            list.addView(row)
        }
        binding.addMember.isEnabled = prefs.squad.size < Prefs.MAX_SQUAD
        renderHint()
    }

    private fun renderHint() {
        val n = prefs.squad.size
        binding.hint.text = when {
            prefs.calibrationMode ->
                "Calibration: sit in each state (locked in, writing, talking, away) for 30 s and tag it on the session screen. " +
                    "CSVs go to Android/data/com.caughtlackin/files/calibration."
            n < Prefs.MIN_SQUAD -> "Add ${Prefs.MIN_SQUAD - n} more squad member${if (Prefs.MIN_SQUAD - n == 1) "" else "s"} to start."
            else -> "Squad of $n ready. Texts have a 10 minute cooldown."
        }
    }

    private fun onStartClicked() {
        saveMessages()
        if (!prefs.calibrationMode) {
            if (prefs.squad.size < Prefs.MIN_SQUAD) return toast("You need at least ${Prefs.MIN_SQUAD} people in your squad")
            if (prefs.messages.isEmpty()) return toast("Add at least one message")
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) startSession() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun missingPermissions(): List<String> {
        val wanted = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) +
            if (prefs.calibrationMode) emptyList() else listOf(Manifest.permission.SEND_SMS)
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun startSession() {
        SessionService.start(this)
        startActivity(Intent(this, SessionActivity::class.java))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun String.normalized() = filter { it.isDigit() }.takeLast(10)
}

fun applySystemBarPadding(view: View) {
    val l = view.paddingLeft
    val t = view.paddingTop
    val r = view.paddingRight
    val b = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
}
