package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.entity.EmergencyContactEntity
import com.bipss.app.navigation.Routes
import com.bipss.app.sos.SmsCapability
import com.bipss.app.sos.SmsSender
import com.bipss.app.sos.SosBlocker
import com.bipss.app.ui.components.*
import com.bipss.app.ui.settings.MAX_EMERGENCY_CONTACTS
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * The last step. Finishing is what marks onboarding complete, so an app killed
 * part-way through starts again rather than landing in a half-set-up shell.
 */
class OnboardingContactsViewModel(
    private val contactDao: EmergencyContactDao,
    private val smsSender:  SmsSender,
    private val prefs:      UserPreferencesRepository
) : ViewModel() {

    val contacts: StateFlow<List<EmergencyContactEntity>> =
        contactDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _warning = MutableStateFlow<Set<SosBlocker>?>(null)
    /** Non-null while the "SOS can't text anyone yet" notice is up. */
    val warning: StateFlow<Set<SosBlocker>?> = _warning.asStateFlow()

    private var finishing = false

    /**
     * Finishes, unless something the user can still fix would stop an SOS from
     * texting anyone - then says so first. A phone that cannot send SMS at all
     * was already told on the SMS step, and there is nothing here to fix.
     */
    fun onFinish(onDone: () -> Unit) {
        viewModelScope.launch {
            val blockers = buildSet {
                if (smsSender.capability() == SmsCapability.NO_PERMISSION) add(SosBlocker.NO_SMS_PERMISSION)
                if (smsSender.capability() != SmsCapability.NO_TELEPHONY &&
                    contactDao.all().first().isEmpty()) add(SosBlocker.NO_CONTACTS)
            }
            if (blockers.isEmpty()) complete(onDone) else _warning.value = blockers
        }
    }

    fun onFinishAnyway(onDone: () -> Unit) {
        _warning.value = null
        complete(onDone)
    }

    fun dismissWarning() { _warning.value = null }

    private fun complete(onDone: () -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            prefs.setOnboardingComplete()
            onDone()
        }
    }
}

/** Step 7: emergency contacts - the people an SOS texts. */
@Composable
fun OnboardingContactsScreen(
    navController: NavController,
    catchUp: Boolean,
    vm: OnboardingContactsViewModel = koinViewModel()
) {
    val colors = BipssTheme.colors
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val warning by vm.warning.collectAsStateWithLifecycle()
    val finish: () -> Unit = { navController.finishOnboarding() }
    val openPicker: () -> Unit = { navController.navigate(Routes.SETTINGS_CONTACT_PICKER) }

    OnboardingScaffold(
        step = OnboardingStep.indexOf(OnboardingStep.CONTACTS, catchUp),
        totalSteps = OnboardingStep.TOTAL,
        title = AppText.text(R.string.copy_9463d00b353e, "Emergency contacts"),
        subtitle = AppText.text(R.string.copy_2eda52c84b5e, "The people SOS texts. Choosing them now means an emergency isn't the first time you look."),
        onBack = { navController.popBackStack() },
        footer = {
            if (contacts.isEmpty()) {
                PrimaryButton(AppText.text(R.string.copy_677b92192944, "Pick emergency contacts"), onClick = openPicker)
                Spacer(Modifier.height(Space.s))
                GhostButton(AppText.text(R.string.copy_a0e63d7c7125, "Not now"), onClick = { vm.onFinish(finish) }, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(AppText.text(R.string.copy_bc01ae77f37c, "Finish setup"), onClick = { vm.onFinish(finish) })
                if (contacts.size < MAX_EMERGENCY_CONTACTS) {
                    Spacer(Modifier.height(Space.s))
                    GhostButton(AppText.text(R.string.copy_d7246da527c1, "Add another"), onClick = openPicker, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    ) {
        if (contacts.isEmpty()) {
            FeatureRow(
                icon = Icons.Rounded.Contacts, tint = colors.bandRisk,
                title = AppText.text(R.string.copy_78ae9c3e607d, "Picked from your phone's contacts"),
                body = AppText.text(R.string.copy_0ee6d02d2fbb, "BIPSS asks to read your contacts so you can choose from them. It keeps only the name ") +
                    AppText.text(R.string.copy_6e6ad6f85c55, "and number of the people you pick (up to %1\$s), on this phone and in ", MAX_EMERGENCY_CONTACTS) +
                    AppText.text(R.string.copy_894f1274d5a3, "your Drive backup if you turn that on. In an SOS, nearby hospitals that are texted are ") +
                    AppText.text(R.string.copy_1b718119b211, "given these names and numbers, so they can reach someone who knows you.")
            )
        } else {
            BipssCard(contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                contacts.forEachIndexed { i, c ->
                    if (i > 0) HairlineDivider()
                    Row(Modifier.padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = colors.textTertiary,
                             modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Space.m))
                        Column {
                            Text(c.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                            Spacer(Modifier.height(Space.xs))
                            Text(c.phoneNumber, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.l))
            Text(
                AppText.text(R.string.copy_0e01b59d0d74, "You can remove contacts, or add them later, in Settings."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }
    }

    warning?.let { blockers ->
        AlertDialog(
            onDismissRequest = vm::dismissWarning,
            title = { Text(AppText.text(R.string.copy_05ea75565c87, "SOS can't text anyone yet")) },
            text = {
                Text(
                    buildList {
                        if (SosBlocker.NO_SMS_PERMISSION in blockers) add(AppText.text(R.string.copy_3c8054997500, "SMS permission is off."))
                        if (SosBlocker.NO_CONTACTS in blockers) add(AppText.text(R.string.copy_3c59258018d8, "No emergency contacts are saved."))
                    }.joinToString(" ") +
                        AppText.text(R.string.copy_d911ba923160, " Until that's fixed, pressing SOS can only offer to call 112. Home will keep ") +
                        AppText.text(R.string.copy_200141895d08, "reminding you, and you can fix it in Settings.")
                )
            },
            confirmButton = { TextButton(onClick = { vm.onFinishAnyway(finish) }) { Text(AppText.text(R.string.copy_8c31b160848e, "Finish anyway")) } },
            dismissButton = { TextButton(onClick = vm::dismissWarning) { Text(AppText.text(R.string.copy_6aadac2f2b7a, "Go back")) } }
        )
    }
}
