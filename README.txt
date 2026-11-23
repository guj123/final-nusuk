Nusuk Helper - two separate modes
  Nusuk Helper icon : enter codes (floating box: Start/Stop). Reads final.txt, moves each code to used.txt.
  Nusuk Quiz icon   : answer door questions (floating box: Dump / Answer / Auto / X).
                      Answers from its own memory (quiz_answers.json, offline) when the question is known,
                      otherwise follows Gemini Live's caption, then learns from Nusuk's right/wrong screen.
Showing one floating box switches the other one off.

Files in the chosen folder (default Download/C):
  final.txt, used.txt, results.txt           codes
  quiz_answers.json                          the learned answers (grows by itself). Format:
                                               {"right": {"question": "answer"}, "unverified": {...}, "wrong": {"question": ["answer", ...]}}
                                             Edit it by hand or share it; keys are lower-case questions without punctuation.
  quiz_import.json                           optional: put a JSON file here (or use 'Download and merge answers' in the Nusuk Quiz screen);
                                             it is merged as 'unverified', then renamed to quiz_import.done.json
  quiz_history.csv, quiz_result_log.txt      what it did, and the raw screen text after each answer
  dump_*.txt                                 "Dump" button output

Build: upload everything (keep the hidden .github folder) to a private GitHub repository -> Actions -> Build APK ->
download the NusukHelper-apk artifact. (Gradle ignores the tests/ folder; they run with plain javac.)

Permissions: Internet is used ONLY when you press 'Download and merge answers' (https link you type, max 5 MB). Nothing runs in the background.

=== Update: one app, two floating boxes ===
- Open the app: "Show CODES box" or "Show QUIZ box". Showing one hides the other.
- Screen now stays on while Start/Auto runs (keep-screen-on flag + wake lock). Stop/X releases it.
- CODES: an unrecognised result no longer stops the run. The code is moved to the end of final.txt (not logged as used)
  and the loop continues; it stops only after 5 unrecognised results in a row. If the entry form is lost it presses Back up to 3 times.
- QUIZ Auto now plays the whole flow: choose a door -> "Open the door" -> answer -> Check -> result page
  (right or wrong, both have "Play again") -> Play again. It plays MAX_DOORS (4) doors per Auto press (constant in QuizAccessService).
- Only the Nusuk window (com.moh.nusukapp) is read/clicked. Gemini caption must be stable 3 ticks; "both/either" captions are ignored.
- After installing, re-enable the single "Nusuk Helper" accessibility service.

=== v5 ===
- Quiz: half-spoken answers / one keyword / ordinals ("second option") are accepted. No usable answer after 14 s -> guesses an option not known wrong and learns from the result, so Auto never stalls.
- Codes: phone keyboard is hidden while Start runs (restored on Stop).

=== v6 ===
- Quiz Auto runs until you press Stop (MAX_DOORS = 0). Box: Auto / Guess on-off / X. Long-press the status text = save a dump.

=== v7 ===
- Auto stops at 0 keys; tap status text = dump; Guess default off (waits for Gemini, never blind-clicks).

=== v8 ===
- Ask button: the app speaks the question prompt through the phone speaker (text-to-speech) so Gemini Live hears it; up to 3 tries per question.

=== v9 ===
- Voice: full instruction once per Auto start, then only "Answer." / "Do your best."
- Lock button: pocket mode (invisible touch-blocking layer + dim screen; Gemini still sees the screen). Hold status text 1.5 s to unlock.

=== v10 ===
- Voice: full instruction once, then only "Answer." Unlock: double-tap the status text.

=== v11 ===
- Full sentence is spoken the moment Auto is pressed; afterwards only "Answer."

=== v12 ===
- Captions like "4. Mecca" / "2) Taif" understood; own spoken prompt ignored in captions.
- Lock on CODES and QUIZ boxes (touches pass through only while the app sends its own gestures). Double-tap status text to unlock.
- Compact single-line buttons (no more L/o/c/k on separate lines); quiz box has two rows.

=== v13 ===
- Code entry: trusts SET_TEXT (no second paste), clears before paste, and final guard keeps the code exactly once.
- Quiz: identical repeated captions (Option 2 twice) now detected via Gemini window events; Mecca=Makkah, Medina=Madinah synonyms; status shows what it heard when unclear.

=== v14 ===
- Stray single digits in the Gemini window are no longer read as an option number (only "4. Mecca" style).
- Ask waits until Gemini is quiet (max 6 s) so it does not talk over Gemini.

=== v15 ===
- Root cause of "always option 1": the previous answer stayed in the Gemini window and was read as the new answer. Now only lines that appeared after the question count.
- Ask retries until answered (no limit), never guesses.
- Briefing: "just reply with the option number only"; best installed US voice, slower rate.
- Scrolls the Nusuk screen when an option is off-screen or under the Gemini caption window.
