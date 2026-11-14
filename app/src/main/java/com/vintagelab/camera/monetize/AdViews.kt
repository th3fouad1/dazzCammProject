package com.vintagelab.camera.monetize

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.vintagelab.camera.BuildConfig
import com.vintagelab.camera.R
import com.vintagelab.camera.ui.theme.VlColors

/** SPEC 4.2: anchored adaptive banner, full width; its height comes from AdMob for this width. */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
    val size = remember(widthDp) { AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp) }
    val heightDp = with(density) { size.getHeightInPixels(context).toDp() }
    val view = remember(size) {
        AdView(context).apply {
            adUnitId = BuildConfig.AD_BANNER
            setAdSize(size)
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(view) { onDispose { view.destroy() } }
    AndroidView(factory = { view }, modifier = modifier.fillMaxWidth().height(heightDp))
}

/** SPEC 4.2: a native ad card in the gallery grid, styled like the app, with the required "Ad" badge. */
@Composable
fun NativeAdCard(ad: NativeAd, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> buildNativeView(ctx) },
        update = { v -> if (v.tag !== ad) { v.tag = ad; bind(v, ad) } },
        modifier = modifier.fillMaxWidth().height(136.dp),
    )
}

private fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

private fun buildNativeView(ctx: Context): NativeAdView {
    val ink = VlColors.Ink.toArgb()
    val muted = VlColors.InkMuted.toArgb()
    val amber = VlColors.Amber.toArgb()
    val root = NativeAdView(ctx)
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        background = GradientDrawable().apply { setColor(VlColors.Raised.toArgb()); cornerRadius = ctx.dp(8).toFloat() }
    }
    val media = MediaView(ctx).apply { id = R.id.native_media }
    row.addView(media, LinearLayout.LayoutParams(ctx.dp(120), ctx.dp(120)))
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), 0, 0, 0) }
    val badge = TextView(ctx).apply {
        text = ctx.getString(R.string.ad_badge)
        setTextColor(VlColors.Bench.toArgb())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(ctx.dp(4), ctx.dp(1), ctx.dp(4), ctx.dp(1))
        background = GradientDrawable().apply { setColor(amber); cornerRadius = ctx.dp(3).toFloat() }
    }
    col.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val headline = TextView(ctx).apply {
        id = R.id.native_headline
        setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(0, ctx.dp(4), 0, 0)
    }
    col.addView(headline)
    val body = TextView(ctx).apply {
        id = R.id.native_body
        setTextColor(muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    col.addView(body)
    val cta = TextView(ctx).apply {
        id = R.id.native_cta
        setTextColor(amber); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.dp(6), 0, 0)
    }
    col.addView(cta)
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.mediaView = media
    root.headlineView = headline
    root.bodyView = body
    root.callToActionView = cta
    return root
}

private fun bind(v: NativeAdView, ad: NativeAd) {
    (v.headlineView as TextView).text = ad.headline
    (v.bodyView as TextView).apply { text = ad.body; visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE }
    (v.callToActionView as TextView).apply { text = ad.callToAction; visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE }
    v.setNativeAd(ad)
}
