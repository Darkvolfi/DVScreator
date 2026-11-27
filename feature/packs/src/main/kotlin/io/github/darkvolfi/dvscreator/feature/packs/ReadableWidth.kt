package io.github.darkvolfi.dvscreator.feature.packs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ekranı doldurur ama içerik en fazla [max] genişlikte ve ortada kalır: tablette ve yatay telefonda
 * kartlar ve düğmeler ekran boyu uzamasın.
 */
fun Modifier.readableWidth(max: Dp = 720.dp): Modifier =
    fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()
