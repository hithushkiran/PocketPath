package com.pocketpath.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketpath.R

private enum class AuthMode {
    LOGIN,
    REGISTER,
    RESET,
}

@Composable
fun AuthScreen(
    action: AuthActionState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResetPassword: (String) -> Unit,
    onClearMessage: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.LOGIN) }
    var email by rememberSaveable { mutableStateOf("") }

    // Passwords deliberately do not enter saved-instance state.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    fun changeMode(next: AuthMode) {
        mode = next
        password = ""
        confirmation = ""
        onClearMessage()
    }

    fun submit() {
        when (mode) {
            AuthMode.LOGIN -> onSignIn(email, password)
            AuthMode.REGISTER -> onRegister(email, password, confirmation)
            AuthMode.RESET -> onResetPassword(email)
        }
    }

    BackHandler(
        enabled = mode != AuthMode.LOGIN && !action.busy,
    ) {
        changeMode(AuthMode.LOGIN)
    }

    val title = when (mode) {
        AuthMode.LOGIN -> R.string.auth_login_title
        AuthMode.REGISTER -> R.string.auth_register_title
        AuthMode.RESET -> R.string.auth_reset_title
    }

    val buttonLabel = when (mode) {
        AuthMode.LOGIN -> R.string.auth_login_title
        AuthMode.REGISTER -> R.string.auth_create_account
        AuthMode.RESET -> R.string.auth_send_reset
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                )

                Text(
                    text = stringResource(R.string.auth_welcome),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.headlineSmall,
                )

                if (mode == AuthMode.REGISTER) {
                    Text(stringResource(R.string.auth_register_help))
                }

                if (mode == AuthMode.RESET) {
                    Text(stringResource(R.string.auth_reset_help))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !action.busy,
                    label = { Text(stringResource(R.string.auth_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = if (mode == AuthMode.RESET) {
                            ImeAction.Done
                        } else {
                            ImeAction.Next
                        },
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() },
                    ),
                )

                key(mode) {
                    if (mode != AuthMode.RESET) {
                        PasswordInput(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.auth_password),
                            enabled = !action.busy,
                            imeAction = if (mode == AuthMode.REGISTER) {
                                ImeAction.Next
                            } else {
                                ImeAction.Done
                            },
                            onDone = { submit() },
                        )
                    }

                    if (mode == AuthMode.REGISTER) {
                        PasswordInput(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = stringResource(R.string.auth_confirm_password),
                            enabled = !action.busy,
                            imeAction = ImeAction.Done,
                            onDone = { submit() },
                        )
                    }
                }

                action.messageRes?.let { message ->
                    Text(
                        text = stringResource(message),
                        color = if (action.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }

                Button(
                    onClick = { submit() },
                    enabled = !action.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (action.busy) {
                                R.string.auth_working
                            } else {
                                buttonLabel
                            },
                        ),
                    )
                }

                if (mode == AuthMode.LOGIN) {
                    TextButton(
                        onClick = { changeMode(AuthMode.REGISTER) },
                        enabled = !action.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_create_account))
                    }

                    TextButton(
                        onClick = { changeMode(AuthMode.RESET) },
                        enabled = !action.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_forgot_password))
                    }
                } else {
                    TextButton(
                        onClick = { changeMode(AuthMode.LOGIN) },
                        enabled = !action.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_back_to_login))
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    imeAction: ImeAction,
    onDone: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone() },
        ),
        trailingIcon = {
            TextButton(
                onClick = { visible = !visible },
                enabled = enabled,
            ) {
                Text(
                    stringResource(
                        if (visible) R.string.auth_hide else R.string.auth_show,
                    ),
                )
            }
        },
    )
}
