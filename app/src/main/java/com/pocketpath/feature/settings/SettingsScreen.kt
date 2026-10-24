package com.pocketpath.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketpath.R

@Composable
fun SettingsScreen(
    email: String? = null,
    isSigningOut: Boolean = false,
    errorMessage: String? = null,
    onSignOut: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.auth_signed_in),
            style = MaterialTheme.typography.titleLarge,
        )

        email?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Text(stringResource(R.string.auth_settings_note))

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }

        Button(
            onClick = onSignOut,
            enabled = !isSigningOut,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (isSigningOut) {
                        R.string.auth_signing_out
                    } else {
                        R.string.auth_sign_out
                    },
                ),
            )
        }
    }
}
