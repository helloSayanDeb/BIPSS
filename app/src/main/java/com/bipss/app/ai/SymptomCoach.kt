package com.bipss.app.ai

import com.bipss.app.localization.AppText
import com.bipss.app.R

/** Offline first-aid signposting, before either score reporting or model inference.
 * Sources and scope are recorded in docs/coach-guidance.md. This is not a diagnosis.
 */
object SymptomCoach {
    enum class Topic { DIZZINESS, CHEST, BREATHING, HEADACHE, FEVER, NAUSEA, UNWELL }
    data class Reply(val text: String, val topic: Topic?)

    private val patterns = mapOf(
        Topic.DIZZINESS to Regex("\\b(diz+y(?:\\s*ness)?|diz+iness|light[ -]?headed(?:ness)?|giddy|vertigo|feel(?:ing)? faint|about to faint|off[ -]balance)\\b"),
        Topic.CHEST to Regex("\\b(chest (?:pain|pressure|tightness)|(?:pain|pressure|tightness) in (?:my |the )?chest|(?:my )?chest (?:hurts|feels tight|feels heavy))\\b"),
        Topic.BREATHING to Regex("\\b(short(?:ness)? of breath|breathless|trouble breathing|difficulty breathing|breathing difficulty|(?:can't|cannot|can not) breathe|struggling to breathe)\\b"),
        Topic.HEADACHE to Regex("\\b(headache|head ache|(?:my )?head hurts)\\b"),
        Topic.FEVER to Regex("\\b(fever(?:ish)?|high temperature|chills|shivering)\\b"),
        Topic.NAUSEA to Regex("\\b(nausea|nauseous|nauseated|vomit(?:ing)?|throwing up|feel(?:ing)? sick)\\b"),
        Topic.UNWELL to Regex("\\b(unwell|not feeling (?:well|good)|feel(?:ing)? (?:weak|ill|tired)|fatigue|exhausted)\\b")
    )
    private val urgent = Regex("\\b((?:can't|cannot|can not) breathe|gasping|choking|blue lips|sudden confusion|suddenly confused|one[ -]sided weakness|slurred speech|trouble speaking|(?:can't|cannot) (?:speak|wake)|unresponsive|unconscious|passed out|collapsed|sudden (?:severe |worst )headache)\\b")
    private val negation = Regex("\\b(?:no|without|not|don't have|do not have|deny|denies)(?: (?:any|a|currently|feel|feeling|have|having|longer|more|experiencing)){0,3}\\s*$")
    private val emergency get() = AppText.text(R.string.copy_e38d0b7632be, "Call 112 in India (or your local emergency number elsewhere) now. Ask someone nearby to stay with you. Don't drive yourself or wait for the band to confirm it.")
    private val limits get() = AppText.text(R.string.copy_5c63699dc23d, "Band readings cannot tell what is causing this or rule out a serious problem.")

    private fun normalize(text: String) = com.bipss.app.localization.CoachLanguage.canonicalize(text)
        .replace('’', '\'').replace(Regex("\\s+"), " ").trim()

    /** Negation is local to a clause; 'no chest pain, but dizzy' still counts as dizziness. */
    private fun active(pattern: Regex, text: String): Boolean = pattern.findAll(text).any { match ->
        val prefix = text.substring(0, match.range.first).split(Regex("[,.;!?]|\\bbut\\b")).last()
        val suffix = text.substring(match.range.last + 1)
        val resolved = Regex("^\\s+(?:is |has |have )?(?:gone|stopped|resolved)\\b").containsMatchIn(suffix)
        val directNo = negation.containsMatchIn(prefix)
        // A single negation can govern a list: 'no chest pain or shortness of breath'.
        val listNo = Regex("\\b(?:no|without)\\b[^,.;!?]*\\b(?:or|and)\\s*$").containsMatchIn(prefix)
        !directNo && !listNo && !resolved
    }

    fun respond(question: String, previous: Topic? = null): Reply? {
        val q = normalize(question)
        val topics = patterns.filterValues { active(it, q) }.keys
        if (active(urgent, q)) return Reply(AppText.text(R.string.copy_9b458d2a6eed, "Those symptoms may need emergency help. %1\$s", emergency), topics.firstOrNull())
        if (Topic.CHEST in topics && (Topic.DIZZINESS in topics || Topic.BREATHING in topics || Topic.NAUSEA in topics))
            return Reply(AppText.text(R.string.copy_49e4dec35c55, "Chest discomfort together with these symptoms needs emergency help. %1\$s", emergency), Topic.CHEST)
        if (Topic.HEADACHE in topics && (active(Regex("\\bworst ever\\b"), q) ||
                (active(Regex("\\bsudden\\b"), q) && active(Regex("\\b(severe|extremely painful)\\b"), q))))
            return Reply(AppText.text(R.string.copy_4c0274720ee7, "A sudden, extremely painful headache needs emergency help. %1\$s", emergency), Topic.HEADACHE)

        val topic = listOf(Topic.CHEST, Topic.BREATHING, Topic.DIZZINESS, Topic.HEADACHE, Topic.FEVER, Topic.NAUSEA, Topic.UNWELL)
            .firstOrNull { it in topics }
        if (topic != null) return Reply(when (topic) {
            Topic.DIZZINESS -> AppText.text(R.string.copy_966a16385cdf, "Sit or lie down somewhere safe, move slowly, and avoid driving or climbing. You can sip water if you can swallow normally and haven't been told to limit fluids.\n\n") +
                AppText.text(R.string.copy_87fd38085cf9, "If you also have chest pain, severe trouble breathing, fainting, new weakness, trouble speaking or vision loss: %1\$s\n\n", emergency) +
                AppText.text(R.string.copy_9b36841665cb, "If dizziness persists or keeps returning, seek medical advice. %1\$s When did it start, and were you standing up or in the heat?", limits)
            Topic.CHEST -> AppText.text(R.string.copy_3d5d466cddb5, "New or recurring chest pain needs medical assessment. If it is sudden and isn't going away, feels tight or heavy, or comes with sweating, nausea, dizziness or breathlessness: %1\$s\n\n", emergency) +
                AppText.text(R.string.copy_495d7849cc9a, "%1\$s Is the pain happening now, and when did it start?", limits)
            Topic.BREATHING -> AppText.text(R.string.copy_95f4973402fe, "Stop exertion and get medical advice promptly for new or worsening breathlessness. If you are gasping, can't speak normally, have a tight or heavy chest, blue lips or sudden confusion: %1\$s\n\n", emergency) +
                AppText.text(R.string.copy_757bb4c03dbe, "%1\$s Did this start suddenly, and is it happening at rest?", limits)
            Topic.HEADACHE -> AppText.text(R.string.copy_e9278930f603, "Rest, drink water if you have no fluid restriction, and take a break from screens. A sudden extremely painful headache, weakness, difficulty speaking, confusion or a recent head injury needs emergency assessment. %1\$s\n\n", limits) +
                AppText.text(R.string.copy_f116722683a0, "Seek medical advice if it worsens, keeps returning, or comes with vomiting or vision changes. When did it start?")
            Topic.FEVER -> AppText.text(R.string.copy_db217bb48a5a, "Check with a thermometer if you can: wrist skin temperature cannot confirm a fever. Rest and drink fluids unless you've been given a fluid limit. Seek medical advice if you are getting worse or not improving.\n\n") +
                AppText.text(R.string.copy_1e732adbdbbf, "For severe breathing difficulty or confusion, %1\$s What temperature did the thermometer show, and how long have you felt unwell?", emergency)
            Topic.NAUSEA -> AppText.text(R.string.copy_8b7209d71384, "Try small, regular sips of a cool drink and small meals if you can tolerate them. If it persists or keeps returning, seek medical advice. Sudden nausea with chest tightness or breathlessness needs emergency help.\n\n") +
                AppText.text(R.string.copy_66d303080bc1, "%1\$s How long has this been happening, and are you able to keep fluids down?", limits)
            Topic.UNWELL -> AppText.text(R.string.copy_c41ab48fb6bc, "Pause strenuous activity and tell me what you're feeling, when it started, and whether it's worsening. %1\$s\n\n", limits) +
                AppText.text(R.string.copy_af6eb8309d3d, "For chest pain with breathlessness, severe breathing difficulty, fainting or sudden confusion, %1\$s", emergency)
        }, topic)

        if (patterns.values.any { it.containsMatchIn(q) })
            return Reply(AppText.text(R.string.copy_76991a573df1, "Thanks for clarifying that symptom isn't happening now. If it returns or worries you, seek medical advice. What would you like help with?"), null)

        if (previous != null) {
            if (Regex("\\b(worse|worsening|not better|not improving|still|won't stop)\\b").containsMatchIn(q))
                return Reply(AppText.text(R.string.copy_6ba62b39855c, "Since this is continuing or getting worse, seek medical advice promptly. For chest pain with breathlessness, severe breathing difficulty, fainting, new weakness or trouble speaking, %1\$s", emergency), previous)
            if (Regex("\\b(better|gone|stopped|resolved)\\b").containsMatchIn(q))
                return Reply(AppText.text(R.string.copy_e6f64f6f18b5, "Take things slowly. If the symptoms return, persist or worry you, get medical advice. %1\$s", limits), null)
            if (Regex("\\b(\\d+|few|several|since|standing|stood|heat|outside|minutes?|hours?|days?)\\b").containsMatchIn(q) &&
                !Regex("\\b(score|water|goal|heart|oxygen|temperature|sleep)\\b").containsMatchIn(q))
                return Reply(AppText.text(R.string.copy_8756b6a360f7, "That timing or trigger is useful to tell a clinician, but it doesn't establish the cause. If the symptom is continuing or recurring, seek medical advice. %1\$s Are you feeling better, the same, or worse now?", limits), previous)
            if (q.trimEnd('.', '!') in listOf("yes", "no", "same", AppText.text(R.string.copy_fa0487f2dc91, "the same")))
                return Reply(AppText.text(R.string.copy_6b261fac3ef5, "Please say which symptom you mean and whether it is happening now. If there is chest pain with breathlessness, severe breathing difficulty, fainting, new weakness or trouble speaking, %1\$s", emergency), previous)
        }
        return null
    }
}
