package de.marmaro.krt.ffupdater.settings

import android.content.SharedPreferences
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import de.marmaro.krt.ffupdater.BaseTest
import de.marmaro.krt.ffupdater.app.App
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.stream.Stream


@ExtendWith(MockKExtension::class)
class BackgroundSettingsTest : BaseTest() {

    private lateinit var sharedPreferences: SharedPreferences

    @BeforeEach
    fun setUp() {
        sharedPreferences = SPMockBuilder().createSharedPreferences()
        BackgroundSettings.init(sharedPreferences)
    }

    companion object {
        @JvmStatic
        fun testDataForBooleanSettings(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "isUpdateCheckEnabled",
                "background__update_check__enabled",
                true,
                { BackgroundSettings.isUpdateCheckEnabled }),
            Arguments.of(
                "isUpdateCheckOnMeteredAllowed",
                "background__update_check__metered",
                true,
                { BackgroundSettings.isUpdateCheckOnMeteredAllowed }),
            Arguments.of(
                "isUpdateCheckOnlyAllowedWhenDeviceIsIdle",
                "background__update_check__when_device_idle",
                false,
                { BackgroundSettings.isUpdateCheckOnlyAllowedWhenDeviceIsIdle }),
            Arguments.of(
                "isDownloadEnabled",
                "background__download__enabled",
                true,
                { BackgroundSettings.isDownloadEnabled }),
            Arguments.of(
                "isDownloadOnMeteredAllowed",
                "background__download__metered",
                false,
                { BackgroundSettings.isDownloadOnMeteredAllowed }),
            Arguments.of(
                "isInstallationEnabled",
                "background__installation__enabled",
                false,
                { BackgroundSettings.isInstallationEnabled }),
            Arguments.of(
                "isDeleteUpdateIfInstallSuccessful",
                "background__delete_cache_if_install_successful",
                true,
                { BackgroundSettings.isDeleteUpdateIfInstallSuccessful }),
            Arguments.of(
                "isDeleteUpdateIfInstallFailed",
                "background__delete_cache_if_install_failed",
                false,
                { BackgroundSettings.isDeleteUpdateIfInstallFailed }),
        )

        @JvmStatic
        fun excludedAppsFromBackgroundUpdateCheck_data(): Stream<Arguments> = Stream.of(
            Arguments.of(App.BRAVE, "BRAVE"),
            Arguments.of(App.BRAVE_BETA, "BRAVE_BETA"),
            Arguments.of(App.BRAVE_NIGHTLY, "BRAVE_NIGHTLY"),
            Arguments.of(App.BROMITE, "BROMITE"),
            Arguments.of(App.BROMITE_SYSTEMWEBVIEW, "BROMITE_SYSTEMWEBVIEW"),
            Arguments.of(App.FFUPDATER, "FFUPDATER"),
            Arguments.of(App.FIREFOX_BETA, "FIREFOX_BETA"),
            Arguments.of(App.FIREFOX_FOCUS, "FIREFOX_FOCUS"),
            Arguments.of(App.FIREFOX_KLAR, "FIREFOX_KLAR"),
            Arguments.of(App.FIREFOX_NIGHTLY, "FIREFOX_NIGHTLY"),
            Arguments.of(App.FIREFOX_RELEASE, "FIREFOX_RELEASE"),
            Arguments.of(App.KIWI, "KIWI"),
            Arguments.of(App.VIVALDI, "VIVALDI"),
        )
    }

    @ParameterizedTest(name = "has \"{0}\" the correct default value \"{2}\"")
    @MethodSource("testDataForBooleanSettings")
    fun `has boolean settings the correct default value`(
        name: String,
        preferenceKey: String,
        defaultValue: Boolean,
        getValue: () -> Boolean,
    ) {
        val actual = getValue()
        Assertions.assertEquals(defaultValue, actual)
    }

    @ParameterizedTest(name = "has \"{0}\" the correct value when changed to true")
    @MethodSource("testDataForBooleanSettings")
    fun `has boolean settings the correct value when changed to true`(
        name: String,
        preferenceKey: String,
        defaultValue: Boolean,
        getValue: () -> Boolean,
    ) {
        sharedPreferences.edit().putBoolean(preferenceKey, true).commit()
        val actual = getValue()
        Assertions.assertTrue(actual)
    }

    @ParameterizedTest(name = "has \"{0}\" the correct value when changed to false")
    @MethodSource("testDataForBooleanSettings")
    fun `has boolean settings the correct value when changed to false`(
        name: String,
        preferenceKey: String,
        defaultValue: Boolean,
        getValue: () -> Boolean,
    ) {
        sharedPreferences.edit().putBoolean(preferenceKey, false).commit()
        val actual = getValue()
        Assertions.assertFalse(actual)
    }

    @ParameterizedTest(name = "has \"{0}\" the correct value when changing values")
    @MethodSource("testDataForBooleanSettings")
    fun `has boolean settings the correct value when changing values`(
        name: String,
        preferenceKey: String,
        defaultValue: Boolean,
        getValue: () -> Boolean,
    ) {
        sharedPreferences.edit().putBoolean(preferenceKey, false).commit()
        Assertions.assertFalse(getValue())
        sharedPreferences.edit().putBoolean(preferenceKey, true).commit()
        Assertions.assertTrue(getValue())
    }

    @Test
    fun `backgroundUpdateCheckInterval with no settings`() {
        Assertions.assertEquals(
            Duration.ofHours(6),
            BackgroundSettings.updateCheckInterval
        )
    }

    @Test
    fun `backgroundUpdateCheckInterval with invalid setting null`() {
        sharedPreferences.edit().putString("background__update_check__interval", null).commit()
        Assertions.assertEquals(
            Duration.ofHours(6),
            BackgroundSettings.updateCheckInterval
        )
    }

    @Test
    fun `backgroundUpdateCheckInterval with invalid setting empty string`() {
        sharedPreferences.edit().putString("background__update_check__interval", "").commit()
        Assertions.assertEquals(
            Duration.ofHours(6),
            BackgroundSettings.updateCheckInterval
        )
    }

    @Test
    fun `backgroundUpdateCheckInterval with invalid setting string`() {
        sharedPreferences.edit().putString("background__update_check__interval", "lorem ipsum").commit()
        Assertions.assertEquals(
            Duration.ofHours(6),
            BackgroundSettings.updateCheckInterval
        )
    }

    @Test
    fun `backgroundUpdateCheckInterval with 42 minutes`() {
        sharedPreferences.edit().putString("background__update_check__interval", "42").commit()
        Assertions.assertEquals(
            Duration.ofMinutes(42),
            BackgroundSettings.updateCheckInterval
        )
    }

    @Test
    fun `backgroundUpdateCheckInterval with too low value`() {
        sharedPreferences.edit().putString("background__update_check__interval", "-1").commit()
        Assertions.assertEquals(
            Duration.ofMinutes(15),
            BackgroundSettings.updateCheckInterval
        )
    }

    @Test
    fun `backgroundUpdateCheckInterval with too high value`() {
        sharedPreferences.edit().putString("background__update_check__interval", "100000").commit()
        Assertions.assertEquals(
            Duration.ofDays(28),
            BackgroundSettings.updateCheckInterval
        )
    }


    @Test
    fun `excludedAppsFromBackgroundUpdateCheck with default value`() {
        Assertions.assertTrue(BackgroundSettings.excludedAppsFromUpdateCheck.isEmpty())
    }

    @Test
    fun `excludedAppsFromBackgroundUpdateCheck with null`() {
        sharedPreferences.edit().putStringSet("background__update_check__excluded_apps", null).commit()
        Assertions.assertTrue(BackgroundSettings.excludedAppsFromUpdateCheck.isEmpty())
    }

    @Test
    fun `excludedAppsFromBackgroundUpdateCheck with empty set`() {
        sharedPreferences.edit().putStringSet("background__update_check__excluded_apps", setOf()).commit()
        Assertions.assertTrue(BackgroundSettings.excludedAppsFromUpdateCheck.isEmpty())
    }

    @ParameterizedTest(name = "excludedAppsFromBackgroundUpdateCheck with app \"{0}\"")
    @MethodSource("excludedAppsFromBackgroundUpdateCheck_data")
    fun `excludedAppsFromBackgroundUpdateCheck with app X`(app: App, name: String) {
        sharedPreferences.edit()
            .putStringSet("background__update_check__excluded_apps", setOf(name))
            .commit()
        val disabledApps = BackgroundSettings.excludedAppsFromUpdateCheck
        Assertions.assertTrue(app in disabledApps)
    }

    @Test
    fun `excludedAppsFromBackgroundUpdateCheck with invalid value`() {
        sharedPreferences.edit().putStringSet("background__update_check__excluded_apps", setOf("invalid"))
            .commit()
        val disabledApps = BackgroundSettings.excludedAppsFromUpdateCheck
        Assertions.assertTrue(disabledApps.isEmpty())
    }

    @Test
    fun `excludedAppsFromBackgroundUpdateCheck with all apps`() {
        sharedPreferences.edit().putStringSet(
            "background__update_check__excluded_apps",
            setOf(
                "BRAVE",
                "BRAVE_BETA",
                "BRAVE_NIGHTLY",
                "BROMITE",
                "BROMITE_SYSTEMWEBVIEW",
                "CHROMIUM",
                "DUCKDUCKGO_ANDROID",
                "FENNEC_FDROID",
                "FFUPDATER",
                "FIREFOX_BETA",
                "FIREFOX_FOCUS",
                "FIREFOX_FOCUS_BETA",
                "FIREFOX_KLAR",
                "FIREFOX_NIGHTLY",
                "FIREFOX_RELEASE",
                "ICERAVEN",
                "KIWI",
                "LOCKWISE",
                "MULCH",
                "MULCH",
                "MULL",
                "MULL_FROM_REPO",
                "ORBOT",
                "PRIVACY_BROWSER",
                "TOR_BROWSER",
                "TOR_BROWSER_ALPHA",
                "UNGOOGLED_CHROMIUM",
                "VIVALDI",
            )
        ).commit()
        Assertions.assertEquals(
            App.values().toList().sorted(),
            BackgroundSettings.excludedAppsFromUpdateCheck.sorted()
        )
    }
}