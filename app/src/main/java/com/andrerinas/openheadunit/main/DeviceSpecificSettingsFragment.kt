package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.device.DeviceSpecificHeadUnitPlugins
import com.andrerinas.openheadunit.device.DeviceSpecificHeadUnitSettings
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceSpecificSettingsFragment : Fragment() {
    private lateinit var deviceSettings: DeviceSpecificHeadUnitSettings
    private lateinit var settingsAdapter: SettingsAdapter
    private var mcuAccessAvailable: Boolean? = null
    private var mcuCheckInProgress = false
    private var mcuCheckGeneration = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_device_specific_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        deviceSettings = DeviceSpecificHeadUnitSettings(requireContext())
        settingsAdapter = SettingsAdapter()
        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = settingsAdapter
        }
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            if (!findNavController().navigateUp()) requireActivity().finish()
        }
        updateSettingsList()
        checkMcuAccessIfSelected()
    }

    private fun updateSettingsList() {
        if (!::settingsAdapter.isInitialized || !::deviceSettings.isInitialized) return
        val available = mcuAccessAvailable == true
        val items = mutableListOf<SettingItem>()

        val models = DeviceSpecificHeadUnitSettings.Model.values()
        val currentModel = deviceSettings.model
        items.add(SettingItem.SettingEntry(
            stableId = "deviceSpecificHeadUnitModel",
            nameResId = R.string.device_specific_head_unit_model,
            value = getString(
                if (currentModel == DeviceSpecificHeadUnitSettings.Model.ADAYO_HAVAL) {
                    R.string.device_specific_model_adayo_haval
                } else {
                    R.string.device_specific_model_none
                }
            ),
            isEnabled = true,
            onClick = {
                val labels = arrayOf(
                    getString(R.string.device_specific_model_none),
                    getString(R.string.device_specific_model_adayo_haval)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.device_specific_head_unit_model)
                    .setSingleChoiceItems(labels, models.indexOf(currentModel)) { dialog, which ->
                        deviceSettings.model = models[which]
                        mcuCheckGeneration++
                        mcuAccessAvailable = null
                        mcuCheckInProgress = false
                        dialog.dismiss()
                        updateSettingsList()
                        checkMcuAccessIfSelected()
                    }
                    .show()
            }
        ))

        if (currentModel == DeviceSpecificHeadUnitSettings.Model.ADAYO_HAVAL) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "pauseProjectionOnReverse",
                nameResId = R.string.device_specific_pause_projection_reverse,
                descriptionResId = R.string.device_specific_pause_projection_reverse_description,
                isChecked = deviceSettings.pauseProjectionOnReverse,
                isEnabled = available,
                descriptionOverride = if (mcuCheckInProgress) {
                    getString(R.string.device_specific_mcu_checking)
                } else {
                    null
                },
                onCheckedChanged = { enabled ->
                    deviceSettings.pauseProjectionOnReverse = enabled
                }
            ))
            if (mcuAccessAvailable == false) {
                items.add(SettingItem.InfoBanner(
                    stableId = "mcuUnavailable",
                    textResId = R.string.device_specific_mcu_unavailable
                ))
            }
        }
        settingsAdapter.submitList(items)
    }

    private fun checkMcuAccessIfSelected() {
        if (deviceSettings.model != DeviceSpecificHeadUnitSettings.Model.ADAYO_HAVAL ||
            mcuAccessAvailable != null || mcuCheckInProgress
        ) return

        mcuCheckInProgress = true
        val checkGeneration = ++mcuCheckGeneration
        updateSettingsList()
        viewLifecycleOwner.lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) {
                DeviceSpecificHeadUnitPlugins.canAccessMcu()
            }
            if (checkGeneration != mcuCheckGeneration ||
                deviceSettings.model != DeviceSpecificHeadUnitSettings.Model.ADAYO_HAVAL
            ) return@launch
            mcuCheckInProgress = false
            mcuAccessAvailable = available
            updateSettingsList()
        }
    }
}
