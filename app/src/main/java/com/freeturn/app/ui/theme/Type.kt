package com.freeturn.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Веса displaySmall/titleLarge намеренно тяжелее MD3-дефолта (Bold/SemiBold):
// верх экрана — главный визуальный якорь (статус, заголовки секций), Regular на
// 22sp/36sp смотрится вяло рядом с акцентным ProxyToggleButton. titleMedium
// (16sp) оставлен на дефолте — он широко используется в списках/карточках/
// section-headerах, и глобальный SemiBold даёт визуальный шум.
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
)
