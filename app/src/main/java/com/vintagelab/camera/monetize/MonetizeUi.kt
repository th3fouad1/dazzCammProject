package com.vintagelab.camera.monetize

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vintagelab.camera.App
import com.vintagelab.camera.R
import com.vintagelab.camera.ui.icons.VlIcons
import com.vintagelab.camera.ui.theme.VlColors

/** The app's monetization singletons, from any composable. */
@Composable
fun rememberMonetize(): Monetize {
    val app = LocalContext.current.applicationContext as App
    return remember(app) { app.monetize }
}

/**
 * SPEC 4.1: shown when the shutter (or the editor's Save) is pressed on a locked look. "Watch ad" plays a rewarded ad and
 * unlocks [lookId] for [AdConfig.rewardUnlockHours]; "Get Pro" opens the Pro screen.
 */
@Composable
fun UnlockDialog(lookId: String, lookName: String, onClose: () -> Unit, onPro: () -> Unit) {
    val monetize = rememberMonetize()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val cfg by monetize.remote.config.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!loading) onClose() },
        containerColor = VlColors.Deck,
        titleContentColor = VlColors.Ink,
        textContentColor = VlColors.InkMuted,
        title = { Text(stringResource(R.string.lock_title, lookName)) },
        text = {
            Column {
                Text(stringResource(R.string.lock_body, cfg.rewardUnlockHours))
                if (loading) {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = VlColors.Amber, strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        Text(stringResource(R.string.lock_loading_ad), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && activity != null, onClick = {
                val a = activity ?: return@TextButton
                loading = true
                monetize.ads.showRewarded(a) { earned ->
                    loading = false
                    if (earned) {
                        monetize.entitlements.unlock(lookId, cfg.rewardUnlockHours)
                        Toast.makeText(context, context.getString(R.string.msg_look_unlocked, lookName, cfg.rewardUnlockHours), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.msg_ad_not_finished, Toast.LENGTH_SHORT).show()
                    }
                    onClose()
                }
            }) { Text(stringResource(R.string.lock_watch_ad), color = VlColors.Amber) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !loading, onClick = { onClose(); onPro() }) { Text(stringResource(R.string.lock_get_pro), color = VlColors.Ink) }
                TextButton(enabled = !loading, onClick = onClose) { Text(stringResource(R.string.lock_not_now), color = VlColors.InkMuted) }
            }
        },
    )
}

/** SPEC 4.3: the Pro screen. Plans and prices come from Play; nothing is hardcoded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProScreen(lookCount: Int, onBack: () -> Unit) {
    val monetize = rememberMonetize()
    val activity = LocalActivity.current
    val billing by monetize.billing.state.collectAsStateWithLifecycle()
    val ent by monetize.entitlements.state.collectAsStateWithLifecycle()
    var plan by remember { mutableStateOf(ProPlan.Yearly) }
    BackHandler(onBack = onBack)
    // Reconnect if Play was unavailable when the app started.
    androidx.compose.runtime.LaunchedEffect(Unit) { monetize.billing.connect() }

    Column(Modifier.fillMaxSize().background(VlColors.Bench)) {
        TopAppBar(
            title = { Text(stringResource(R.string.pro_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(VlIcons.Back, stringResource(R.string.cd_back), tint = VlColors.Ink) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VlColors.Deck, titleContentColor = VlColors.Ink),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.pro_headline), color = VlColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Benefit(stringResource(R.string.pro_benefit_looks, lookCount))
            Benefit(stringResource(R.string.pro_benefit_ads))
            Benefit(stringResource(R.string.pro_benefit_support))
            Spacer(Modifier.height(8.dp))
            when {
                ent.pro -> {
                    Text(stringResource(R.string.pro_active), color = VlColors.Amber, fontSize = 18.sp)
                    TextButton(onClick = { activity?.let { openSubscriptions(it) } }) { Text(stringResource(R.string.pro_manage), color = VlColors.Ink) }
                }
                billing.pending -> Text(stringResource(R.string.pro_pending), color = VlColors.Ink)
                billing.offers.isEmpty() && (!billing.queried) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = VlColors.Amber, strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                    Text(stringResource(R.string.pro_loading), color = VlColors.InkMuted, modifier = Modifier.padding(start = 12.dp))
                }
                billing.offers.isEmpty() -> Text(stringResource(R.string.pro_unavailable), color = VlColors.InkMuted)
                else -> {
                    if (plan !in billing.offers) plan = billing.offers.keys.first()
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in ProPlan.entries) {
                            val o = billing.offers[p] ?: continue
                            PlanRow(o, selected = p == plan, best = p == ProPlan.Yearly) { plan = p }
                        }
                    }
                    Button(
                        onClick = { activity?.let { monetize.billing.buy(it, plan) } },
                        colors = ButtonDefaults.buttonColors(containerColor = VlColors.Amber, contentColor = VlColors.Bench),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(stringResource(R.string.pro_continue), fontWeight = FontWeight.Bold) }
                    Text(stringResource(R.string.pro_terms), color = VlColors.InkMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 12.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(VlColors.Amber))
        Text(text, color = VlColors.Ink, fontSize = 16.sp)
    }
}

@Composable
private fun PlanRow(o: PlanOffer, selected: Boolean, best: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val name = stringResource(
        when (o.plan) { ProPlan.Monthly -> R.string.pro_plan_monthly; ProPlan.Yearly -> R.string.pro_plan_yearly; ProPlan.Lifetime -> R.string.pro_plan_lifetime },
    )
    val price = stringResource(
        when (o.plan) { ProPlan.Monthly -> R.string.pro_price_month; ProPlan.Yearly -> R.string.pro_price_year; ProPlan.Lifetime -> R.string.pro_price_once },
        o.price,
    )
    Row(
        Modifier.fillMaxWidth().clip(shape).background(VlColors.Raised)
            .border(if (selected) 2.dp else 1.dp, if (selected) VlColors.Amber else VlColors.Hairline, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = VlColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(price, color = VlColors.InkMuted, fontSize = 14.sp)
            if (o.trial != null) Text(stringResource(R.string.pro_free_trial), color = VlColors.Amber, fontSize = 13.sp)
        }
        if (best) {
            Text(
                stringResource(R.string.pro_best_value), color = VlColors.Bench, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(VlColors.Amber).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun openSubscriptions(activity: Activity) {
    val uri = "https://play.google.com/store/account/subscriptions?package=${activity.packageName}".toUri()
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        // no browser or Play Store
    }
}
