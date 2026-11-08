package owntally.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies text that is a secret (an invite link holds the group key) and marks the entry sensitive, so the system hides it in its
 * clipboard preview. This file is the only one in the module allowed to use a bundle (see `NoPhraseLeaksTest`): it is not saved state.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
