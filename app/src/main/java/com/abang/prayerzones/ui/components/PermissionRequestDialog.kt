package com.abang.prayerzones.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun PermissionRequestDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Settings",
    dismissText: String = "Not Now"
) {
    var showDialog by remember { mutableStateOf(true) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                onDismiss()
            },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    onConfirm()
                }) {
                    Text(confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    onDismiss()
                }) {
                    Text(dismissText)
                }
            }
        )
    }
}