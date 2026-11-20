package com.laterbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.laterbox.app.ui.theme.LaterboxTheme

/**
 * Synchronizes an external string [StateFlow] with a Compose [TextFieldValue] without capturing stale
 * text or reverting fast multi-word deletions / trailing spaces when upstream emissions lag or echo
 * out of order.
 */
@Stable
class SyncedTextFieldController internal constructor(
    initialText: String
) {
    var value by mutableStateOf(
        TextFieldValue(
            text = initialText,
            selection = TextRange(initialText.length)
        )
    )
        private set

    private var lastObservedExternalText: String = initialText
    private val unconfirmedLocalTexts = ArrayDeque<String>()

    internal fun syncExternal(externalText: String) {
        if (externalText == lastObservedExternalText) return
        lastObservedExternalText = externalText

        val echoIndex = unconfirmedLocalTexts.indexOf(externalText)
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) {
                unconfirmedLocalTexts.removeFirst()
            }
            if (unconfirmedLocalTexts.isNotEmpty()) {
                return
            }
        } else {
            unconfirmedLocalTexts.clear()
        }

        if (externalText != value.text) {
            value = TextFieldValue(
                text = externalText,
                selection = TextRange(externalText.length)
            )
        }
    }

    fun onLocalValueChange(next: TextFieldValue, onExternalChange: (String) -> Unit) {
        val textChanged = next.text != value.text
        value = next
        if (textChanged) {
            if (unconfirmedLocalTexts.size >= 32) {
                unconfirmedLocalTexts.removeFirst()
            }
            unconfirmedLocalTexts.addLast(next.text)
            onExternalChange(next.text)
        }
    }

    fun clearLocal(onExternalClear: () -> Unit) {
        value = TextFieldValue("")
        lastObservedExternalText = ""
        unconfirmedLocalTexts.clear()
        onExternalClear()
    }
}

@Composable
fun rememberSyncedTextFieldController(externalText: String): SyncedTextFieldController {
    val controller = remember { SyncedTextFieldController(externalText) }
    controller.syncExternal(externalText)
    return controller
}

/**
 * Standardised search bar control with tactile borders, 12dp rounded corners,
 * leading search icon, and quick clear action.
 */
@Composable
fun LaterboxSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: () -> Unit = { onQueryChange("") }
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchController = rememberSyncedTextFieldController(query)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(spacing.buttonHeight)
            .clip(LaterboxTheme.shapes.InputField)
            .background(colors.surfacePrimary)
            .border(spacing.hairline, colors.borderSubtle, LaterboxTheme.shapes.InputField)
            .padding(horizontal = spacing.md),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = LaterboxIcons.Search),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall + 2.dp),
                tint = colors.textSecondary
            )
            Spacer(modifier = Modifier.width(spacing.sm))

            Box(modifier = Modifier.weight(1f)) {
                if (searchController.value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = typography.bodySecondary,
                        color = colors.textSecondary.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = searchController.value,
                    onValueChange = { next ->
                        searchController.onLocalValueChange(next, onQueryChange)
                    },
                    singleLine = true,
                    textStyle = typography.bodyPrimary.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (searchController.value.text.isNotEmpty() || query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(LaterboxTheme.shapes.IconButton)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Clear search",
                            onClick = { searchController.clearLocal(onClear) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = LaterboxIcons.Close),
                        contentDescription = "Clear search",
                        modifier = Modifier.size(spacing.iconSmall),
                        tint = colors.textSecondary
                    )
                }
            }
        }
    }
}
