package com.payra.gateway.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.payra.gateway.storage.GatewayAppearance

@Composable
internal fun GatewayTheme(appearance: GatewayAppearance, content: @Composable () -> Unit) {
    val darkTheme = when (appearance) {
        GatewayAppearance.SYSTEM -> isSystemInDarkTheme()
        GatewayAppearance.LIGHT -> false
        GatewayAppearance.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
