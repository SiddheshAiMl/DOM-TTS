package com.itantra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.itantra.ui.navigation.MainAppContainer
import com.itantra.ui.theme.ITantraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ITantraTheme {
                MainAppContainer()
            }
        }
    }
}
