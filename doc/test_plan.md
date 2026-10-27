# Release Test Plan

Run this full test pass **before every release**, meaning any Play Store upload or any APK/AAB given to users. A release is ready only when every case passes on every test device. Write the results into the release notes or the PR (see [Recording results](#6-recording-results)).

Most cases are automated by [`tools/release_tests.py`](../tools/release_tests.py) (see [Automated run](#5-automated-run)). The rest are manual, as marked in the tables.

The iOS app (`weiqi-ios`) has the same game rules and its own test suite. [ios_test_mapping.md](ios_test_mapping.md) maps every iOS test to the Android case that covers it; the iOS repo's `android_test_mapping.md` maps every case below to the iOS test that covers it.

## 1. Pre-release checks

1. **Version bumped** in all three places; they must match:
   - `src/AndroidManifest.xml`: `android:versionCode` and `android:versionName`
   - `src/BUILD`: both `manifest_values` blocks (`versionCode`, `versionName`)
2. **No local debug overrides.** The premium bypass (see [Bypassing Premium Lock](../README.md#5-bypassing-premium-lock-for-local-builds)) must not ship. Check both the working tree and what is staged for the commit:
   ```bash
   git status --short && git diff --stat && git diff --cached --stat   # review every change going into the release
   grep -n "isPremiumUnlocked = true\|_isPremiumUnlocked = MutableStateFlow(true)" src/java/com/cwave/weiqi/*.kt   # must print nothing
   ```
3. **Build, install and launch the release build**, and confirm the installed version:
   ```bash
   bazel build -c opt --config=android_arm64-v8a //src:release && adb install -r bazel-bin/src/release.apk && adb shell am start -n com.cwave.weiqi/com.cwave.weiqi.MainActivity
   adb shell dumpsys package com.cwave.weiqi | grep -E "versionCode|versionName"
   ```

## 2. Test devices and engine log

Test on at least two phones:
- a **Qualcomm Adreno** phone (e.g. Samsung Galaxy Z Flip 8, Adreno 840). First-run GPU tuning used to corrupt the screen on this GPU.
- a **non-Adreno** phone (e.g. Pixel 11 Pro XL, PowerVR).

Keep the engine log open while testing. Most checks below are confirmed there:
```bash
adb logcat -v time | grep -E "KataGoBridge|KataGoBackend|GameFragment|OpenCL"
```

| Log line | Meaning |
|---|---|
| `AI max visits updated to N` | Difficulty applied: Easy 100, Amateur 500, Advanced 1000, Pro 2500 |
| `Komi updated to K` | 7.5 without handicap, 0.5 with handicap 1–9 |
| `AI fixed_handicap N: <stones>` | N handicap stones placed (1 stone = Q16); White moves next |
| `AI play: color=C, move=M, rootPla=P` | A move or pass sent to the engine (1 = Black, 2 = White). `color` must always equal `rootPla`. |
| `AI play refused: illegal move M` | The engine refused a move (ko, suicide, occupied point). The app then logs `Illegal move refused` and shows "Illegal move". |
| `Refused: not black's turn` (or white's) | The engine refused a move or search for the wrong color. The app must never cause this. |
| `Starting AI search (genmove) for black/white` | The AI is about to move, with this color |
| `AI search completed in X ms. Move: M, visits: V, root visits: R` | The AI played M; V is the visits it spent on that move (never more than the level's budget) |
| `Final score R (lead W-B L), komi after scoring K` | Result of a finished game (Chinese area count, always x.5 with half-point komi). K must be the game's komi. |

## 3. Test cases

| # | Area | Steps | Expected |
|---|---|---|---|
| T1 | First-run GPU tuning | `adb shell pm clear com.cwave.weiqi`, start a recording with `adb shell screenrecord /sdcard/tuning.mp4`, launch the app, wait for the engine to be ready (about 1 minute), then pull and watch the video | No black blocks, lines or triangles anywhere on the screen, during or after tuning. Log shows `Done tuning`, `Engine Initialization Successful` and `VERIFIED RUNTIME BACKEND: GPU_OPENCL` (`CPU_EIGEN` on phones without OpenCL). The top bar badge shows the same backend. |
| T2 | Normal start and update | Force-stop (`adb shell am force-stop com.cwave.weiqi`) and relaunch. Then install the same APK again with `adb install -r` (this is what an update does) and launch | Log shows `Loaded tuning parameters`; the engine is ready within a few seconds; no artifacts. After the reinstall the log shows `Successfully extracted gtp.cfg` (the app uses the config of the new version) and no new GPU tuning. |
| T3 | Difficulty | New Game, pick each available level, START | `AI max visits updated to` 100 / 500 / 1000 / 2500 for Easy / Amateur / Advanced / Pro. Premium locking is covered in [Premium and payment tests](#4-premium-and-payment-tests). |
| T4 | Handicap | Start a game with every handicap level, None and 1–9 | None: empty board, komi 7.5, Black moves first. 1–9: that many Black stones on the standard star points, komi 0.5, White moves first. Stones: 1 Q16; 2 D4 Q16; 3 adds D16; 4 the four corners; 5 corners + K10; 6 corners + D10 Q10; 7 adds K10; 8 corners + D10 Q10 K16 K4; 9 all nine. Undo is off until someone has moved; as Black with handicap the AI opens as White. Under Handicap, the New Game dialog says who gets the stones and who moves first. |
| T5 | Play as | Start each mode, with and without handicap | You are Black: you move first without handicap; the AI opens as White with handicap. You are White: the AI opens as Black without handicap; you move first with handicap. Two Players: the AI never moves; both colors are placed by hand and PASS works for both. AI vs AI: AUTO plays both sides, PAUSE stops, STEP plays one move, Undo and Redo are off; New Game can be opened during self-play and the new game starts without any old move. In every mode the AI only plays its own color. |
| T6 | Moves | Tap a point, then PLACE | The stone appears where tapped; the AI replies with the other color; occupied points can't be selected; PLACE, PASS and NEW GAME are disabled while the AI is thinking. |
| T7 | Pass and game end | PASS; keep passing until both sides pass in a row (or pass twice in Two Players) | After one pass the AI replies. After two passes in a row "Counting score…" shows, then the Game Over dialog with a result ending in .5 (e.g. `W+3.5`). BACK TO BOARD keeps the finished game: "Game Over", the result and the territory stay on the board, which is locked. NEW GAME works. |
| T8 | Undo and redo | Against the AI, play a few moves including a pass, then Undo, then Redo | Undo removes the AI's reply together with your move, so it is your turn again, and the AI does not move by itself. Undo is off until you have made a move (also when the AI opens). It never removes handicap stones. Redo puts both moves back. Two Players: Undo and Redo are one move each. Colors in the log keep alternating. |
| T9 | Cancel | Mid-game, open New Game, change difficulty, handicap and side, press CANCEL | The game continues exactly as before: same side, same difficulty, and you can still place a stone. |
| T10 | Save and restore | Mid-game (after at least one pass), force-stop and relaunch; repeat once while the AI is thinking | Same position and side to move. The replay in the log alternates colors, including `move=pass`. If the app was closed on the AI's turn, the AI moves right after the restore. |
| T11 | Analysis | Toggle ANALYSIS on and off during a game; tap the best candidate, then PLACE | Winrate/score, territory dots and candidate moves appear and update after each move; tapping a candidate previews it there and PLACE plays it; no crash. |
| T12 | Stability and memory | Play about 20 moves against the AI on Pro; run `adb shell dumpsys meminfo com.cwave.weiqi` before and after | No crash or ANR. `GL mtrack` (GPU memory) stays flat. Native heap grows by less each move and levels off (KataGo's cache, about 100 MB at most). |
| T13 | Search budget | For each available level, play Q16 in a new game (and D4 in another) and read `AI search completed … visits: V` | V never exceeds the level's budget (100 / 500 / 1000 / 2500), and each level spends clearly more visits than the one below it. |
| T14 | Rules and coordinates | Two Players. (a) Tap A19, J10, T1. (b) Ko: B19 C19 A18 B18 B17 D18 K10 C17, then Black C18 takes; White retakes B18; then White K4, Black Q16, White B18. (c) Suicide: A1 A2 K10 B2 K11 C1, then Black B1 | (a) The log shows `move=A19`, `J10`, `T1` (no column I, rows from the bottom). (b) The immediate retake is refused with "Illegal move" and nothing changes; after the ko threat the retake works. (c) The multi-stone suicide is refused with "Illegal move" (Chinese rules). |
| T15 | Exact score | Two Players: play the finished "diagonal" game from the iOS tests (Black fills x + y ≥ 18, White x + y ≤ 17, two eyes each; 357 stones), then pass twice. Repeat with handicap 2 | Even game: exactly `B+11.5` (190 − 171 − 7.5 komi). Handicap 2: exactly `B+16.5` (0.5 komi, White +1 per handicap stone). The log shows the komi unchanged after scoring. Undo at game over, then PASS again: the same result. |
| T16 | Game over and relaunch | Two Players: one move, pass twice, BACK TO BOARD, force-stop and relaunch, then Undo | After the relaunch the game is still over with the same result, without the dialog and without scoring again. Undo resumes play with the right color to move. |
| T17 | Settings | Clean install; then start games with several mode, handicap and level combinations, force-stop and relaunch after each | First launch opens New Game on Amateur, no handicap, You are Black. After each relaunch the engine gets the saved visits, komi and handicap, and New Game shows the saved choices. |
| T18 | Languages | Set the app language to Japanese, then Simplified Chinese (Settings > System > Languages > App languages, or `adb shell cmd locale set-app-locales com.cwave.weiqi --locales ja-JP`); open New Game with handicap 2 as Black; end a Two Players game with two passes | The New Game dialog, the handicap line and the result dialog are fully translated (no CANCEL, START, PLAY AS, Game Over or BACK TO BOARD in English). Reset the app language afterwards. |
| T19 | Turn order | Whole run | The app never sends a move or search for the wrong color: every `AI play` has `color` = `rootPla`, and no `Refused: not … turn` appears. |
| U1 | Update from the previous release (optional) | Install the previous release, start a handicap game and play a move, then install this build over it | The saved game comes back unchanged and nothing is refused during the replay. Run when the saved-game format or the handicap rules changed. |

### Manual checks

These need a person to look or listen. Do them on one phone per release, with the release build.

| # | Check | Expected |
|---|---|---|
| M1 | Turn indicator and progress bar in all four modes | The circle above the board shows the color to move; the progress bar runs while the AI thinks or the score is counted. |
| M2 | AI pass and illegal move | When the AI passes, an "AI passed" message appears. A ko retake or suicide shows "Illegal move" and the board doesn't change. |
| M3 | Result display | After two passes, the territory marks and the result look right for the position (Chinese count: stones + territory, minus komi). |
| M4 | Sound | A stone sound plays for every move, a short tone for every pass. |
| M5 | Languages | Look through the T18 screenshots (`t18_*.png`) and the main screen in Japanese and Chinese: no clipped or overlapping text. |

## 4. Premium and payment tests

Premium unlocks the Advanced and Pro levels with a one-time purchase (`weiqi_one_time_product`). Watch the billing log while testing:
```bash
adb logcat -v time | grep BillingManager
```

**Every release** (release build, no premium bypass): P1–P4 and P10. Use a Play account that has **not** bought premium for P1–P4. Never complete a purchase with a real payment method.

**When billing code, the product, or the Play Billing library changed, and before the first release of a new version line:** also P5–P9 (manual). These need a license tester account and the app installed from the internal testing track; setup is in [Testing In-App Purchases](../README.md#testing-in-app-purchases-iap).

| # | Area | Steps | Expected |
|---|---|---|---|
| P1 | Locked state | Fresh install, open New Game | Log `Billing Setup Successful.` The "Unlock Premium AI" banner is shown at the top; Advanced and Pro show a lock; Easy and Amateur can be selected and apply (`AI max visits updated to` 100 / 500). |
| P2 | Purchase sheet opens | Tap Advanced, Pro and the banner, one at a time | Each opens the Google Play purchase sheet for "Unlock Premium AI" with its price. The selected level does not change. |
| P3 | Cancel purchase | Close the sheet (back), then START | Log `User canceled purchase flow.` Still locked; the game starts at the previously selected level. |
| P4 | Billing offline | Turn on airplane mode, force-stop and relaunch, tap a locked level, then turn connectivity back on | No crash. Google Play's billing service runs on the phone, so billing can still connect and Play may still open its sheet (it can't complete a payment offline). Premium stays locked; Easy and Amateur still work. |
| P5 | Successful purchase (manual) | License tester: buy with "Test card, always approves" | Unlocks immediately, without a restart: banner and locks disappear; Advanced gives `AI max visits updated to 1000` and Pro gives 2500. Log `Purchase acknowledged successfully.`; Play Console shows the order as acknowledged (Play refunds unacknowledged purchases after 3 days). |
| P6 | Declined payment (manual) | License tester: buy with "Test card, always declines" | Play shows the error; log `Purchase error: ...`; premium stays locked; no crash. |
| P7 | Pending payment (manual) | License tester: buy with a slow test card (approves after a delay) | Stays locked while the payment is pending; unlocks once it completes, either with the app open or on the next launch. |
| P8 | Purchase restored (manual) | After P5: force-stop and relaunch; uninstall and reinstall; sign in with the same account on a second phone | Premium is unlocked again in every case (restored from Play at startup). |
| P9 | Refund and revoke (manual) | Refund the order with **Revoke access** in Play Console, then relaunch | Premium is locked again; the banner and locks come back. |
| P10 | Premium unlocked | With premium unlocked (a purchase, or the local bypass on a non-release build) start a game on every level | No banner and no locks; all four levels apply (100 / 500 / 1000 / 2500). |

## 5. Automated run

`tools/release_tests.py` drives a connected phone over adb. It taps UI elements by their text (via `uiautomator`), reads the board from screenshots, and checks the engine and billing log.

**Requirements:** `adb`, Python 3 with `numpy` and `Pillow`, and `ffmpeg` (for T1). Install the build under test first (see [Pre-release checks](#1-pre-release-checks)). Keep the phone unlocked with the screen on, and don't touch it while the runner works; a full pass takes about an hour. The runner raises the phone's log buffer to 16 MB (`adb logcat -G 16M`) so a whole step's engine log is kept. On a Mac it runs itself under `caffeinate -i`, because a sleeping Mac pauses the run.

```bash
python3 tools/release_tests.py --out /tmp/release-3.6            # every step; premium state auto-detected
python3 tools/release_tests.py --out /tmp/release-3.6 t1 t2      # selected steps only
python3 tools/release_tests.py --list                            # list steps and the cases they cover
```

Run it once on the release build (premium off: covers P1–P4). Then run it once more on a local build with the premium bypass (premium on), at least steps `t3 t12 t13 t17`: `t3` covers P10, and `t12`, `t13` and `t17` only exercise Advanced and Pro when premium is on. Use `--serial` to pick a phone when several are connected.

```bash
python3 tools/release_tests.py --out /tmp/release-3.6-premium t3 t12 t13 t17   # premium-on build
```

| Step | Covers | Notes |
|---|---|---|
| `t1` | T1 | **Clears the app's data** to force tuning; records the screen and scans every frame for black blocks and dark triangles. Launch-animation frames can be flagged; watch the saved recording to confirm. |
| `t2` | T2 | |
| `t3` | T3, P1–P4 (premium off) or P10 (premium on) | Opens the Play purchase sheet and only closes it; it never buys. Turns on airplane mode briefly for P4, then restores Wi-Fi and mobile data. |
| `t4` | T4 | |
| `t5` | T5 | |
| `t6-8` | T6, T7, T8 | |
| `t9` | T9 | |
| `t10` | T10 | |
| `t11` | T11 | |
| `t12` | T12 | Uses Pro when premium is on, Amateur otherwise. |
| `t13` | T13 | All four levels when premium is on, Easy and Amateur otherwise. |
| `t14` | T14 | |
| `t15` | T15 | Taps about 750 times; takes about 10 minutes. |
| `t16` | T16 | |
| `t17` | T17 | The first-launch part runs in `t1` (after the clean install). |
| `t18` | T18 | Changes the app language and restores it at the end. Needs Android 13 or later. |
| (all) | T19 | Checked over every step that ran. |
| `upgrade` | U1 | Optional, not part of a default run: `--previous-apk old.apk`. **Uninstalls the app** first. |

**Output** goes to the `--out` folder: `summary.md` (a results table for the release notes), `results.json`, screenshots, the T1 recordings, and `actions.log` (every tap and swipe with a timestamp). The exit code is non-zero if any check fails.

**Not automated:** P5–P9 (they need a license tester account and the internal testing track), the manual checks M1–M5, watching the T1 recording when frames are flagged, and testing on the second device.

## 6. Recording results

Add a table like this to the release notes or the PR, one column per device and premium state. Attach each run's `summary.md`.

| Case | Pixel 11 Pro XL (premium off) | Galaxy Z Flip 8 (premium off) | … |
|---|---|---|---|
| Pre-release checks | Pass | Pass | |
| T1–T19 | Pass | Pass | |
| M1–M5 | Pass | | |
| P1–P4, P10 | Pass | Pass | |
| P5–P9 | Pass / not required | | |
