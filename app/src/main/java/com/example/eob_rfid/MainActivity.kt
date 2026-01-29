package com.example.eob_rfid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.eob_rfid.ui.theme.EOB_RFIDTheme
import navigation.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EOB_RFIDTheme {
                AppNav()
            }
        }
    }
}
