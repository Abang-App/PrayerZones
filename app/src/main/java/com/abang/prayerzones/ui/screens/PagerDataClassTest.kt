package com.abang.prayerzones.ui.screens

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

private const val DATA_CLASS_TEST_TAG = "PagerDataClassTest"

// A simple, non-Hilt data class.
data class DummyMosque(val id: Int, val name: String)

@Composable
fun DummyMosqueScreen(mosque: DummyMosque) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Mosque: ${mosque.name}", fontSize = 24.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagerDataClassTest() {
    val items = listOf(DummyMosque(1, "Al-Khair"), DummyMosque(2, "An-Nur"))
    Log.d(DATA_CLASS_TEST_TAG, "Composing with hard-coded data class list: ${items.size}")

    val pagerState = rememberPagerState(pageCount = { items.size })

    HorizontalPager(state = pagerState) { page ->
        Log.d(DATA_CLASS_TEST_TAG, "Displaying page $page for mosque: ${items[page].name}")
        DummyMosqueScreen(mosque = items[page])
    }
}

@Preview(showBackground = true)
@Composable
fun PagerDataClassTestPreview() {
    PagerDataClassTest()
}