package owntally.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import owntally.crypto.RecoveryPhrase
import owntally.ui.components.CenteredNote
import owntally.ui.components.Notice
import owntally.ui.components.OwnTallyField
import owntally.ui.components.PrimaryButton
import owntally.ui.components.StepLabel
import owntally.ui.components.TextAction
import owntally.ui.components.Wordmark
import owntally.ui.theme.OwnTallyColors

/** The texts of the first-run screens, as in the mockups (design/07, 08 and 08b). */
object OnboardingText {
    const val HEADLINE = "Split expenses.\nKeep your data."
    const val INTRO = "No account and no server. Everything stays on your phone and moves straight to your friends' phones."
    const val NAME_LABEL = "Your name"
    const val NAME_HINT = "For example, Asha"
    const val PHONE_LABEL = "Phone number"
    const val PHONE_HINT = "+91 98765 43210"
    const val PROFILE_NOTE = "Friends in your groups see your name and number so they know who to pay. It isn't verified, and you can use different ones in each group."
    const val CONTINUE = "Continue"
    const val NEXT = "Next: save your recovery phrase"

    const val STEP = "Step 2 of 2"
    const val PHRASE_TITLE = "Save your recovery phrase"
    const val PHRASE_BODY = "These 12 words restore your identity on a new phone. They are the only backup of who you are in your groups."
    const val PHRASE_WARNING = "Write these on paper. Anyone with them can act as you in every group, and we can't recover them for you. Screenshots are blocked on this screen."
    const val WRITTEN = "I've written it down"

    const val CONFIRM_TITLE = "Check you wrote it down"
    const val CONFIRM_BODY = "Type the words we ask for. This makes sure you can get back in."
    const val FINISH = "Finish"
    const val SHOW_AGAIN = "Show the words again"
    const val MISMATCH = "Those words don't match what we showed. Check your paper, or show the words again."
    const val COULD_NOT_SAVE = "Couldn't save on this phone. Try again."

    fun nameError(p: NameProblem) = when (p) {
        NameProblem.EMPTY -> "Enter your name."
        NameProblem.TOO_LONG -> "Use 40 characters or fewer."
    }

    fun phoneError(p: PhoneProblem) = when (p) {
        PhoneProblem.EMPTY -> "Enter your phone number."
        PhoneProblem.BAD_CHARACTERS -> "Use only digits, spaces and + - ( ) ."
        PhoneProblem.TOO_FEW_DIGITS -> "Use at least 5 digits."
        PhoneProblem.BAD_LENGTH -> "Use 5 to 20 characters."
    }
}

@Composable
private fun ScreenFrame(content: @Composable (Modifier) -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding().padding(horizontal = 24.dp)) {
        content(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) { actions() }
    }
}

@Composable
fun WelcomeScreen(state: OnboardingState, onName: (String) -> Unit, onPhone: (String) -> Unit, onContinue: () -> Unit) {
    ScreenFrame(
        content = { modifier ->
            Column(modifier) {
                Spacer(Modifier.height(32.dp))
                Wordmark()
                Spacer(Modifier.height(48.dp))
                Text(OnboardingText.HEADLINE, style = MaterialTheme.typography.headlineLarge, color = OwnTallyColors.Ink)
                Spacer(Modifier.height(20.dp))
                Text(OnboardingText.INTRO, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(36.dp))
                OwnTallyField(
                    label = OnboardingText.NAME_LABEL,
                    value = state.name,
                    onValueChange = onName,
                    placeholder = OnboardingText.NAME_HINT,
                    error = state.nameProblem?.let(OnboardingText::nameError),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(20.dp))
                OwnTallyField(
                    label = OnboardingText.PHONE_LABEL,
                    value = state.phone,
                    onValueChange = onPhone,
                    placeholder = OnboardingText.PHONE_HINT,
                    error = state.phoneProblem?.let(OnboardingText::phoneError),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(14.dp))
                Text(OnboardingText.PROFILE_NOTE, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(16.dp))
            }
        },
        actions = {
            PrimaryButton(OnboardingText.CONTINUE, onContinue)
            Spacer(Modifier.height(10.dp))
            CenteredNote(OnboardingText.NEXT)
        },
    )
}

/**
 * One numbered word. A recovery word must never be cut off, so the word shrinks to fit its chip (down to a small size) instead
 * of being clipped; a test checks the longest words at large font sizes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(number: Int, word: String, modifier: Modifier) {
    Row(
        modifier
            .background(OwnTallyColors.Chip, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Word $number: $word" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BasicText("$number", style = TextStyle(fontSize = 12.sp, color = OwnTallyColors.TextHint), maxLines = 1)
        BasicText(
            word,
            modifier = Modifier.weight(1f).testTag("word-$number"),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OwnTallyColors.Ink),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
        )
    }
}

@Composable
fun PhraseScreen(phrase: RecoveryPhrase, onWritten: () -> Unit) {
    val words = phrase.words()
    ScreenFrame(
        content = { modifier ->
            Column(modifier) {
                Spacer(Modifier.height(36.dp))
                StepLabel(OnboardingText.STEP)
                Spacer(Modifier.height(6.dp))
                Text(OnboardingText.PHRASE_TITLE, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink)
                Spacer(Modifier.height(14.dp))
                Text(OnboardingText.PHRASE_BODY, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(24.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(OwnTallyColors.Card, RoundedCornerShape(24.dp))
                        .border(1.dp, OwnTallyColors.Border, RoundedCornerShape(24.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Three columns as in the mockup; two wider ones when the person has chosen large text, so no word is squeezed.
                    val columns = if (LocalDensity.current.fontScale > 1.3f) 2 else 3
                    for (row in 0 until words.size / columns) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (col in 0 until columns) {
                                val index = row * columns + col
                                WordChip(index + 1, words[index], Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Notice(OnboardingText.PHRASE_WARNING)
                Spacer(Modifier.height(16.dp))
            }
        },
        actions = { PrimaryButton(OnboardingText.WRITTEN, onWritten) },
    )
}

@Composable
fun ConfirmScreen(state: OnboardingState, onAnswer: (Int, String) -> Unit, onFinish: () -> Unit, onShowAgain: () -> Unit) {
    ScreenFrame(
        content = { modifier ->
            Column(modifier) {
                Spacer(Modifier.height(36.dp))
                StepLabel(OnboardingText.STEP)
                Spacer(Modifier.height(6.dp))
                Text(OnboardingText.CONFIRM_TITLE, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink)
                Spacer(Modifier.height(14.dp))
                Text(OnboardingText.CONFIRM_BODY, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(26.dp))
                state.positions.forEachIndexed { i, position ->
                    OwnTallyField(
                        label = "Word $position",
                        value = state.answers.getOrNull(i)?.text.orEmpty(),
                        onValueChange = { onAnswer(i, it) },
                        placeholder = "Type word $position",
                        // No suggestions or learning of the words: password-type keyboard (no suggestions, text stays visible), no auto-correct, no capital letters.
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = if (i == state.positions.lastIndex) ImeAction.Done else ImeAction.Next,
                        ),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (state.mismatch) Text(OnboardingText.MISMATCH, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Red)
                state.saveProblem?.let { Text(OnboardingText.COULD_NOT_SAVE, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Red) }
                Spacer(Modifier.height(16.dp))
            }
        },
        actions = {
            PrimaryButton(OnboardingText.FINISH, onFinish, enabled = state.canFinish, loading = state.saving)
            TextAction(OnboardingText.SHOW_AGAIN, onShowAgain)
        },
    )
}

/** The whole first-run flow. [onFinished] is called once, when the check is passed and everything is saved. */
@Composable
fun OnboardingFlow(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step == Step.PHRASE || state.step == Step.CONFIRM) { viewModel.back() }
    LaunchedEffect(state.step) { if (state.step == Step.DONE) onFinished() }
    when (state.step) {
        Step.WELCOME -> WelcomeScreen(state, viewModel::onNameChange, viewModel::onPhoneChange, viewModel::continueFromWelcome)
        Step.PHRASE -> state.phrase?.let { PhraseScreen(it, viewModel::phraseWritten) }
        Step.CONFIRM -> ConfirmScreen(state, viewModel::onAnswerChange, viewModel::finish, viewModel::showWordsAgain)
        Step.DONE -> Unit
    }
}
