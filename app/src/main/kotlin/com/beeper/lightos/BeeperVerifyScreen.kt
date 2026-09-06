package com.beeper.lightos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch

class BeeperVerifyScreen(
    sealedActivity: SealedLightActivity,
    private val requestId: String
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val coroutineScope = rememberCoroutineScope()
        var code by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        
        var isEditingCode by remember { mutableStateOf(false) }
        
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
                if (isEditingCode) {
                    val textFieldState = rememberTextFieldState(code)
                    val editorKey = remember { java.util.UUID.randomUUID().toString() }
                    LightTextInputEditor(
                        title = "Code",
                        state = textFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        editorKey = editorKey,
                        onSubmit = {
                            code = it.toString()
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
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isLoggedIn) {
                        navigateTo(screenFactory = { BeeperChatListScreen(it) })
                        return@Column
                    }

                    LightText(text = "Enter 6-Digit Code", variant = LightTextVariant.Copy, modifier = Modifier.padding(bottom = 24.dp))
                    LightText(text = "Check your email for the code.", variant = LightTextVariant.Copy, modifier = Modifier.padding(bottom = 24.dp))
                    
                    LightTextField(
                        label = "Code:",
                        value = code,
                        placeholder = "123456",
                        onClick = { isEditingCode = true }
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    if (errorMessage != null) {
                        LightText(text = errorMessage ?: "", variant = LightTextVariant.Subheading, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    LightText(
                        text = if (isLoading) "Verifying..." else "Verify",
                        variant = LightTextVariant.Button,
                        modifier = Modifier.lightClickable {
                            if (code.isNotBlank()) {
                                isLoading = true
                                errorMessage = null
                                coroutineScope.launch {
                                    val result = BeeperRepository.verifyLogin(requestId, code)
                                    isLoading = false
                                    if (result.isFailure) {
                                        errorMessage = result.exceptionOrNull()?.message ?: "Verification failed"
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
