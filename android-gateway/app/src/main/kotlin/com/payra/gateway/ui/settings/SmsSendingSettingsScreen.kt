package com.payra.gateway.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.payra.gateway.R
import com.payra.gateway.storage.MAX_SMS_PARTS
import com.payra.gateway.ui.InlineLoadingLabel

@Composable
internal fun SmsSendingSettingsScreen(
    maxParts: Int,
    isSaving: Boolean,
    saveFailed: Boolean,
    onSave: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var draft by rememberSaveable(maxParts) { mutableStateOf(maxParts.toString()) }
    val limit = draft.toIntOrNull()?.takeIf { it in 1..MAX_SMS_PARTS }
    val invalidLimit = limit == null && draft.isNotEmpty()
    SettingsPage(title = R.string.gateway_sms_sending_settings, onBack = onBack, isSaving = isSaving) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = !isSaving,
            singleLine = true,
            isError = invalidLimit,
            label = { Text(stringResource(R.string.gateway_sms_parts_limit)) },
            supportingText = {
                val hint = if (invalidLimit) {
                    stringResource(R.string.gateway_sms_parts_invalid, MAX_SMS_PARTS)
                } else {
                    stringResource(R.string.gateway_sms_parts_limit_description)
                }
                Text(hint)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (saveFailed) {
            Text(
                text = stringResource(R.string.gateway_settings_save_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { limit?.let(onSave) }, enabled = !isSaving && limit != null && limit != maxParts) {
            if (isSaving) {
                InlineLoadingLabel(stringResource(R.string.gateway_saving))
            } else {
                Text(stringResource(R.string.gateway_save))
            }
        }
    }
}
