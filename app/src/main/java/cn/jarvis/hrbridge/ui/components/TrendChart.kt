package cn.jarvis.hrbridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cn.jarvis.hrbridge.ui.theme.HrRed

/**
 * 轻量 Canvas 折线图（无第三方库）。
 *
 * 绘制策略：
 * - 纵轴范围：min/max 围绕实际数据上下浮动，保留 10% 余量
 * - 横轴：样本均分
 * - >120 bpm 区间背景染红提示
 */
@Composable
fun TrendChart(
    data: List<Int>,
    modifier: Modifier = Modifier,
    elevatedThreshold: Int = 120
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            "近 30 分钟心率趋势",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (data.size < 2) {
            Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "数据不足",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val primary = MaterialTheme.colorScheme.primary
            val dangerZone = HrRed.copy(alpha = 0.12f)
            val min = (data.min() - 5).coerceAtLeast(30)
            val max = (data.max() + 5).coerceAtMost(220)
            val range = (max - min).coerceAtLeast(1)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val w = size.width
                val h = size.height
                val step = if (data.size > 1) w / (data.size - 1) else w

                // 高心率危险区背景
                val topY = h * (1f - (max - elevatedThreshold).toFloat() / range).coerceIn(0f, 1f)
                drawRect(
                    color = dangerZone,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(w, topY)
                )

                // 折线
                val path = Path()
                data.forEachIndexed { i, v ->
                    val x = i * step
                    val y = h * (1f - (v - min).toFloat() / range)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = primary,
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )

                // 最后一点高亮
                val lastIdx = data.size - 1
                val lastV = data.last()
                val lx = lastIdx * step
                val ly = h * (1f - (lastV - min).toFloat() / range)
                drawCircle(color = primary, radius = 6f, center = Offset(lx, ly))
                drawCircle(color = Color.White.copy(alpha = 0.5f), radius = 3f, center = Offset(lx, ly))
            }
        }
    }
}
