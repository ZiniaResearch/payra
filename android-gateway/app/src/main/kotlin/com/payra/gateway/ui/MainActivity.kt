package com.payra.gateway.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.payra.gateway.R
import com.payra.gateway.sms.SmsSender

class MainActivity : Activity() {
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message =
            TextView(this).apply {
                text = getString(R.string.hello_world)
            }

        permissionButton =
            Button(this).apply {
                setOnClickListener {
                    requestPermissions(
                        arrayOf(Manifest.permission.SEND_SMS),
                        SMS_PERMISSION_REQUEST,
                    )
                }
            }
        val recipient =
            EditText(this).apply {
                setHint(R.string.test_sms_number)
                inputType = InputType.TYPE_CLASS_PHONE
                setSingleLine(true)
            }

        val sendButton = Button(this)
        sendButton.setText(R.string.send_test_sms)
        sendButton.setOnClickListener {
            try {
                SmsSender.sendTest(this, recipient.text.toString().trim())
                sendButton.isEnabled = false
            } catch (error: IllegalArgumentException) {
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.allow_sms, Toast.LENGTH_LONG).show()
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(message)
                addView(permissionButton)
                addView(recipient)
                addView(sendButton)
            },
        )

        updatePermissionButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_REQUEST) {
            updatePermissionButton()
        }
    }

    private fun updatePermissionButton() {
        val granted =
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

        permissionButton.setText(
            if (granted) R.string.sms_permission_granted else R.string.allow_sms,
        )
        permissionButton.isEnabled = !granted
    }

    private companion object {
        const val SMS_PERMISSION_REQUEST = 1
    }
}
