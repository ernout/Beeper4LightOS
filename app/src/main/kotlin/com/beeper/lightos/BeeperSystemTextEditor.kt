package com.beeper.lightos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Full-screen editor for the long fields, typed on the phone's own keyboard.
 *
 * The SDK's embedded LP3 keyboard is fine for a code or an address, but it slows
 * to a crawl on anything the length of a message. A plain [BasicTextField] with
 * focus brings up the system IME (AOSP LatinIME on the LP3), suggestion bar and
 * all; [BeeperTextInputEditor] stays available for the short fields.
 */
@Composable
fun BeeperSystemTextEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String = "SEND",
) {
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography
    val focusRequester = remember { FocusRequester() }

    Surface {
        Column(modifier = modifier.fillMaxSize().imePadding()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                ),
                center = LightTopBarCenter.Text(title),
                rightButton = LightBarButton.Text(
                    text = submitLabel,
                    onClick = { onSubmit(state.text) },
                ),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            BasicTextField(
                state = state,
                textStyle = typography.fine.copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                lineLimits = TextFieldLineLimits.MultiLine(),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp())
                    .focusRequester(focusRequester),
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
