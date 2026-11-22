package com.payra.gateway.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.payra.gateway.ui.theme.GatewayTheme

class MainActivity : ComponentActivity() {
    private val gatewayViewModel: GatewayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (gatewayViewModel.state == GatewayScreenState.Loading) {
            gatewayViewModel.loadSettings()
        }

        setContent {
            GatewayTheme {
                GatewayApp(gatewayViewModel)
            }
        }
    }
}
