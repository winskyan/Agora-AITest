package io.agora.ai.test.ui.dialog

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BottomPopupView
import io.agora.ai.test.R
import io.agora.ai.test.constants.Constants
import io.agora.ai.test.context.DemoContext
import io.agora.ai.test.databinding.DialogSettingsBinding
import io.agora.ai.test.model.Config
import io.agora.ai.test.utils.Utils


object SettingsDialog {
    const val TAG: String = Constants.TAG + "-SettingsDialog"


    fun showSettingsDialog(context: Context) {
        XPopup.Builder(context)
            .hasBlurBg(true)
            .autoDismiss(false)
            .popupHeight((Utils.getScreenHeight(context) * 0.9).toInt())
            .asCustom(object : BottomPopupView(context) {

                override fun getImplLayoutId(): Int {
                    return R.layout.dialog_settings;
                }

                override fun initPopupContent() {
                    super.initPopupContent()
                    val binding = DialogSettingsBinding.bind(popupImplView)

                    val config =
                        DemoContext.parseConfigJson(Utils.readAssetContent(context, "config.json"))
                    Log.d(TAG, "config: $config")

                    binding.enableRtmCb.isChecked = DemoContext.isEnableRtm()
                    binding.enableRtmCb.setOnCheckedChangeListener { _, isChecked ->
                        DemoContext.setEnableRtm(isChecked)
                    }

                    binding.enableStereoTestCb.isChecked = DemoContext.isEnableStereoTest()
                    binding.enableStereoTestCb.setOnCheckedChangeListener { _, isChecked ->
                        DemoContext.setEnableStereoTest(isChecked)
                    }

                    binding.enableSaveAudioCb.isChecked = DemoContext.isEnableSaveAudio()
                    binding.enableSaveAudioCb.setOnCheckedChangeListener { _, isChecked ->
                        DemoContext.setEnableSaveAudio(isChecked)
                    }

                    val constraintLayout = findViewById<ConstraintLayout>(R.id.params_layout)
                    createCheckboxes(constraintLayout, config, context)

                    val audioProfileLayout =
                        findViewById<ConstraintLayout>(R.id.audio_profile_layout)
                    val audioScenarioLayout =
                        findViewById<ConstraintLayout>(R.id.audio_scenario_layout)
                    createRadioButtons(audioProfileLayout, context, config.audioProfile, true)
                    createRadioButtons(audioScenarioLayout, context, config.audioScenario, false)

                    binding.okBtn.setOnClickListener {
                        dismiss()
                    }

                }

            })
            .show();
    }

    private fun createCheckboxes(
        constraintLayout: ConstraintLayout,
        config: Config,
        context: Context
    ) {
        var viewIndex = 0
        val selectedParams = DemoContext.getParams().toMutableSet()
        config.params.forEach { paramsObj ->
            paramsObj.forEach { (key, valueList) ->
                (valueList as List<String>).forEachIndexed { index, param ->
                    viewIndex++
                    val checkBox = CheckBox(context).apply {
                        id = View.generateViewId()
                        text = "$key: $param"
                        isChecked = selectedParams.contains(param)
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                DemoContext.addParams(param)
                            } else {
                                DemoContext.removeParams(param)
                            }
                        }
                    }

                    constraintLayout.addView(checkBox)
                    val constraintSet = ConstraintSet()
                    constraintSet.clone(constraintLayout)
                    if (viewIndex == 1) {
                        constraintSet.connect(
                            checkBox.id,
                            ConstraintSet.TOP,
                            R.id.params_title_tv,
                            ConstraintSet.BOTTOM,
                            8
                        )
                    } else {
                        val previousCheckBox =
                            constraintLayout.getChildAt(constraintLayout.childCount - 2) as CheckBox
                        constraintSet.connect(
                            checkBox.id,
                            ConstraintSet.TOP,
                            previousCheckBox.id,
                            ConstraintSet.BOTTOM,
                            8
                        )
                    }
                    constraintSet.connect(
                        checkBox.id,
                        ConstraintSet.START,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.START,
                        8
                    )
                    constraintSet.applyTo(constraintLayout)
                }
            }
        }
    }

    private fun createRadioButtons(
        constraintLayout: ConstraintLayout,
        context: Context,
        options: List<Map<String, Int>>,
        isProfile: Boolean
    ) {
        val radioGroup = RadioGroup(context).apply {
            id = View.generateViewId()
            orientation = RadioGroup.VERTICAL
        }

        options.forEach { option ->
            option.forEach { (key, value) ->
                val radioButton = RadioButton(context).apply {
                    id = View.generateViewId()
                    text = key + ":" + value
                    isChecked =
                        isProfile && DemoContext.getAudioProfile() == value || !isProfile && DemoContext.getAudioScenario() == value
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            if (isProfile) {
                                DemoContext.setAudioProfile(value)
                            } else {
                                DemoContext.setAudioScenario(value)
                            }
                        }
                    }
                }
                radioGroup.addView(radioButton)
            }
        }

        constraintLayout.addView(radioGroup)
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)
        constraintSet.connect(
            radioGroup.id,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP,
            8
        )
        constraintSet.connect(
            radioGroup.id,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START,
            8
        )
        constraintSet.applyTo(constraintLayout)
    }
}