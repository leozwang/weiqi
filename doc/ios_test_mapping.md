# Android coverage of the iOS tests

The iOS app (`weiqi-ios`, version 1.15) has the same game rules. Its [TESTING.md](../../weiqi-ios/TESTING.md) lists 54 automated tests and a 10-item manual checklist. This page maps each of them to the Android case in [test_plan.md](test_plan.md) and the step of [`tools/release_tests.py`](../tools/release_tests.py) that runs it. The reverse mapping (every Android case to its iOS test) is [android_test_mapping.md](../../weiqi-ios/android_test_mapping.md) in the iOS repo.

The Android tests drive the installed release app over adb. Engine behavior (rules, scoring, search budget) is checked through the app itself: moves are tapped in Two Players mode and the results are read from the screen and the engine log. The iOS UI tests read the same facts from an engine status that only Debug builds show (search limit, komi, handicap, replayed moves, memory, refused out-of-turn requests).

Status values:
- **Covered**: an Android check tests the same behavior.
- **Covered (Android form)**: the same behavior, checked in the way the Android app or Google Play works. The note says how.
- **Manual**: covered by a manual case only.
- **N/A**: specific to the iOS test setup.

## EngineTests (12)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testHostAppStaysIdleDuringEngineTests` | The app skips its own engine start-up while hosting the tests | — | N/A: there is no in-app test host on Android |
| `testEveryDifficultyLevelReachesTheEngine` | Easy 100 / Amateur 500 / Advanced 1000 / Pro 2500 become the engine's limits | T3, P10 (`t3`) | Covered: `AI max visits updated to N` for every level |
| `testDifficultyLevelControlsSearchBudget` | All four levels, openings Q16 and D4: the search stops at the budget and the chosen move never gets more visits than it; each level searches over 1.5× more than the one below | T13 (`t13`) | Covered: visits of each AI move, from the engine log. iOS checks all four levels in both of its runs |
| `testEveryHandicapLevelPlacesTheStandardStones` | Handicap 1–9: standard star points, White first, komi 0.5, stones stay | T4 (`t4`) | Covered: stones read from the screen for every level |
| `testHandicapSetupSurvivesUndo` | Undo never removes handicap stones; cannot undo past the setup | T8 (`t6-8`), T4 | Covered |
| `testScoreEvenGameMatchesExactAreaCount` | A finished game scores exactly `B+11.5`; komi unchanged | T15 (`t15`) | Covered: the same game, tapped move by move |
| `testScoreHandicapGameIncludesChineseCompensation` | The same board from a 2-stone game scores exactly `B+16.5` | T15 (`t15`) | Covered |
| `testUndoAtGameOverThenPassGivesTheSameScore` | Undo of the last pass at game over, then pass again: the same `B+11.5` | T15 (`t15`) | Covered |
| `testFinalScoreIsRoundedAndKeepsKomi` | Results end in .5; scoring never changes the komi | T7, T15, T16 | Covered: every result is checked for .5; the engine logs the komi after scoring |
| `testMultiStoneSuicideIsIllegal` | Multi-stone suicide is refused (Chinese rules) | T14 (`t14`) | Covered |
| `testImmediateKoRecaptureIsIllegal` | Immediate ko recapture is refused; a ko threat elsewhere is allowed | T14 (`t14`) | Covered, plus the retake after the threat |
| `testOutOfTurnRequestsKeepHistory` | Out-of-turn moves are refused and counted, without losing the game record | T19 (all steps) | Covered (Android form): the log audit proves the app never sends a move for the wrong color. The engine refuses such moves (same code as iOS), but no test sends one on purpose. |

## StoreTests (9)

iOS runs these against a local StoreKit test session. Google Play has no local equivalent, so the purchase flows need a license tester account and the internal testing track ([Premium and payment tests](test_plan.md#4-premium-and-payment-tests)).

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testProductIsConfiguredAsNonConsumable` | The product loads: non-consumable, name, price | P2 (`t3`) | Covered (Android form): the Play purchase sheet opens for the product; the name and price are seen there |
| `testLevelsAreLockedWithoutPurchase` | Locked by default | P1 (`t3`) | Covered |
| `testPurchaseUnlocksLevels` | Buying unlocks Advanced and Pro | P5 | Manual |
| `testPurchaseIsRememberedAtNextLaunch` | A relaunched app finds the purchase | P8 | Manual |
| `testRefundLocksLevelsAgain` | A refund locks again | P9 | Manual (locks at the next launch on Android) |
| `testRestorePurchases` | Restore Purchases unlocks a previous purchase | P8 | Manual. Android has no Restore button: Play restores purchases at startup, also after a reinstall |
| `testAskToBuyStaysLockedUntilApproved` | A pending purchase stays locked until approved | P7 | Manual (slow test card) |
| `testFailedPurchaseShowsErrorAndStaysLocked` | A failed purchase shows an error and stays locked | P6 | Manual (declining test card) |
| `testOfflineStoreKeepsLevelsLockedAndCanRetry` | Offline: no product, nothing can be bought, stays locked; the retry loads the product once online | P4 (`t3`) | Covered (Android form): airplane mode; premium stays locked, Easy and Amateur still work |

## ModelTests (6)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testDefaultSettingsForFirstLaunch` | First launch: Black, no handicap, Amateur | T17 (`t1`) | Covered: checked in the New Game dialog right after the clean install |
| `testSettingsRoundTripForEveryModeHandicapAndLevel` | Every mode × handicap × level is saved and loaded | T17 (`t17`) | Covered: every mode, handicaps 0, 1, 3, 9 (and 5 with premium), every available level. Each combination relaunches the app, so it is a sample rather than all 64 |
| `testLegacyPlayAsTitlesStillLoad` | Settings saved by older versions still load | U1 (`upgrade`, optional) | Covered (Android form): the saved mode names never changed on Android; U1 restores a game saved by the previous release |
| `testGameRecordRoundTrip` | Saved moves, including passes, round-trip | T10, T16 | Covered |
| `testFinishedGameResultRoundTrip` | The result of a finished game is saved with its move count | T16 (`t16`) | Covered: same result after the relaunch, without scoring again |
| `testBoardCoordinates` | GTP coordinates: no column I, rows from the bottom | T14 (`t14`) | Covered: taps at A19, J10 and T1 |

## GameplayUITests (13)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testEveryPlayAsMode` | All 4 modes: who moves first, the AI moves only on its turns, self-play continues, Pass/Undo/Redo availability | T5 (`t5`), T4 | Covered. Android shows no player names; the turn circle is checked manually (M1) |
| `testPlayAsWithHandicap` | Two Players with handicap: White first, the AI never moves, both colors pass; AI vs AI with handicap keeps playing | T5 (`t5`) | Covered |
| `testPlayAsBlackUndoReturnsToMyTurn` | Undo takes back your move and the reply; the AI doesn't replay by itself; Redo restores both | T8 (`t6-8`) | Covered |
| `testPlayAsWhiteKataGoOpensAndUndo` | The AI (Black) opens; Undo returns to your turn | T8, T5 | Covered |
| `testTwoPlayersUndoIsOneMove` | Two Players: Undo and Redo are one move | T8 | Covered |
| `testAIvsAINewGameAvailableAndNoStaleMoves` | AI vs AI: NEW GAME always enabled; no old moves leak into the new game | T5 (`t5`) | Covered for "no old moves". Differs by decision: on Android NEW GAME is disabled while the AI thinks, so the test opens New Game between two self-play moves |
| `testHandicapAsBlackKataGoOpensAndUndo` | 2 stones as Black: the AI opens; Undo disabled until you move | T4 | Covered |
| `testHandicapAsWhiteIMoveFirst` | Handicap as White: you move first | T5, T4 | Covered |
| `testHandicapBoundaries` | Handicap 0…9 limits, the "who receives the stones" line, 9-stone and 1-stone games | T4, T18 | Covered. Android's handicap row only offers None and 1–9, so there are no limits to test |
| `testCaptureHandicapScreens` | Screenshots of the New Game dialog and a handicap game | T4, T5, T18 | Covered: the screenshots are saved in the output folder (`t4_new_game_h2_*.png`, `t4_black_h2.png`, `t18_*.png`) |
| `testPaymentGatesAdvancedAndProLevels` | Free: banner, lock and paywall; Premium: none; Easy/Amateur always playable | P1, P2, P10 (`t3`) | Covered |
| `testProGameWhenPurchased` | Premium: a Pro game starts and the AI plays; Pass and PLACE stay off while it thinks | P10, T6, T12, T13 | Covered in the premium-on run (`t3`, `t12`, `t13`); T6 checks the controls while the AI thinks |
| `testGameOverUndoAndRelaunch` | Two passes end the game; "Counting score…", then an x.5 result; locked board; relaunch keeps it without the dialog or re-scoring; Undo then pass gives the same result; NEW GAME works | T16 (`t16`), T7, T15 | Covered |

## BoardUITests (3)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testEveryHandicapLevelOnTheBoard` | None and 1–9: the stones on screen, komi 7.5 / 0.5, who moves first, Undo off | T4 (`t4`) | Covered |
| `testPlacingStonesAndCoordinates` | Taps at A19, J10, T1 land there; colors alternate; an occupied point can't be selected | T6 (`t6-8`), T14 (`t14`) | Covered |
| `testIllegalMovesAreRefusedOnTheBoard` | Ko retake and multi-stone suicide show "Illegal move" and change nothing; the retake after a ko threat works | T14 (`t14`), M2 | Covered |

## GameFlowUITests (8)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testFirstLaunchDefaults` | No saved data: Black, Amateur, no handicap, in the engine and in New Game | T17 (`t1`) | Covered. Differs by decision: iOS starts a game with the defaults right away; Android opens New Game |
| `testEveryLevelAppliesToTheEngine` | Each playable level picked in New Game reaches the engine and stays selected; a locked level never does | T3, P1, P3, P10 (`t3`) | Covered |
| `testSettingsSurviveRelaunch` | Mode, level and handicap combinations survive a relaunch: engine, board and New Game | T17 (`t17`) | Covered |
| `testCancelNewGameKeepsTheGame` | Cancel (or swiping the sheet down) keeps the game, the side and the level; play goes on | T9 (`t9`) | Covered (CANCEL; the Back button is not tested on Android) |
| `testPassAgainstKataGo` | The AI answers a pass with a move; passes between moves never end the game | T7 (`t6-8`) | Covered |
| `testUndoTakesBackAPass` | Undo takes back your pass with the AI's reply; the AI doesn't move by itself; Redo puts both back | T8 (`t6-8`) | Covered |
| `testRelaunchMidGameAndWhileKataGoThinks` | Relaunch after a pass restores the game; closed while the AI thinks, it moves right after the relaunch | T10 (`t10`) | Covered |
| `testLongSelfPlayStaysStable` | 30 self-play moves (Pro with premium): no crash, memory levels off, NEW GAME works | T12 (`t12`) | Covered (Android form): 20 moves against the AI, native heap and GPU memory from `dumpsys meminfo` |

## AnalysisUITests (1)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testAnalysisRecommendationsAndPlace` | Winrate, score lead and ranked candidates appear and refresh; tapping a candidate previews it; PLACE plays it; off restores the status line | T11 (`t11`) | Covered |

## LocalizationUITests (2)

| iOS test | Checks | Android | Status |
|---|---|---|---|
| `testJapanese`, `testSimplifiedChinese` | New Game sheet, handicap line, status line and Game Over dialog translated, no English left; screenshots attached | T18 (`t18`), M5 | Covered |

## Manual checklist (10)

| iOS item | Android |
|---|---|
| 1. Launch; Settings shows the version | T2; the installed version is checked in the [pre-release checks](test_plan.md#1-pre-release-checks). The Android app does not show its version (kept by decision). |
| 2. Turn ring and thinking spinner follow the side to move | M1 |
| 3. Free user: Easy/Amateur start, Advanced/Pro locked, price shown | P1, P2 |
| 4. Buying Premium; cancelling changes nothing | P5, P3 |
| 5. Premium user: Pro thinks longer; purchase survives relaunch, reinstall, refund | T13, P8, P9 |
| 6. AI pass message; "Counting score…"; result matches a Chinese count | M2, T7, T15, M3 |
| 7. Sound and haptics toggles | M4. The Android app has no sound or haptics settings: the sound is always on. |
| 8. Japanese and Simplified Chinese: no clipped or overlapping text | T18, M5 |
| 9. Memory on a device during a long Pro game | T12 (`t12`, automated on the phone) |
| 10. Update from the previous release (optional) | U1 (`upgrade`, optional) |

## Test setup

| iOS | Android |
|---|---|
| Free and Premium test-plan configurations | Two runs: the release build (premium off) and a local build with the premium bypass (premium on); see [Automated run](test_plan.md#5-automated-run) |
| Engine status in Debug builds | The engine log (`adb logcat`) |
| Uninstall before each run (clean install) | T1 clears the app data |
| iPhone and iPad simulators | An Adreno phone and a non-Adreno phone |
| Release build for generic iOS devices; the script checks its version and that no test hooks are in it | The release APK is built and installed in the pre-release checks (version in all three places, no premium bypass) |

## Remaining differences (by decision)

- NEW GAME is disabled while the AI thinks, also in AI vs AI (iOS: always enabled). PAUSE stops self-play first.
- The first launch opens New Game (iOS starts a game with the default settings right away).
- The app version is not shown in the app.
- No Restore Purchases button (Play restores purchases by itself) and no sound or haptics settings.
