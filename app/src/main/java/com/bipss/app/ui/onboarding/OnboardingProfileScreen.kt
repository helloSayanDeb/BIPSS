package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ui.components.BipssTextField
import com.bipss.app.ui.components.GenderSelector
import com.bipss.app.ui.components.InfoBanner
import com.bipss.app.ui.components.OnboardingScaffold
import com.bipss.app.ui.components.PrimaryButton
import com.bipss.app.ui.components.SecondaryButton
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

/**
 * Step 2: the profile. Also opened from Settings in edit mode, which is where
 * Health Connect is offered - it is an opt-in, not a dialog raised on the way
 * through setup.
 */
@Composable
fun OnboardingProfileScreen(
    navController: NavController,
    isEditMode:    Boolean = false,
    vm: OnboardingProfileViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        contract = vm.healthConnectPermissionContract()
    ) { vm.refreshHealthConnect() }

    LaunchedEffect(isEditMode) { if (isEditMode) vm.loadExistingProfile() }

    OnboardingScaffold(
        step = if (isEditMode) null else OnboardingStep.indexOf(OnboardingStep.PROFILE, catchUp = false),
        totalSteps = OnboardingStep.TOTAL,
        title = if (isEditMode) AppText.text(R.string.copy_15c4aa13037e, "Edit profile") else AppText.text(R.string.copy_428f4d00b7ff, "About you"),
        subtitle = AppText.text(R.string.copy_13447f23b07b, "Your age sets the heart-rate range your strain is measured against."),
        onBack = if (isEditMode || navController.previousBackStackEntry != null) {
            { navController.popBackStack() }
        } else null,
        footer = {
            PrimaryButton(
                text = if (isEditMode) AppText.text(R.string.copy_dd0ae7a5cbcf, "Save changes") else AppText.text(R.string.copy_31fbef162594, "Continue"),
                enabled = state.isFormValid,
                loading = state.isSaving,
                onClick = {
                    if (isEditMode) vm.onSave { navController.popBackStack() }
                    else vm.onSave { navController.goToNextOnboardingStep(OnboardingStep.PROFILE, catchUp = false) }
                }
            )
        }
    ) {
        BipssTextField(
            value = state.name,
            onValueChange = vm::onNameChange,
            label = AppText.text(R.string.copy_f13a64ba2fea, "Full name"),
            placeholder = AppText.text(R.string.copy_568f712b07d7, "e.g. John Doe"),
            leadingIcon = Icons.Rounded.Person,
            error = state.nameError,
            keyboardType = KeyboardType.Text,
        )
        Spacer(Modifier.height(Space.xl))

        BipssTextField(
            value = state.age,
            onValueChange = vm::onAgeChange,
            label = AppText.text(R.string.copy_39b7370f30a3, "Age"),
            placeholder = "25",
            suffix = "years",
            leadingIcon = Icons.Rounded.CalendarToday,
            error = state.ageError,
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(Space.xl))

        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            BipssTextField(
                value = state.height,
                onValueChange = vm::onHeightChange,
                label = AppText.text(R.string.copy_bfe2eca40179, "Height"),
                placeholder = "170",
                suffix = "cm",
                leadingIcon = Icons.Rounded.Height,
                error = state.heightError,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            BipssTextField(
                value = state.weight,
                onValueChange = vm::onWeightChange,
                label = AppText.text(R.string.copy_81d27ef6d503, "Weight"),
                placeholder = "61",
                suffix = "kg",
                leadingIcon = Icons.Rounded.MonitorWeight,
                error = state.weightError,
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Space.xxl))
        Text(
            AppText.text(R.string.copy_10133a17613d, "How do you describe yourself?"),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(Space.m))
        GenderSelector(selected = state.gender, onSelect = vm::onGenderChange)
        Spacer(Modifier.height(Space.m))
        GenderNote()

        Spacer(Modifier.height(Space.xxl))
        InfoBanner(
            text = AppText.text(R.string.copy_964c8c78c520, "Used for your heart-rate zones, BMI and hydration goal."),
            icon = Icons.Rounded.Shield,
            tint = colors.bandNormal,
        )

        if (isEditMode && vm.healthConnectAvailable) {
            Spacer(Modifier.height(Space.l))
            if (state.healthConnectGranted) {
                Text(
                    AppText.text(R.string.copy_0c96d260818c, "Health Connect is allowed: saving also updates your height and weight there."),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            } else {
                SecondaryButton(
                    text = AppText.text(R.string.copy_7d2811023759, "Also save to Health Connect"),
                    leadingIcon = Icons.Rounded.Favorite,
                    onClick = { healthConnectPermissionLauncher.launch(vm.healthConnectPermissions) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
