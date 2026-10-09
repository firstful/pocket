package com.sutliff.pocket

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun appContext(): Context = LocalContext.current