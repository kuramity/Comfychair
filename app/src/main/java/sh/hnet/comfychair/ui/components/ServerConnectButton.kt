package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.screens.ConnectionState
import sh.hnet.comfychair.ui.theme.Dimensions

/**
 * Connect button that displays connection status with appropriate styling.
 *
 * @param connectionState Current connection state
 * @param enabled Whether the button is enabled
 * @param onClick Called when the button is clicked
 * @param modifier Modifier for the button
 */
@Composable
fun ServerConnectButton(
    connectionState: ConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonColors = when (connectionState) {
        ConnectionState.IDLE -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        ConnectionState.CONNECTING -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.secondary
        )
        ConnectionState.FAILED -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
        ConnectionState.CONNECTED -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiary
        )
    }

    val buttonText = when (connectionState) {
        ConnectionState.IDLE -> stringResource(R.string.button_connect)
        ConnectionState.CONNECTING -> stringResource(R.string.button_connecting)
        ConnectionState.FAILED -> stringResource(R.string.button_failed)
        ConnectionState.CONNECTED -> stringResource(R.string.button_connected)
    }

    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        colors = buttonColors,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.ButtonHeight)
    ) {
        Text(
            text = buttonText,
            // 基线用 M3 按钮自身的内容样式 labelLarge（本项目 Type.kt 定的是 SemiBold、
            // 行高 20sp、字距 0.1sp），只把字号换成 token。不要换成 bodyLarge：
            // 那是正文样式（Regular、行高 24sp），按钮文字会明显变细（R2-F1）
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = Dimensions.ButtonTextSize
            )
        )
    }
}
