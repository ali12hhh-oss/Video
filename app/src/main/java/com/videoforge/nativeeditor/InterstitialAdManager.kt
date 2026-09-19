package com.videoforge.nativeeditor

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Preloads and shows the interstitial used when the user chooses
 * "Save to phone" from AI Photo Studio.
 *
 * The current unit is Google's official test interstitial. Replace it
 * with the production AdMob unit before release.
 */
object InterstitialAdManager {
    private const val TAG = "VideoForgeInterstitial"
    private const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    @Volatile
    private var interstitialAd: InterstitialAd? = null
    @Volatile
    private var isLoading = false
    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) {
            load(context)
            return
        }
        initialized = true
        MobileAds.initialize(context.applicationContext) {
            load(context)
        }
    }

    fun load(context: Context) {
        if (isLoading || interstitialAd != null) return
        isLoading = true
        InterstitialAd.load(
            context.applicationContext,
            TEST_INTERSTITIAL_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                    Log.d(TAG, "Interstitial unavailable: ${error.message}")
                }
            }
        )
    }

    /**
     * Runs [onFinished] after the ad is dismissed.
     * If an ad is not ready, the save action continues immediately.
     */
    fun showBeforeAction(
        activity: Activity,
        onFinished: () -> Unit
    ) {
        val ad = interstitialAd
        if (ad == null) {
            load(activity)
            onFinished()
            return
        }

        interstitialAd = null
        var dismissed = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                dismissed = true
                load(activity)
                onFinished()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.d(TAG, "Interstitial failed to show: ${error.message}")
                load(activity)
                onFinished()
            }
        }

        ad.show(activity)
    }
}
