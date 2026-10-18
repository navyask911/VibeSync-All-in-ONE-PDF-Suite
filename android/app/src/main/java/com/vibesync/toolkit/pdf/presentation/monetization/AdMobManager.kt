package com.vibesync.toolkit.pdf.presentation.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.vibesync.toolkit.pdf.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AdMob monetization layer adhering to Google Play Data Safety standards:
 * Local execution with standard SDK mediation and zero custom cloud transmission.
 */
object AdMobManager {

    private const val TAG = "AdMobManager"

    // Official Google Test Ad Unit IDs (used during local debug/testing to prevent policy violations)
    const val BANNER_TEST_ID = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL_TEST_ID = "ca-app-pub-3940256099942544/1033173712"

    // Official Production Ad Unit IDs
    const val BANNER_PROD_ID = "ca-app-pub-4783826505860771/3755922842"

    /**
     * Resolves the active Banner Ad Unit ID based on the build variant.
     * Uses test ID in debug builds to prevent AdMob invalid traffic strikes,
     * and official production unit ID in release builds.
     */
    val BANNER_AD_UNIT_ID: String
        get() = if (BuildConfig.DEBUG) BANNER_TEST_ID else BANNER_PROD_ID

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false
    private var lastKnownValidatedInternet: Boolean? = null
    private val _reconnectionGeneration = MutableStateFlow(0L)
    val reconnectionGeneration: StateFlow<Long> = _reconnectionGeneration

    /**
     * Reuse MainActivity's single connectivity callback. Retry failed ad requests only after
     * a confirmed offline-to-online transition; never loop/reload while the device is offline.
     */
    fun onConnectivityChanged(context: Context, isValidated: Boolean) {
        val previous = lastKnownValidatedInternet
        lastKnownValidatedInternet = isValidated
        if (previous == false && isValidated) {
            _reconnectionGeneration.value = _reconnectionGeneration.value + 1
            if (interstitialAd == null && !isAdLoading) preloadInterstitial(context.applicationContext)
        }
    }

    /**
     * Calculates the orientation-anchored adaptive banner size based on device screen width.
     */
    fun getAdaptiveBannerSize(context: Context): AdSize {
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val adWidthPixels = displayMetrics.widthPixels.toFloat()
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
    }

    /**
     * Preloads an Interstitial Ad ready for display after heavy task completions.
     */
    fun preloadInterstitial(context: Context) {
        if (interstitialAd != null || isAdLoading) return

        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            INTERSTITIAL_TEST_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isAdLoading = false
                    Log.d(TAG, "Interstitial ad loaded successfully.")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                    isAdLoading = false
                    Log.w(TAG, "Interstitial ad failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    /**
     * Shows the interstitial ad on task completion (e.g. Merge/Split/Export finished)
     * and triggers the onComplete callback.
     */
    fun showInterstitial(activity: Activity, onComplete: () -> Unit) {
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

/**
 * Standard Adaptive BannerAd Composable wrapper with full lifecycle integration
 * (loadAd, pause, resume, destroy).
 */
@Composable
fun BannerAd(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.BANNER_AD_UNIT_ID
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val reconnectionGeneration by AdMobManager.reconnectionGeneration.collectAsState()
    var lastReloadGeneration by remember { mutableStateOf(0L) }
    var adViewRef by remember { mutableStateOf<AdView?>(null) }

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
                loadAd(AdRequest.Builder().build())
                lastReloadGeneration = reconnectionGeneration
                adViewRef = this
            }
        }
    )

    // Reload an existing banner once after a confirmed offline-to-online transition.
    LaunchedEffect(reconnectionGeneration, adViewRef) {
        val adView = adViewRef
        if (adView != null && reconnectionGeneration > lastReloadGeneration) {
            adView.loadAd(AdRequest.Builder().build())
            lastReloadGeneration = reconnectionGeneration
        }
    }

    // Handle AdView lifecycle events safely to avoid memory leaks
    DisposableEffect(lifecycleOwner, adViewRef) {
        val adView = adViewRef
        if (adView == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> adView.pause()
                    Lifecycle.Event.ON_RESUME -> adView.resume()
                    Lifecycle.Event.ON_DESTROY -> adView.destroy()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                adView.destroy()
            }
        }
    }
}
