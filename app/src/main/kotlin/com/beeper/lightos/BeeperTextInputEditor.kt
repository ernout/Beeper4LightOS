package com.beeper.lightos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.*
import com.thelightphone.sdk.ui.*
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private const val INPUT_UNDERLINE_THICKNESS_PX = 3f
private const val INPUT_UNDERLINE_GAP_GRID_UNITS = 0.5f

@Composable
fun BeeperTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    modifier: Modifier = Modifier,
    submitLabel: String = "SEND",
    editorKey: Any = title,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val keyboardCallback = remember(state) {
        BeeperTextInputKeyboardCallback(
            state = state,
            singleLine = false,
            onReturn = { currentOnSubmit(state.text) },
        )
    }

    val keyboardViewModel: Lp3KeyboardViewModel = viewModel<DefaultLp3KeyboardViewModel>(
        key = "BeeperTextInputEditor-$editorKey",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DefaultLp3KeyboardViewModel(
                    keyboardCallback,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    optionsForLayout = {
                        val showCloseButton = when (it) {
                            EmojiLayout, is ExtendedCharKeyboard -> true
                            CapsLockedLayout, LowerCaseLayout, NumberLayout, SymbolsLayout, UpperCaseLayout -> false
                        }
                        LayoutOptions(showCloseButton)
                    }
                ) as T
            }
        },
    )

    val colors = LightThemeTokens.colors
    val t = LightThemeTokens.typography
    val inputStyle = t.fine.copy(color = colors.content)
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.text.toString(), textLayout, scrollState.maxValue) {
        textLayout?.let { layout ->
            if (scrollState.viewportSize > 0) {
                val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                val rect = layout.getCursorRect(cursorPos)
                val cursorCenterY = (rect.top + rect.bottom) / 2f
                val targetScroll = (cursorCenterY - (scrollState.viewportSize / 2f)).roundToInt().coerceIn(0, scrollState.maxValue)
                if (scrollState.value != targetScroll) {
                    scrollState.scrollTo(targetScroll)
                }
            }
        }
    }

    Surface {
        Column(modifier = modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                ),
                center = LightTopBarCenter.Text(title),
                rightButton = LightBarButton.Text(
                    text = submitLabel,
                    onClick = { onSubmit(state.text) }
                ),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp())
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            textLayout?.let { layout ->
                                state.edit {
                                    val pos = Offset(down.position.x, down.position.y + scrollState.value)
                                    selection = TextRange(layout.getOffsetForPosition(pos))
                                }
                            }
                            drag(down.id) { change ->
                                textLayout?.let { layout ->
                                    state.edit {
                                        val pos = Offset(change.position.x, change.position.y + scrollState.value)
                                        selection = TextRange(layout.getOffsetForPosition(pos))
                                    }
                                }
                                change.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                LightScrollView(
                    scrollState = scrollState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicText(
                            text = state.text.toString(),
                            style = inputStyle,
                            onTextLayout = { textLayout = it },
                            maxLines = Int.MAX_VALUE,
                            softWrap = true,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(
                            modifier = Modifier.height(
                                INPUT_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp(),
                            ),
                        )
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(INPUT_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                                .background(colors.content),
                        )
                        Spacer(
                            modifier = Modifier.height(
                                with(LocalDensity.current) { (scrollState.viewportSize / 2).coerceAtLeast(0).toDp() }
                            )
                        )
                    }
                }
                textLayout?.let { layout ->
                    val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                    val rect = layout.getCursorRect(cursorPos)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(rect.left.toInt(), rect.top.toInt() - scrollState.value) }
                            .width(2.dp)
                            .height(with(LocalDensity.current) { rect.height.toDp() })
                            .background(colors.content),
                    )
                }
            }

            LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
        }
    }
}
