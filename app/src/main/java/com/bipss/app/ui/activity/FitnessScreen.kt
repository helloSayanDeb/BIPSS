package com.bipss.app.ui.activity

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.FitnessRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.*
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FitnessScreen(nav: NavController, repo: FitnessRepository = koinInject(),
    prefs: UserPreferencesRepository = koinInject()) {
    val records by repo.records.collectAsStateWithLifecycle(emptyList())
    val profile by prefs.userProfile.collectAsStateWithLifecycle(null)
    var reference by rememberSaveable { mutableStateOf<String?>(null) }
    var rating by rememberSaveable { mutableStateOf<Int?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    var chooseActivity by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val colors = BipssTheme.colors
    val input = profile?.let { p -> reference?.let { r -> rating?.let { a ->
        FitnessInput(p.age, p.heightCm.toDouble(), p.weightKg.toDouble(), a, FitnessReference.valueOf(r))
    } } }
    val preview = input?.let { FitnessEstimate.calculate(it, 0) }
    WellnessPage(AppText.text(R.string.copy_2026882df169, "Cardio fitness"), nav) {
        records.lastOrNull()?.let { last ->
            BipssCard {
                SectionLabel(AppText.text(R.string.copy_376d429be997, "Estimated VO₂ max"))
                Spacer(Modifier.height(Space.s))
                Text("%.1f".format(last.vo2), style = MaterialTheme.typography.displayMedium, color = colors.activity)
                Text(AppText.text(R.string.copy_0e9b0c13ab89, "mL/kg/min · ") + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(last.atMs)), color = colors.textSecondary)
                Spacer(Modifier.height(Space.l))
                Text(AppText.text(R.string.copy_9ed384517bca, "Estimated fitness age"), style = MaterialTheme.typography.titleMedium)
                Text(last.ageBand, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_b736c42e4915, "An age-group comparison of fitness, not your biological age."), color = colors.textSecondary)
            }
        }
        SectionHeader(AppText.text(R.string.copy_4f20b7ccd1db, "Update your estimate"))
        Text(AppText.text(R.string.copy_e49663782c87, "Use your usual activity over the past month. No exercise test is needed."), color = colors.textSecondary)
        BipssCard {
            InfoRow(AppText.text(R.string.copy_39b7370f30a3, "Age"), profile?.age?.toString() ?: "—")
            InfoRow(AppText.text(R.string.copy_bfe2eca40179, "Height"), profile?.heightCm?.let { AppText.text(R.string.copy_d17db548b175, "%.0f cm").format(it) } ?: "—")
            InfoRow(AppText.text(R.string.copy_81d27ef6d503, "Weight"), profile?.weightKg?.let { AppText.text(R.string.copy_9007d1e37ece, "%.1f kg").format(it) } ?: "—")
            TextButton(onClick = { nav.navigate(Routes.ONBOARDING_PROFILE_EDIT) }) { Text(AppText.text(R.string.copy_56d4c3830da4, "Update measurements")) }
            Text(AppText.text(R.string.copy_247986d46fb3, "Formula reference"), style = MaterialTheme.typography.titleMedium)
            Text(AppText.text(R.string.copy_774e531aaa6a, "Choose the sex reference to use for this estimate."), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FitnessReference.entries.forEach { r -> FilterChip(selected = reference == r.name,
                    onClick = { reference = r.name }, label = { Text(r.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            }
        }
        BipssCard(onClick = { chooseActivity = true }) {
            Text(AppText.text(R.string.copy_e3644ea326e4, "Usual activity"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            Text(rating?.let { FitnessEstimate.activityLabels[it] } ?: AppText.text(R.string.copy_e27407704026, "Choose what best describes your past month"), color = colors.textSecondary)
            TextButton(onClick = { chooseActivity = true }) { Text(AppText.text(R.string.copy_823d04db8d52, "Choose activity level")) }
        }
        if (input != null && preview == null) Text(AppText.text(R.string.copy_cefc4b11b319, "This estimate supports ages 20–70 and a limited range of body measurements. Check your profile."), color = colors.textSecondary)
        PrimaryButton(if (saving) AppText.text(R.string.copy_23e39291d613, "Saving…") else AppText.text(R.string.copy_5286c4d58bfe, "Save estimate"), enabled = preview != null && !saving, onClick = {
            scope.launch { saving = true
                try { message = if (repo.save(input!!)) AppText.text(R.string.copy_1c861e2449f2, "Estimate saved") else AppText.text(R.string.copy_c42c125744d5, "Check your measurements") }
                catch (_: Exception) { message = AppText.text(R.string.copy_9d249d518279, "Couldn't save. Please try again.") }
                finally { saving = false }
            }
        })
        message?.let { Text(it) }
        TextButton(onClick = { details = !details }) { Text(if (details) AppText.text(R.string.copy_06bb50ae5fe3, "Hide method") else AppText.text(R.string.copy_f480ad56dc93, "How this estimate works")) }
        if (details) BipssCard {
            Text(AppText.text(R.string.copy_7980b04ff66c, "VO₂ max is estimated from age, BMI, activity and your selected reference using the Jackson non-exercise equation. It is not measured by your band. Individual error can be substantial (the original model's typical error was about 5.6 mL/kg/min)."), color = colors.textSecondary)
            Spacer(Modifier.height(Space.m))
            Text(AppText.text(R.string.copy_b7738189dc67, "Fitness age is the nearest age-group average in the HUNT3 healthy Norwegian reference cohort. Combining an estimate with population averages adds uncertainty. This comparison has not been validated for your band or population and does not measure aging. Small changes may reflect your answers or weight rather than improved fitness."), color = colors.textSecondary)
            TextButton(onClick = { uri.openUri("https://wwwn.cdc.gov/nchs/data/nhanes/public/2003/manuals/cv_99-04.pdf") }) { Text(AppText.text(R.string.copy_508cf4b30290, "VO₂ estimation method")) }
            TextButton(onClick = { uri.openUri("https://www.ntnu.edu/cerg/fitness-numbers") }) { Text(AppText.text(R.string.copy_734b99b4b2b3, "Fitness-age reference values")) }
        }
        if (records.isNotEmpty()) {
            SectionHeader(AppText.text(R.string.copy_6c558b37aabc, "Previous estimates"))
            records.asReversed().take(12).forEach { r -> BipssCard {
                InfoRow(SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(r.atMs)), AppText.text(R.string.copy_6de1c1eb8aba, "%.1f mL/kg/min").format(r.vo2))
                Text(AppText.text(R.string.copy_46891438564b, "Fitness age %1\$s · activity %2\$s/7", r.ageBand, r.input.activityRating), color = colors.textSecondary)
            } }
            TextButton(onClick = { scope.launch { repo.clear() } }) { Text(AppText.text(R.string.copy_854fc54fe61d, "Clear estimate history")) }
        }
    }
    if (chooseActivity) AlertDialog(onDismissRequest = { chooseActivity = false }, title = { Text(AppText.text(R.string.copy_891987377e37, "Your usual activity")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            FitnessEstimate.activityLabels.forEachIndexed { index, label ->
                TextButton(onClick = { rating = index; chooseActivity = false }) {
                    Text((if (rating == index) "✓ " else "") + label, Modifier.fillMaxWidth())
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseActivity = false }) { Text(AppText.text(R.string.copy_7d9eb7acb13e, "Close")) } })
}
