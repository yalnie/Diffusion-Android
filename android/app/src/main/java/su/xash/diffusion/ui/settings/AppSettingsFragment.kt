package su.xash.diffusion.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import su.xash.diffusion.R

class AppSettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_app_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.settingsFragment) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settingsFragment, AppSettingsPreferenceFragment())
                .commit()
        }
    }
}
