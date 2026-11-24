Nusuk Helper - two separate modes
  Nusuk Helper icon : enter codes (floating box: Start/Stop). Reads final.txt, moves each code to used.txt.
  Nusuk Quiz icon   : answer door questions (floating box: Dump / Answer / Auto / X).
                      Answers from its own memory (quiz_answers.json, offline) when the question is known,
                      otherwise asks your AI endpoint (any OpenAI-compatible API, no Gemini), then learns from Nusuk's right/wrong screen.
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

Permissions: Internet is used when you press 'Download and merge answers' and, while Quiz Auto runs, to call the Groq API for questions not in memory. Nothing runs in the background.

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

=== v16: Groq API instead of Gemini Live ===
- Gemini Live, voice prompts (text-to-speech), caption reading, "Ask" and "Guess" are all removed.
- Unknown question -> one short request to Groq: system "Reply only with the single digit (1, 2, 3, or 4) ...", user = question + numbered options,
  max_tokens 2, temperature 0 (reasoning models such as gpt-oss/qwen get max_completion_tokens 512 so they can finish thinking).
- Setup: Nusuk Helper app -> paste Groq API key + model -> "Save Groq settings". Default model openai/gpt-oss-20b (use any chat model enabled in your Groq project: gpt-oss-120b, qwen/..., etc.).
- NEVER taps a blind option. Only a clear single digit 1-4 from Groq is tapped. If the API fails (offline, 429 rate limit, odd reply)
  it taps nothing, shows the reason in the box and retries (honours Groq's retry-after).
  Auto stops (nothing tapped) on: bad key/model (401/403/404/400), daily quota reached (retry-after > 2 min), or 6 failures in a row on one question.
- Groq answers are stored as 'unverified' and corrected by Nusuk's right/wrong screen, as before.
- Log columns: quiz_history.csv no longer has gemini_caption; source is now memory / groq.
- Test the API part with plain javac: tests/LlmTest.java and tests/ResultTest.java (uses a local mock server).

=== v17: custom endpoints + learns with Auto off ===
- Quiz settings now have AI endpoint 1 and an optional backup endpoint 2: each = https URL + API key + model name
  (Groq, Cerebras, Mistral, Gemini OpenAI mode, OpenRouter, your own server...). A base URL ending in /v1 also works.
  'Save and test endpoints' asks a sample question and shows OK / the exact error. Old Groq key/model settings are carried over.
- If endpoint 1 fails (rate limit, error) endpoint 2 is tried. If all fail: nothing is tapped, retry, Auto stops on bad key/model or 6 failures.
- Wrong options already learned for a question are left out of what the AI is asked (it chooses among fewer options).
- Learning with Auto OFF: while the floating box is shown it watches Nusuk. When you answer by hand it notes which option you tapped
  (from the tap event, or the option shown as selected) and learns from the right/wrong page. It never taps by itself.
- On a wrong page that also states 'The correct answer is X' and X is exactly one option, that option is learned as right.
- After installing, re-enable the accessibility service (it now also listens for taps). Check quiz_result_log.txt:
  'UNKNOWN' means the result wording was not recognised; send me a line from it and I will add it.
