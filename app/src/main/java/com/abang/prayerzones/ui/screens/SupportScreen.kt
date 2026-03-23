package com.abang.prayerzones.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.abang.prayerzones.model.Supporter
import com.abang.prayerzones.viewmodel.SupportViewModel

/**
 * Available payment platforms.
 */
private enum class PaymentMethod(val label: String) {
    Stripe("Stripe"),
    Revolut("Revolut"),
    KoFi("Ko-fi")
}

/**
 * Returns the payment URL for a given method and dollar amount.
 */
private fun donationUrl(method: PaymentMethod, dollars: Int): String = when (method) {
    PaymentMethod.Stripe -> when (dollars) {
        3    -> "https://buy.stripe.com/00w5kD8p896P0iu1xe7Re06"
        5    -> "https://buy.stripe.com/28E5kDcFofvdd5g2Bi7Re05"
        10   -> "https://buy.stripe.com/aFa14n7l4fvdaX81xe7Re07"
        25   -> "https://buy.stripe.com/3cI5kDcFo2Ire9k4Jq7Re08"
        else -> "https://buy.stripe.com/00w5kD8p896P0iu1xe7Re06" // default/fallback link $3 donnation
    }
    PaymentMethod.Revolut -> "https://revolut.me/mongi2djt/$dollars"
    PaymentMethod.KoFi    -> "https://ko-fi.com/AbangUstazMoon?amount=$dollars"
}

/**
 * Support Development screen — donation tiers, payment info, and supporters list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    viewModel: SupportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var selectedMethod by remember { mutableStateOf(PaymentMethod.Revolut) }
    val supporters by viewModel.supporters.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Pagination State
    var currentPage by remember { mutableIntStateOf(0) }
    val pageSize = 10
    
    // Sort logic handled in VM/Repository (newest first). 
    // Just slice the list for the current page.
    val currentPageSupporters = remember(supporters, currentPage) {
        val start = currentPage * pageSize
        if (start < supporters.size) {
            supporters.subList(start, minOf(start + pageSize, supporters.size))
        } else {
            emptyList()
        }
    }
    
    val totalPages = (supporters.size + pageSize - 1) / pageSize

    // Reset to page 0 if new supporter added (list grows)
    androidx.compose.runtime.LaunchedEffect(supporters.size) {
        if (supporters.isNotEmpty()) {
            currentPage = 0
            listState.animateScrollToItem(0)
        }
    }

    fun openDonation(dollars: Int) {
        val url = donationUrl(selectedMethod, dollars)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }


    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────
        item {
            Text(
                text = "☕ Support Development",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        item {
            Text(
                text = "If this app helps you, consider buying me a coffee.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── Payment method selector ─────────────────────────────────
        item {
            Text(
                text = "Pay with:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaymentMethod.entries.forEachIndexed { index, method ->
                    if (index > 0) Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = selectedMethod == method,
                        onClick = { selectedMethod = method },
                        label = { Text(method.label) }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── Donation Tiers ──────────────────────────────────────────
        item {
            DonationTierCard(
                emoji = "☕",
                title = "Coffee",
                amount = "$3",
                onClick = { openDonation(3) }
            )
        }

        item {
            DonationTierCard(
                emoji = "☕☕",
                title = "Double Coffee",
                amount = "$5",
                onClick = { openDonation(5) }
            )
        }

        item {
            DonationTierCard(
                emoji = "❤\uFE0F",
                title = "Support the Project",
                amount = "$10",
                onClick = { openDonation(10) }
            )
        }

        item {
            DonationTierCard(
                emoji = "⭐",
                title = "Sponsor",
                amount = "$25",
                onClick = { openDonation(25) }
            )
        }

        // ── Divider ─────────────────────────────────────────────────
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // ── Our Supporters ──────────────────────────────────────────
        item {
            Text(
                text = "❤️ Our Supporters",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(currentPageSupporters) { supporter ->
            SupporterRow(supporter = supporter)
        }

        // ── Pagination Controls ─────────────────────────────────────
        if (totalPages > 1) {
            item {
                Row(
                   modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                   horizontalArrangement = Arrangement.Center,
                   verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text("Previous")
                    }

                    Text(
                        text = "Page ${currentPage + 1} of $totalPages",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1,
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

/**
 * A Material 3 card representing a single donation tier.
 */
@Composable
private fun DonationTierCard(
    emoji: String,
    title: String,
    amount: String,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = emoji,
                // Scaled down to match list icons (20.sp)
                fontSize = 20.sp,
                modifier = Modifier.width(56.dp),
                maxLines = 1
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            Text(
                text = amount,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * A single supporter row using a split layout:
 * Left: Name & Date
 * Right: Tier Icon(s)
 */
@Composable
private fun SupporterRow(supporter: Supporter) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Side: Name and Date
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = supporter.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = supporter.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Right Side: Tier Icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            when (supporter.amount) {
                3 -> Text("☕", fontSize = 20.sp)
                5 -> {
                    Text("☕", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("☕", fontSize = 20.sp)
                }
                10 -> Text("❤️", fontSize = 20.sp)
                25 -> Text("⭐", fontSize = 20.sp)
                else -> {
                    // Fallback or generic donation
                    if (supporter.amount > 0) Text("☕", fontSize = 20.sp)
                }
            }
        }
    }
}
