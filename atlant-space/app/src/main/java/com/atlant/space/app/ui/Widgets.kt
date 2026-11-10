package com.atlant.space.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A compact outlined action button used inside cards. */
@Composable
fun MiniButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Palette.Danger else Palette.Accent
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Divider),
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
fun TextPromptDialog(title: String, label: String, initial: String, onResult: (String?) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        containerColor = Palette.SurfaceElevated,
        title = { Text(title, color = Palette.TextPrimary) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Divider,
                    focusedLabelColor = Palette.Accent, unfocusedLabelColor = Palette.TextSecondary,
                    focusedTextColor = Palette.TextPrimary, unfocusedTextColor = Palette.TextPrimary,
                    cursorColor = Palette.Accent,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onResult(value.text.trim().ifEmpty { null }) }) {
                Text("OK", color = Palette.Accent)
            }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel", color = Palette.TextSecondary) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, danger: Boolean = false, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        containerColor = Palette.SurfaceElevated,
        title = { Text(title, color = Palette.TextPrimary) },
        text = { Text(message, color = Palette.TextSecondary) },
        confirmButton = {
            TextButton(onClick = { onResult(true) }) {
                Text(confirm, color = if (danger) Palette.Danger else Palette.Accent)
            }
        },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel", color = Palette.TextSecondary) } },
    )
}
