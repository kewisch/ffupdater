package de.marmaro.krt.ffupdater.dialog;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.fragment.app.DialogFragment;

import java.util.List;

import de.marmaro.krt.ffupdater.App;
import de.marmaro.krt.ffupdater.R;
import de.marmaro.krt.ffupdater.device.DeviceABI;
import de.marmaro.krt.ffupdater.device.InstalledApps;

/**
 * Created by Tobiwan on 23.08.2019.
 */
public class InstallAppDialog extends DialogFragment {
    public static final String TAG = "download_new_app_dialog";

    private final Consumer<App> downloadCallback;

    public InstallAppDialog(Consumer<App> callback) {
        this.downloadCallback = callback;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        List<App> apps = InstalledApps.getNotInstalledApps(requireActivity().getPackageManager());
        CharSequence[] appNames = new CharSequence[apps.size()];
        for (int i = 0; i < appNames.length; i++) {
            appNames[i] = apps.get(i).getTitle(requireContext());
        }
        return new AlertDialog.Builder(getActivity())
                .setTitle(R.string.download_new_app)
                .setItems(appNames, (dialog, which) -> {
                    App app = apps.get(which);
                    if (app.getUnsupportedAbis().contains(DeviceABI.getBestSuitedAbi())) {
                        new UnsupportedAbiDialog().show(getParentFragmentManager(), UnsupportedAbiDialog.TAG);
                        return;
                    }
                    if (!app.getWarning(requireContext()).isEmpty()) {
                        new InstallationWarningAppDialog(downloadCallback, app).show(getParentFragmentManager(), InstallationWarningAppDialog.TAG);
                        return;
                    }
                    downloadCallback.accept(app);
                })
                .create();
    }
}