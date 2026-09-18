package com.videoforge.nativeeditor

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Handles the opt-in rewarded ad used to remove the free-version watermark for one export.
 *
 * The unit ID below is Google's official test rewarded unit. Replace it with the
 * production AdMob rewarded unit before publishing the app.
 */
object WatermarkRewardManager {
    private const val TAG = "WatermarkReward"
    private const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    @Volatile
    private var rewardedAd: RewardedAd? = null
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
        if (isLoading || rewardedAd != null) return
        isLoading = true
        RewardedAd.load(
            context.applicationContext,
            TEST_REWARDED_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    Log.d(TAG, "Rewarded ad unavailable: ${error.message}")
                }
            }
        )
    }

    fun show(
        activity: Activity,
        onRewarded: () -> Unit,
        onUnavailable: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) {
            load(activity)
            onUnavailable()
            return
        }

        rewardedAd = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                load(activity)
                if (!earned) onUnavailable()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                load(activity)
                onUnavailable()
            }
        }

        ad.show(activity) { _: RewardItem ->
            earned = true
            onRewarded()
        }
    }
}
