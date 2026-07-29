package com.beeper.lightos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import net.folivo.trixnity.client.verification.ActiveSasVerificationState
import net.folivo.trixnity.client.verification.ActiveVerificationState

@Composable
fun BeeperButton(text: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp())
            .lightClickable { onClick() }
    ) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            lighten = true
        )
    }
}

class BeeperVerificationScreen(
    sealedActivity: SealedLightActivity
) : LightScreen<Unit, BeeperVerificationViewModel>(sealedActivity) {

    override val viewModelClass: Class<BeeperVerificationViewModel>
        get() = BeeperVerificationViewModel::class.java

    override fun createViewModel() = BeeperVerificationViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val isVerified by viewModel.isVerified.collectAsState()
        val activeVerification by viewModel.activeVerification.collectAsState()
        val verificationState by viewModel.verificationState.collectAsState()
        val sasVerificationState by viewModel.sasVerificationState.collectAsState()
        
        var securityCode by remember { mutableStateOf("") }
        var isEnteringCode by remember { mutableStateOf(false) }
        var isEditingCode by remember { mutableStateOf(false) }
        val keyboardOptionsFlow = com.thelightphone.sdk.rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (isEditingCode) {
                    val textFieldState = androidx.compose.foundation.text.input.rememberTextFieldState(securityCode)
                    val editorKey = remember { java.util.UUID.randomUUID().toString() }
                    com.thelightphone.sdk.ui.LightTextInputEditor(
                        title = "Security Code",
                        state = textFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        editorKey = editorKey,
                        onSubmit = {
                            securityCode = it.toString()
                            isEditingCode = false
                        },
                        onBack = { isEditingCode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                    return@Box
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text("Verify"),
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp())
                ) {
                    if (isVerified) {
                        LightText(
                            text = "This device is verified. (If messages say 'Waiting for key', enter your Security Code below.)",
                            variant = LightTextVariant.Copy
                        )
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    } else if (activeVerification != null) {
                        val state = verificationState
                        val sasState = sasVerificationState
                        
                        if (sasState != null) {
                            when (sasState) {
                                is ActiveSasVerificationState.OwnSasStart -> {
                                    LightText(text = "Waiting for other device to accept SAS...", variant = LightTextVariant.Copy)
                                    BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                }
                                is ActiveSasVerificationState.TheirSasStart -> {
                                    LightText(text = "Other device started verification. Accept?", variant = LightTextVariant.Copy)
                                    BeeperButton(text = "Accept", onClick = { viewModel.acceptSas(sasState) })
                                    BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                }
                                is ActiveSasVerificationState.ComparisonByUser -> {
                                    LightText(text = "Compare Emojis:", variant = LightTextVariant.Copy)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        sasState.emojis.forEach { emoji ->
                                            LightText(text = emoji.second, variant = LightTextVariant.Title)
                                        }
                                    }
                                    Spacer(modifier = Modifier.heightIn(min = 10.dp))
                                    BeeperButton(text = "Match", onClick = { viewModel.match(sasState) })
                                    BeeperButton(text = "Do Not Match", onClick = { viewModel.noMatch(sasState) })
                                }
                                else -> {
                                    val name = sasState::class.simpleName ?: "Unknown"
                                    if (name.contains("Cancel", ignoreCase = true)) {
                                        LightText(text = "Verification Cancelled or Failed.", variant = LightTextVariant.Copy)
                                        BeeperButton(text = "Reset", onClick = { viewModel.resetVerification() })
                                    } else {
                                        LightText(text = "SAS State: $name", variant = LightTextVariant.Copy)
                                        BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                    }
                                }
                            }
                        } else {
                            when (state) {
                                is ActiveVerificationState.OwnRequest -> {
                                    LightText(text = "Waiting for other device to accept...", variant = LightTextVariant.Copy)
                                    BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                }
                                is ActiveVerificationState.TheirRequest -> {
                                    LightText(text = "Incoming request. Accept?", variant = LightTextVariant.Copy)
                                    BeeperButton(text = "Accept", onClick = { viewModel.acceptRequest(state) })
                                    BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                }
                                is ActiveVerificationState.Ready -> {
                                    LightText(text = "Other device is ready. Start?", variant = LightTextVariant.Copy)
                                    BeeperButton(text = "Start", onClick = { viewModel.startSas(state) })
                                    BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                }
                                is ActiveVerificationState.Done -> {
                                    LightText(text = "Verification Complete! Checking decryption...", variant = LightTextVariant.Copy)
                                    BeeperButton(text = "Reset", onClick = { viewModel.resetVerification() })
                                }
                                else -> {
                                    val name = state?.let { it::class.simpleName } ?: "Unknown"
                                    if (name.contains("Cancel", ignoreCase = true)) {
                                        LightText(text = "Verification Cancelled or Failed.", variant = LightTextVariant.Copy)
                                        BeeperButton(text = "Reset", onClick = { viewModel.resetVerification() })
                                    } else {
                                        LightText(text = "Waiting... State: $name", variant = LightTextVariant.Copy)
                                        BeeperButton(text = "Cancel", onClick = { viewModel.cancelVerification() })
                                    }
                                }
                            }
                        }
                    } else {
                        LightText(
                            text = "This device is NOT verified.",
                            variant = LightTextVariant.Copy
                        )
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    }

                    if (isEnteringCode) {
                        LightText(
                            text = "Enter Security Code:",
                            variant = LightTextVariant.Copy
                        )
                        LightTextField(
                            label = "Code:",
                            value = securityCode,
                            placeholder = "Enter 48-char code",
                            onClick = { isEditingCode = true }
                        )
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                        BeeperButton(
                            text = "Submit Code",
                            onClick = { 
                                viewModel.submitSecurityCode(securityCode)
                                isEnteringCode = false
                            }
                        )
                    } else {
                        BeeperButton(
                            text = "Enter Security Code to Unlock Messages",
                            onClick = {
                                // Debug convenience: prefill from a file pushed via
                                // `adb shell run-as <pkg> sh -c 'cat > files/security_code.txt'`
                                val codeFile = BeeperRepository.appContext
                                    ?.let { java.io.File(it.filesDir, "security_code.txt") }
                                if (codeFile != null && codeFile.exists()) {
                                    securityCode = codeFile.readText().trim()
                                    codeFile.delete()
                                }
                                isEnteringCode = true
                            }
                        )
                        Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                        if (!isVerified) {
                            LightText(
                                text = "Interactive verification is not supported. Use your Beeper Recovery Code.",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
}
