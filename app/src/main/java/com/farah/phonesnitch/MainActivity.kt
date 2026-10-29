package com.farah.phonesnitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.farah.phonesnitch.ui.LauncherRoot
import com.farah.phonesnitch.ui.LauncherViewModel
import com.farah.phonesnitch.ui.LauncherViewModelFactory
import com.farah.phonesnitch.ui.theme.PhoneSnitchTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels {
        LauncherViewModelFactory((application as PhoneSnitchApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneSnitchTheme {
                LauncherRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshApps()
    }
}
