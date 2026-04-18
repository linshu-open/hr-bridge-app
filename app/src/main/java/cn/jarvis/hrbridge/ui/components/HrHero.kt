package cn.jarvis.hrbridge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.jarvis.hrbridge.ui.theme.*

/**
 * 大号心率显示卡（心脏跳动动效 + 状态色）。
 *
 * [hr] 为 null 时显示 "--"。
 */
@Composable
fun HrHero(
    hr: Int?,
    connected: Boolean,
    deviceName: String?,
    modifier: Modifier = Modifier
) {
    val color = hrColor(hr)
    val pulse by animateFloatAsState(
        targetValue = if (hr != null && connected) 1.08f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(40.dp)
                    .scale(pulse)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = hr?.toString() ?: "--",
                style = MaterialTheme.typography.displayLarge,
                color = color
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "bpm",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (connected) HrGreen else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            )
            Text(
                text = when {
                    connected && deviceName != null -> "已连接 · $deviceName"
                    connected -> "已连接"
                    else -> "未连接"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun hrColor(hr: Int?): Color = when {
    hr == null       -> MaterialTheme.colorScheme.onSurfaceVariant
    hr < 60          -> HrBlue
    hr <= 100        -> HrGreen
    hr <= 120        -> HrOrange
    hr < 140         -> HrRed
    else             -> HrPurple
}
