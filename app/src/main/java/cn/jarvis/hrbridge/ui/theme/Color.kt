package cn.jarvis.hrbridge.ui.theme

import androidx.compose.ui.graphics.Color

// ---- 心率区间配色（蓝/绿/橙/红，匹配 v1.2.7 习惯） ----
val HrBlue   = Color(0xFF4DA6FF)   // hr < 60
val HrGreen  = Color(0xFF43D19E)   // 60-100 normal
val HrOrange = Color(0xFFFFA94D)   // 100-120 elevated
val HrRed    = Color(0xFFFF5A6B)   // >120 high
val HrPurple = Color(0xFFB794F4)   // critical

// ---- 深色主题 ----
val DarkBg         = Color(0xFF0E1116)
val DarkSurface    = Color(0xFF161B22)
val DarkSurfaceVar = Color(0xFF1E252E)
val DarkOnBg       = Color(0xFFE6EDF3)
val DarkOnSurface  = Color(0xFFC9D1D9)
val DarkMuted      = Color(0xFF8B949E)
val DarkOutline    = Color(0xFF30363D)

// ---- 亮色主题 ----
val LightBg         = Color(0xFFF7F8FA)
val LightSurface    = Color(0xFFFFFFFF)
val LightSurfaceVar = Color(0xFFEFF2F6)
val LightOnBg       = Color(0xFF0D1117)
val LightOnSurface  = Color(0xFF24292F)
val LightMuted      = Color(0xFF57606A)
val LightOutline    = Color(0xFFD0D7DE)
