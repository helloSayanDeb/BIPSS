package com.bipss.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.BipssTextField
import com.bipss.app.ui.components.InfoBanner
import com.bipss.app.ui.components.OnboardingScaffold
import com.bipss.app.ui.components.PrimaryButton
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingProfileScreen(
    navController: NavController,
    isEditMode:    Boolean = false,
    vm: OnboardingProfileViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    // Ask for Health Connect write access up front so the profile save can
    // actually land there — no-ops harmlessly if Health Connect isn't present.
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        contract = vm.healthConnectPermissionContract()
    ) { /* granted or not — writeProfile() re-checks and degrades gracefully */ }

    LaunchedEffect(isEditMode) { if (isEditMode) vm.loadExistingProfile() }
    LaunchedEffect(Unit) {
        if (!isEditMode && vm.healthConnectAvailable) {
            healthConnectPermissionLauncher.launch(vm.healthConnectPermissions)
        }
    }

    OnboardingScaffold(
        step = if (isEditMode) null else 0,
        totalSteps = 3,
        title = if (isEditMode) "Edit profile" else "About you",
        subtitle = if (isEditMode)
            "Update the details your baselines are calculated from."
        else
            "These four numbers anchor every baseline BIPSS builds for you.",
        onBack = if (isEditMode || navController.previousBackStackEntry != null) {
            { navController.popBackStack() }
        } else null,
        footer = {
            PrimaryButton(
                text = if (isEditMode) "Save changes" else "Continue",
                enabled = state.isFormValid,
                loading = state.isSaving,
                onClick = {
                    if (isEditMode) vm.onSaveEdited { navController.popBackStack() }
                    else vm.onContinue { navController.navigate(Routes.ONBOARDING_CONNECT_BAND) }
                }
            )
        }
    ) {
        BipssTextField(
            value = state.name,
            onValueChange = vm::onNameChange,
            label = "Full name",
            placeholder = "e.g. John Doe",
            leadingIcon = Icons.Rounded.Person,
            error = state.nameError,
            keyboardType = KeyboardType.Text,
        )
        Spacer(Modifier.height(Space.xl))

        BipssTextField(
            value = state.age,
            onValueChange = vm::onAgeChange,
            label = "Age",
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
                label = "Height",
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
                label = "Weight",
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
        InfoBanner(
            text = "Saved to Health Connect and to this device. Your vitals never leave the phone unless you turn on sharing.",
            icon = Icons.Rounded.Shield,
            tint = colors.bandNormal,
        )
    }
}
