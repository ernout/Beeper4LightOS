package com.beeper.lightos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow
import com.thelightphone.sdk.androidContext
import kotlinx.coroutines.launch

@InitialScreen
class BeeperLoginScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val coroutineScope = rememberCoroutineScope()
        val owner = androidx.compose.ui.platform.LocalSavedStateRegistryOwner.current
        LaunchedEffect(owner) {
            BeeperRepository.init(androidContext)
        }
        
        var email by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        
        var isEditingEmail by remember { mutableStateOf(false) }
        
        val isLoggedIn by BeeperRepository.isLoggedIn.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        // Static defaults instead of rememberKeyboardOptions(): LightOS on the LP3
        // answers GetKeyboardOptions with a payload this SDK version cannot parse,
        // and the deserialization error takes the whole process down.
        val keyboardOptionsFlow = remember { MutableStateFlow(defaultKeyboardOptions()) }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                if (isEditingEmail) {
                    val textFieldState = rememberTextFieldState(email)
                    val editorKey = remember { java.util.UUID.randomUUID().toString() }
                    LightTextInputEditor(
                        title = "Email Address",
                        state = textFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        editorKey = editorKey,
                        onSubmit = {
                            email = it.toString()
                            isEditingEmail = false
                        },
                        onBack = { isEditingEmail = false },
                        modifier = Modifier.fillMaxSize()
                    )
                    return@Box
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isLoggedIn) {
                        navigateTo(screenFactory = { BeeperChatListScreen(it) })
                        return@Column
                    }

                    LightText(text = "Login to Beeper", variant = LightTextVariant.Copy, modifier = Modifier.padding(bottom = 24.dp))
                    
                    LightTextField(
                        label = "Email:",
                        value = email,
                        placeholder = "email@example.com",
                        onClick = { isEditingEmail = true }
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    if (errorMessage != null) {
                        LightText(text = errorMessage ?: "", variant = LightTextVariant.Subheading, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    LightText(
                        text = if (isLoading) "Sending code..." else "Continue",
                        variant = LightTextVariant.Button,
                        modifier = Modifier.lightClickable {
                            if (email.isNotBlank()) {
                                isLoading = true
                                errorMessage = null
                                coroutineScope.launch {
                                    val result = BeeperRepository.startLogin(email)
                                    isLoading = false
                                    if (result.isSuccess) {
                                        val requestId = result.getOrThrow()
                                        navigateTo(screenFactory = { BeeperVerifyScreen(it, requestId) })
                                    } else {
                                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to send code"
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
