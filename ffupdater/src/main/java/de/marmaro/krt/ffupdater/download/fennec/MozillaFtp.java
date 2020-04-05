package de.marmaro.krt.ffupdater.download.fennec;


import android.util.Log;

import com.google.common.base.Optional;

import java.io.IOException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import de.marmaro.krt.ffupdater.App;
import de.marmaro.krt.ffupdater.DeviceABI;

/**
 * Get the version name and the download link for the latest Fennec release (release, beta, nightly)
 * from their FTP service. Works a bit more better, but is not official supported.
 */
public class MozillaFtp {
    public static Optional<String> getDownloadUrl(App app, DeviceABI.ABI abi, OfficialApi.Version version) {
        String url;
        switch (app) {
            case FENNEC_RELEASE:
                url = getReleaseDownloadUrl(abi, version);
                break;
            case FENNEC_BETA:
                url = getBetaDownloadUrl(abi, version);
                break;
            case FENNEC_NIGHTLY:
                url = getNightlyDownloadUrl(abi, version);
                break;
            default:
                throw new IllegalArgumentException("unsupported abi " + abi);
        }

        if (isDownloadLinkAvailable(url)) {
            return Optional.of(url);
        }
        return Optional.absent();
    }

    private static String getReleaseDownloadUrl(DeviceABI.ABI abi, OfficialApi.Version response) {
        String version = response.getReleaseVersion();
        String folderName = getFolderName(abi);
        String fileSuffix = getFileSuffix(abi);

        String template = "https://ftp.mozilla.org/pub/mobile/releases/%s/android-%s/multi/fennec-%s.multi.android-%s.apk";
        return String.format(template, version, folderName, version, fileSuffix);
    }

    private static String getBetaDownloadUrl(DeviceABI.ABI abi, OfficialApi.Version response) {
        String version = response.getBetaVersion();
        String folderName = getFolderName(abi);
        String fileSuffix = getFileSuffix(abi);

        String template = "https://ftp.mozilla.org/pub/mobile/releases/%s/android-%s/multi/fennec-%s.multi.android-%s.apk";
        return String.format(template, version, folderName, version, fileSuffix);
    }

    private static String getNightlyDownloadUrl(DeviceABI.ABI abi, OfficialApi.Version response) {
        String version = response.getNightlyVersion();
        String esr = version.split("\\.")[0];
        String folderName = getFolderName(abi);
        String fileSuffix = getFileSuffix(abi);

        String template = "https://ftp.mozilla.org/pub/mobile/nightly/latest-mozilla-esr%s-android-%s/fennec-%s.multi.android-%s.apk";
        return String.format(template, esr, folderName, version, fileSuffix);
    }

    private static String getFolderName(DeviceABI.ABI abi) {
        switch (abi) {
            case AARCH64:
                return "aarch64";
            case ARM:
                return "api-16";
            case X86:
                return "x86";
            case X86_64:
                return "x86_64";
        }
        throw new IllegalArgumentException("unsupported abi " + abi);
    }

    private static String getFileSuffix(DeviceABI.ABI abi) {
        switch (abi) {
            case AARCH64:
                return "aarch64";
            case ARM:
                return "arm";
            case X86:
                return "i386";
            case X86_64:
                return "x86_64";
        }
        throw new IllegalArgumentException("unsupported abi " + abi);
    }

    private static boolean isDownloadLinkAvailable(String url) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.connect();
            return connection.getResponseCode() == HttpsURLConnection.HTTP_OK;
        } catch (IOException e) {
            Log.e("", "cant validate download link " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }
}
