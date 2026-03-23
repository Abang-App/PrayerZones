package com.abang.prayerzones.ui.screens

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp



@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagerSmokeTest() {
    // 1. Fixed list of three items
    val items = listOf("A", "B", "C")

    // 2. PagerState must know exact size via lambda
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount   = { items.size }
    )

    // 3. One-time log when composed
    LaunchedEffect(Unit) {
        Log.d("PagerSmokeTest", "Composing PagerSmokeTest with ${items.size} pages")
    }

    HorizontalPager(
        state    = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) { page ->
        // 4. Log each page as it’s displayed
        Log.d("PagerSmokeTest", "Displaying page ${items[page]}")

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Page Content for: ${items[page]}")
        }
    }
}


@Preview(showBackground = true)
@Composable
fun PagerSmokeTestPreview() {
    PagerSmokeTest()
}