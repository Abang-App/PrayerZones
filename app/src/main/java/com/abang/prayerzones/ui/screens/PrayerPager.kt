package com.abang.prayerzones.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.ui.components.Section11_PagerIndicator
import com.abang.prayerzones.viewmodel.MosqueDetailViewModel
import com.abang.prayerzones.viewmodel.PrayerViewModel
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrayerPager(
    mosques: List<Mosque>,
    parentViewModel: PrayerViewModel,
    focusSlotState: MutableState<Int?>
) {
    val pagerState = rememberPagerState(pageCount = { mosques.size })

    // Jump to requested slot if present (one-shot)
    LaunchedEffect(focusSlotState.value, mosques.size) {
        val target = focusSlotState.value
        if (target != null && target in 0 until mosques.size) {
            pagerState.animateScrollToPage(target)
            focusSlotState.value = null
        }
    }

    // Observe page changes and notify the parent ViewModel.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            parentViewModel.onPageChanged(page)
        }
    }

    // No Scaffold here - bottom bar is at top level now
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            val mosque = mosques[page]
            val isFirstMosque = (page == 0)

            // ✅ FIX #4: Create ViewModel explicitly with unique key for each page
            // This ensures proper isolation and prevents cross-mosque state pollution
            val viewModel: MosqueDetailViewModel = hiltViewModel(
                key = "mosque_${mosque.id}_${isFirstMosque}_page${page}"
            )

            MosqueScreen(
                mosque = mosque,
                isFirstMosque = isFirstMosque,
                slotIndex = page,
                viewModel = viewModel
            )
        }
        Section11_PagerIndicator(
            pagerState = pagerState,
            pageCount = mosques.size,
            modifier = Modifier.wrapContentHeight()
        )
    }
}