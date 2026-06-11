package com.meshchat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.meshchat.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val orbitronFont = GoogleFont("Orbitron")
val dmSansFont = GoogleFont("DM Sans")
val shareTechMonoFont = GoogleFont("Share Tech Mono")

val OrbitronFamily = FontFamily(
    Font(googleFont = orbitronFont, fontProvider = provider, weight = FontWeight.Black) // 900
)

val DMSansFamily = FontFamily(
    Font(googleFont = dmSansFont, fontProvider = provider, weight = FontWeight.Normal), // 400
    Font(googleFont = dmSansFont, fontProvider = provider, weight = FontWeight.Bold) // 700
)

val ShareTechMonoFamily = FontFamily(
    Font(googleFont = shareTechMonoFont, fontProvider = provider, weight = FontWeight.Normal)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = OrbitronFamily,
        fontWeight = FontWeight.Black,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DMSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = DMSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ShareTechMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
