package com.pocketpath

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.pocketpath.feature.auth.AuthRoot
import com.pocketpath.feature.auth.AuthViewModel
import com.pocketpath.ui.theme.PocketPathTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        setContent {
            PocketPathTheme {
                AuthRoot(viewModel = authViewModel)
            }
        }
    }
}
