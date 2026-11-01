package com.vibesync.toolkit.pdf.presentation.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.vibesync.toolkit.pdf.BuildConfig
import com.vibesync.toolkit.pdf.data.billing.BillingManagerProvider
import com.vibesync.toolkit.pdf.data.billing.ProEntitlementState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Existing AdMob call sites remain source-compatible. MainActivity injects the one billing
 * manager it owns; this object never constructs a billing client or entitlement manager.
 */
object AdMobManager {
    private const val TAG = "AdMobManager"

    const val BANNER_TEST_ID = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL_TEST_ID = "ca-app-pub-3940256099942544/1033173712"
    const val BANNER_PROD_ID = "ca-app-pub-4783826505860771/3755922842"
    const val INTERSTITIAL_PROD_ID = ""

    private val emptyEntitlement = MutableStateFlow(ProEntitlementState(false, 0L, 0))
    val entitlementState: StateFlow<ProEntitlementState>
        get() = BillingManagerProvider.current()?.state ?: emptyEntitlement

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false
    private var lastKnownValidatedInternet: Boolean? = null
    private val _reconnectionGeneration = MutableStateFlow(0L)
    val reconnectionGeneration: StateFlow<Long> = _reconnectionGeneration

    val BANNER_AD_UNIT_ID: String
        get() = if (BuildConfig.DEBUG) BANNER_TEST_ID else BANNER_PROD_ID

    fun injectBillingManager(manager: com.vibesync.toolkit.pdf.data.billing.LocalAdBillingManager) {
        BillingManagerProvider.inject(manager)
    }

    fun checkAdStatusForThisDevice(): Boolean =
        BillingManagerProvider.current()?.checkAdStatusForThisDevice() == true

    fun onConnectivityChanged(context: Context, isValidated: Boolean) {
        val previous = lastKnownValidatedInternet
        lastKnownValidatedInternet = isValidated
        if (previous == false && isValidated) {
            _reconnectionGeneration.value = _reconnectionGeneration.value + 1
            if (!checkAdStatusForThisDevice() && interstitialAd == null && !isAdLoading) {
                preloadInterstitial(context.applicationContext)
            }
        }
    }

    fun getAdaptiveBannerSize(context: Context): AdSize {
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val adWidthPixels = displayMetrics.widthPixels.toFloat()
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
    }

    fun preloadInterstitial(context: Context) {
        if (checkAdStatusForThisDevice()) {
            interstitialAd = null
            return
        }
        if (interstitialAd != null || isAdLoading) return
        val interstitialUnitId = if (BuildConfig.DEBUG) INTERSTITIAL_TEST_ID else INTERSTITIAL_PROD_ID
        if (interstitialUnitId.isBlank()) {
            Log.w(TAG, "Production interstitial ad unit is not configured; skipping interstitial load.")
            return
        }

        isAdLoading = true
        InterstitialAd.load(
            context,
            interstitialUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isAdLoading = false
                    interstitialAd = if (checkAdStatusForThisDevice()) null else ad
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                    isAdLoading = false
                    Log.w(TAG, "Interstitial ad failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    fun showInterstitial(activity: Activity, onComplete: () -> Unit) {
        if (checkAdStatusForThisDevice()) {
            interstitialAd = null
            onComplete()
            return
        }
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    preloadInterstitial(activity)
                    onComplete()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    interstitialAd = null
                    preloadInterstitial(activity)
                    onComplete()
                }
            }
            ad.show(activity)
        } else {
            preloadInterstitial(activity)
            onComplete()
        }
    }
}

@Composable
fun BannerAd(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.BANNER_AD_UNIT_ID
) {
    val entitlement by AdMobManager.entitlementState.collectAsState()
    val proActive = entitlement.isProActive && AdMobManager.checkAdStatusForThisDevice()
    val lifecycleOwner = LocalLifecycleOwner.current
    val reconnectionGeneration by AdMobManager.reconnectionGeneration.collectAsState()
    var lastReloadGeneration by remember { mutableStateOf(0L) }
    var adViewRef by remember { mutableStateOf<AdView?>(null) }

    if (!proActive) {
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { context ->
                AdView(context).apply {
                    val adaptiveSize = AdMobManager.getAdaptiveBannerSize(context)
                    setAdSize(adaptiveSize)
                    this.adUnitId = adUnitId
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    if (!AdMobManager.checkAdStatusForThisDevice()) {
                        loadAd(AdRequest.Builder().build())
                    }
                    lastReloadGeneration = reconnectionGeneration
                    adViewRef = this
                }
            }
        )

        LaunchedEffect(reconnectionGeneration, adViewRef, proActive) {
            val adView = adViewRef
            if (!proActive && !AdMobManager.checkAdStatusForThisDevice() &&
                adView != null && reconnectionGeneration > lastReloadGeneration
            ) {
                adView.loadAd(AdRequest.Builder().build())
                lastReloadGeneration = reconnectionGeneration
            }
        }

        DisposableEffect(lifecycleOwner, adViewRef) {
            val adView = adViewRef
            if (adView == null) {
                onDispose { }
            } else {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> adView.pause()
                        Lifecycle.Event.ON_RESUME -> adView.resume()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    adView.destroy()
                    if (adViewRef === adView) adViewRef = null
                }
            }
        }
    }
}
