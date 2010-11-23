/*
 * Copyright (C) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.voice;

import com.android.inputmethod.latin.EditingUtil;
import com.android.inputmethod.latin.Hints;
import com.android.inputmethod.latin.KeyboardSwitcher;
import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.SharedPreferencesCompat;
import com.android.inputmethod.latin.SubtypeSwitcher;
import com.android.inputmethod.latin.LatinIME.UIHandler;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.speech.SpeechRecognizer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VoiceIMEConnector implements VoiceInput.UiListener {
    private static final VoiceIMEConnector sInstance = new VoiceIMEConnector();

    public static final boolean VOICE_INSTALLED = true;
    private static final boolean ENABLE_VOICE_BUTTON = true;
    private static final String PREF_VOICE_MODE = "voice_mode";
    // Whether or not the user has used voice input before (and thus, whether to show the
    // first-run warning dialog or not).
    private static final String PREF_HAS_USED_VOICE_INPUT = "has_used_voice_input";
    // Whether or not the user has used voice input from an unsupported locale UI before.
    // For example, the user has a Chinese UI but activates voice input.
    private static final String PREF_HAS_USED_VOICE_INPUT_UNSUPPORTED_LOCALE =
            "has_used_voice_input_unsupported_locale";
    // The private IME option used to indicate that no microphone should be shown for a
    // given text field. For instance this is specified by the search dialog when the
    // dialog is already showing a voice search button.
    private static final String IME_OPTION_NO_MICROPHONE = "nm";

    private boolean mAfterVoiceInput;
    private boolean mHasUsedVoiceInput;
    private boolean mHasUsedVoiceInputUnsupportedLocale;
    private boolean mImmediatelyAfterVoiceInput;
    private boolean mIsShowingHint;
    private boolean mLocaleSupportedForVoiceInput;
    private boolean mPasswordText;
    private boolean mRecognizing;
    private boolean mShowingVoiceSuggestions;
    private boolean mVoiceButtonEnabled;
    private boolean mVoiceButtonOnPrimary;
    private boolean mVoiceInputHighlighted;

    private LatinIME mContext;
    private AlertDialog mVoiceWarningDialog;
    private VoiceInput mVoiceInput;
    private final VoiceResults mVoiceResults = new VoiceResults();
    private Hints mHints;
    private UIHandler mHandler;
    // For each word, a list of potential replacements, usually from voice.
    private final Map<String, List<CharSequence>> mWordToSuggestions =
            new HashMap<String, List<CharSequence>>();

    public static VoiceIMEConnector init(LatinIME context, UIHandler h) {
        sInstance.initInternal(context, h);
        return sInstance;
    }

    private void initInternal(LatinIME context, UIHandler h) {
        mContext = context;
        mHandler = h;
        if (VOICE_INSTALLED) {
            mVoiceInput = new VoiceInput(context, this);
            mHints = new Hints(context, new Hints.Display() {
                public void showHint(int viewResource) {
                    LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                            Context.LAYOUT_INFLATER_SERVICE);
                    View view = inflater.inflate(viewResource, null);
                    mContext.setCandidatesView(view);
                    mContext.setCandidatesViewShown(true);
                    mIsShowingHint = true;
                }
              });
        }
    }

    private VoiceIMEConnector() {
    }

    public void resetVoiceStates(boolean isPasswordText) {
        mAfterVoiceInput = false;
        mImmediatelyAfterVoiceInput = false;
        mShowingVoiceSuggestions = false;
        mVoiceInputHighlighted = false;
        mPasswordText = isPasswordText;
    }

    public void flushVoiceInputLogs(boolean configurationChanged) {
        if (VOICE_INSTALLED && !configurationChanged) {
            if (mAfterVoiceInput) {
                mVoiceInput.flushAllTextModificationCounters();
                mVoiceInput.logInputEnded();
            }
            mVoiceInput.flushLogs();
            mVoiceInput.cancel();
        }
    }

    public void flushAndLogAllTextModificationCounters(int index, CharSequence suggestion,
            String wordSeparators) {
        if (mAfterVoiceInput && mShowingVoiceSuggestions) {
            mVoiceInput.flushAllTextModificationCounters();
            // send this intent AFTER logging any prior aggregated edits.
            mVoiceInput.logTextModifiedByChooseSuggestion(suggestion.toString(), index,
                    wordSeparators, mContext.getCurrentInputConnection());
        }
    }

    private void showVoiceWarningDialog(final boolean swipe, IBinder token,
            final boolean configurationChanging) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_mic_dialog);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                mVoiceInput.logKeyboardWarningDialogOk();
                reallyStartListening(swipe, configurationChanging);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                mVoiceInput.logKeyboardWarningDialogCancel();
            }
        });

        if (mLocaleSupportedForVoiceInput) {
            String message = mContext.getString(R.string.voice_warning_may_not_understand)
                    + "\n\n" + mContext.getString(R.string.voice_warning_how_to_turn_off);
            builder.setMessage(message);
        } else {
            String message = mContext.getString(R.string.voice_warning_locale_not_supported)
                    + "\n\n" + mContext.getString(R.string.voice_warning_may_not_understand)
                    + "\n\n" + mContext.getString(R.string.voice_warning_how_to_turn_off);
            builder.setMessage(message);
        }

        builder.setTitle(R.string.voice_warning_title);
        mVoiceWarningDialog = builder.create();
        Window window = mVoiceWarningDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = token;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mVoiceInput.logKeyboardWarningDialogShown();
        mVoiceWarningDialog.show();
    }

    public void showPunctuationHintIfNecessary() {
        InputConnection ic = mContext.getCurrentInputConnection();
        if (!mImmediatelyAfterVoiceInput && mAfterVoiceInput && ic != null) {
            if (mHints.showPunctuationHintIfNecessary(ic)) {
                mVoiceInput.logPunctuationHintDisplayed();
            }
        }
        mImmediatelyAfterVoiceInput = false;
    }

    public void hideVoiceWindow(boolean configurationChanging) {
        if (!configurationChanging) {
            if (mAfterVoiceInput)
                mVoiceInput.logInputEnded();
            if (mVoiceWarningDialog != null && mVoiceWarningDialog.isShowing()) {
                mVoiceInput.logKeyboardWarningDialogDismissed();
                mVoiceWarningDialog.dismiss();
                mVoiceWarningDialog = null;
            }
            if (VOICE_INSTALLED & mRecognizing) {
                mVoiceInput.cancel();
            }
        }
        mWordToSuggestions.clear();
    }

    public void setCursorAndSelection(int newSelEnd, int newSelStart) {
        if (mAfterVoiceInput) {
            mVoiceInput.setCursorPos(newSelEnd);
            mVoiceInput.setSelectionSpan(newSelEnd - newSelStart);
        }
    }

    public void setVoiceInputHighlighted(boolean b) {
        mVoiceInputHighlighted = b;
    }

    public void setShowingVoiceSuggestions(boolean b) {
        mShowingVoiceSuggestions = b;
    }

    public boolean isVoiceButtonEnabled() {
        return mVoiceButtonEnabled;
    }

    public boolean isVoiceButtonOnPrimary() {
        return mVoiceButtonOnPrimary;
    }

    public boolean isVoiceInputHighlighted() {
        return mVoiceInputHighlighted;
    }

    public boolean isRecognizing() {
        return mRecognizing;
    }

    public boolean getAndResetIsShowingHint() {
        boolean ret = mIsShowingHint;
        mIsShowingHint = false;
        return ret;
    }

    private void revertVoiceInput() {
        InputConnection ic = mContext.getCurrentInputConnection();
        if (ic != null) ic.commitText("", 1);
        mContext.updateSuggestions();
        mVoiceInputHighlighted = false;
    }

    public void commitVoiceInput() {
        if (VOICE_INSTALLED && mVoiceInputHighlighted) {
            InputConnection ic = mContext.getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            mContext.updateSuggestions();
            mVoiceInputHighlighted = false;
        }
    }

    public boolean logAndRevertVoiceInput() {
        if (VOICE_INSTALLED && mVoiceInputHighlighted) {
            mVoiceInput.incrementTextModificationDeleteCount(
                    mVoiceResults.candidates.get(0).toString().length());
            revertVoiceInput();
            return true;
        } else {
            return false;
        }
    }

    public void rememberReplacedWord(CharSequence suggestion,String wordSeparators) {
        if (mShowingVoiceSuggestions) {
            // Retain the replaced word in the alternatives array.
            EditingUtil.Range range = new EditingUtil.Range();
            String wordToBeReplaced = EditingUtil.getWordAtCursor(
                    mContext.getCurrentInputConnection(), wordSeparators, range);
            if (!mWordToSuggestions.containsKey(wordToBeReplaced)) {
                wordToBeReplaced = wordToBeReplaced.toLowerCase();
            }
            if (mWordToSuggestions.containsKey(wordToBeReplaced)) {
                List<CharSequence> suggestions = mWordToSuggestions.get(wordToBeReplaced);
                if (suggestions.contains(suggestion)) {
                    suggestions.remove(suggestion);
                }
                suggestions.add(wordToBeReplaced);
                mWordToSuggestions.remove(wordToBeReplaced);
                mWordToSuggestions.put(suggestion.toString(), suggestions);
            }
        }
    }

    /**
     * Tries to apply any voice alternatives for the word if this was a spoken word and
     * there are voice alternatives.
     * @param touching The word that the cursor is touching, with position information
     * @return true if an alternative was found, false otherwise.
     */
    public boolean applyVoiceAlternatives(EditingUtil.SelectedWord touching) {
        // Search for result in spoken word alternatives
        String selectedWord = touching.word.toString().trim();
        if (!mWordToSuggestions.containsKey(selectedWord)) {
            selectedWord = selectedWord.toLowerCase();
        }
        if (mWordToSuggestions.containsKey(selectedWord)) {
            mShowingVoiceSuggestions = true;
            List<CharSequence> suggestions = mWordToSuggestions.get(selectedWord);
            // If the first letter of touching is capitalized, make all the suggestions
            // start with a capital letter.
            if (Character.isUpperCase(touching.word.charAt(0))) {
                for (int i = 0; i < suggestions.size(); i++) {
                    String origSugg = (String) suggestions.get(i);
                    String capsSugg = origSugg.toUpperCase().charAt(0)
                            + origSugg.subSequence(1, origSugg.length()).toString();
                    suggestions.set(i, capsSugg);
                }
            }
            mContext.setSuggestions(suggestions, false, true, true);
            mContext.setCandidatesViewShown(true);
            return true;
        }
        return false;
    }

    public void handleBackspace() {
        if (mAfterVoiceInput) {
            // Don't log delete if the user is pressing delete at
            // the beginning of the text box (hence not deleting anything)
            if (mVoiceInput.getCursorPos() > 0) {
                // If anything was selected before the delete was pressed, increment the
                // delete count by the length of the selection
                int deleteLen  =  mVoiceInput.getSelectionSpan() > 0 ?
                        mVoiceInput.getSelectionSpan() : 1;
                mVoiceInput.incrementTextModificationDeleteCount(deleteLen);
            }
        }
    }

    public void handleCharacter() {
        commitVoiceInput();
        if (mAfterVoiceInput) {
            // Assume input length is 1. This assumption fails for smiley face insertions.
            mVoiceInput.incrementTextModificationInsertCount(1);
        }
    }

    public void handleSeparator() {
        commitVoiceInput();
        if (mAfterVoiceInput){
            // Assume input length is 1. This assumption fails for smiley face insertions.
            mVoiceInput.incrementTextModificationInsertPunctuationCount(1);
        }
    }

    public void handleClose() {
        if (VOICE_INSTALLED & mRecognizing) {
            mVoiceInput.cancel();
        }
    }


    public void handleVoiceResults(KeyboardSwitcher switcher, boolean capitalizeFirstWord) {
        mAfterVoiceInput = true;
        mImmediatelyAfterVoiceInput = true;

        InputConnection ic = mContext.getCurrentInputConnection();
        if (!mContext.isFullscreenMode()) {
            // Start listening for updates to the text from typing, etc.
            if (ic != null) {
                ExtractedTextRequest req = new ExtractedTextRequest();
                ic.getExtractedText(req, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
            }
        }
        mContext.vibrate();
        mContext.switchToKeyboardView();

        final List<CharSequence> nBest = new ArrayList<CharSequence>();
        for (String c : mVoiceResults.candidates) {
            if (capitalizeFirstWord) {
                c = Character.toUpperCase(c.charAt(0)) + c.substring(1, c.length());
            }
            nBest.add(c);
        }
        if (nBest.size() == 0) {
            return;
        }
        String bestResult = nBest.get(0).toString();
        mVoiceInput.logVoiceInputDelivered(bestResult.length());
        mHints.registerVoiceResult(bestResult);

        if (ic != null) ic.beginBatchEdit(); // To avoid extra updates on committing older text
        mContext.commitTyped(ic);
        EditingUtil.appendText(ic, bestResult);
        if (ic != null) ic.endBatchEdit();

        mVoiceInputHighlighted = true;
        mWordToSuggestions.putAll(mVoiceResults.alternatives);
    }

    public void switchToRecognitionStatusView(final boolean configurationChanging) {
        final boolean configChanged = configurationChanging;
        mHandler.post(new Runnable() {
            public void run() {
                mContext.setCandidatesViewShown(false);
                mRecognizing = true;
                View v = mVoiceInput.getView();
                ViewParent p = v.getParent();
                if (p != null && p instanceof ViewGroup) {
                    ((ViewGroup)v.getParent()).removeView(v);
                }
                mContext.setInputView(v);
                mContext.updateInputViewShown();
                if (configChanged) {
                    mVoiceInput.onConfigurationChanged();
                }
        }});
    }

    private void reallyStartListening(boolean swipe, final boolean configurationChanging) {
        if (!mHasUsedVoiceInput) {
            // The user has started a voice input, so remember that in the
            // future (so we don't show the warning dialog after the first run).
            SharedPreferences.Editor editor =
                    PreferenceManager.getDefaultSharedPreferences(mContext).edit();
            editor.putBoolean(PREF_HAS_USED_VOICE_INPUT, true);
            SharedPreferencesCompat.apply(editor);
            mHasUsedVoiceInput = true;
        }

        if (!mLocaleSupportedForVoiceInput && !mHasUsedVoiceInputUnsupportedLocale) {
            // The user has started a voice input from an unsupported locale, so remember that
            // in the future (so we don't show the warning dialog the next time they do this).
            SharedPreferences.Editor editor =
                    PreferenceManager.getDefaultSharedPreferences(mContext).edit();
            editor.putBoolean(PREF_HAS_USED_VOICE_INPUT_UNSUPPORTED_LOCALE, true);
            SharedPreferencesCompat.apply(editor);
            mHasUsedVoiceInputUnsupportedLocale = true;
        }

        // Clear N-best suggestions
        mContext.clearSuggestions();

        FieldContext context = makeFieldContext();
        mVoiceInput.startListening(context, swipe);
        switchToRecognitionStatusView(configurationChanging);
    }

    public void startListening(final boolean swipe, IBinder token,
            final boolean configurationChanging) {
        if (VOICE_INSTALLED) {
            if (!mHasUsedVoiceInput ||
                    (!mLocaleSupportedForVoiceInput && !mHasUsedVoiceInputUnsupportedLocale)) {
                // Calls reallyStartListening if user clicks OK, does nothing if user clicks Cancel.
                showVoiceWarningDialog(swipe, token, configurationChanging);
            } else {
                reallyStartListening(swipe, configurationChanging);
            }
        }
    }


    private boolean fieldCanDoVoice(FieldContext fieldContext) {
        return !mPasswordText
                && mVoiceInput != null
                && !mVoiceInput.isBlacklistedField(fieldContext);
    }

    private boolean shouldShowVoiceButton(FieldContext fieldContext, EditorInfo attribute) {
        return ENABLE_VOICE_BUTTON && fieldCanDoVoice(fieldContext)
                && !(attribute != null
                        && IME_OPTION_NO_MICROPHONE.equals(attribute.privateImeOptions))
                && SpeechRecognizer.isRecognitionAvailable(mContext);
    }

    public void loadSettings(EditorInfo attribute, SharedPreferences sp) {
        mHasUsedVoiceInput = sp.getBoolean(PREF_HAS_USED_VOICE_INPUT, false);
        mHasUsedVoiceInputUnsupportedLocale =
                sp.getBoolean(PREF_HAS_USED_VOICE_INPUT_UNSUPPORTED_LOCALE, false);

        mLocaleSupportedForVoiceInput = SubtypeSwitcher.getInstance().isVoiceSupported(
                SubtypeSwitcher.getInstance().getInputLocaleStr());

        if (VOICE_INSTALLED) {
            final String voiceMode = sp.getString(PREF_VOICE_MODE,
                    mContext.getString(R.string.voice_mode_main));
            mVoiceButtonEnabled = !voiceMode.equals(mContext.getString(R.string.voice_mode_off))
                    && shouldShowVoiceButton(makeFieldContext(), attribute);
            mVoiceButtonOnPrimary = voiceMode.equals(mContext.getString(R.string.voice_mode_main));
        }
    }

    public void destroy() {
        if (VOICE_INSTALLED && mVoiceInput != null) {
            mVoiceInput.destroy();
        }
    }

    public void onConfigurationChanged(boolean configurationChanging) {
        if (mRecognizing) {
            switchToRecognitionStatusView(configurationChanging);
        }
    }
    @Override
    public void onCancelVoice() {
        if (mRecognizing) {
            mRecognizing = false;
            mContext.switchToKeyboardView();
        }
    }

    @Override
    public void onVoiceResults(List<String> candidates,
            Map<String, List<CharSequence>> alternatives) {
        if (!mRecognizing) {
            return;
        }
        mVoiceResults.candidates = candidates;
        mVoiceResults.alternatives = alternatives;
        mHandler.updateVoiceResults();
    }

    public FieldContext makeFieldContext() {
        SubtypeSwitcher switcher = SubtypeSwitcher.getInstance();
        return new FieldContext(mContext.getCurrentInputConnection(),
                mContext.getCurrentInputEditorInfo(), switcher.getInputLocaleStr(),
                switcher.getEnabledLanguages());
    }

    private class VoiceResults {
        List<String> candidates;
        Map<String, List<CharSequence>> alternatives;
    }
}