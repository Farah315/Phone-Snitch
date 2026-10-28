package com.farah.phonesnitch.domain

import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppClassification
import com.farah.phonesnitch.domain.model.AppPreference

object AppRules {

    /**
     * Fallback for day 1, before the AI has classified anything.
     * Once the AI returns a category for an app, the AI result wins.
     */
    private val knownSocialPackages = setOf(
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.twitter.android",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.pinterest",
    )

    fun categoryOf(packageName: String, classification: AppClassification?): AppCategory =
        classification?.category
            ?: if (packageName in knownSocialPackages) AppCategory.SOCIAL_MEDIA else AppCategory.OTHER

    fun isSocial(packageName: String, classification: AppClassification?): Boolean =
        categoryOf(packageName, classification) == AppCategory.SOCIAL_MEDIA

    /**
     * User category overrides replace the AI's category (confidence 1.0) but keep its peak_slots.
     * Overrides live in their own table, so the daily AI re-classification never erases them.
     */
    fun applyOverrides(
        classifications: Map<String, AppClassification>,
        preferences: Map<String, AppPreference>,
    ): Map<String, AppClassification> {
        val result = classifications.toMutableMap()
        for ((pkg, pref) in preferences) {
            val category = pref.categoryOverride ?: continue
            result[pkg] = AppClassification(
                packageName = pkg,
                category = category,
                confidence = 1f,
                peakSlots = classifications[pkg]?.peakSlots ?: emptySet(),
            )
        }
        return result
    }
}
