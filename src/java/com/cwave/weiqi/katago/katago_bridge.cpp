#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <iostream>
#include <sstream>
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <chrono>
#include <cmath>

// KataGo includes
#include "core/global.h"
#include "core/mainargs.h"
#include "core/config_parser.h"
#include "game/board.h"
#include "search/timecontrols.h"
#include "program/setup.h"
#include "program/gtpconfig.h"
#include "program/playutils.h"
#include "search/asyncbot.h"
#include "program/play.h"
#include "neuralnet/nneval.h"
#include "main.h"


#define TAG "KataGoBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global state for the engine
namespace {

    std::mutex engineMutex;
    std::unique_ptr<AsyncBot> bot;
    NNEvaluator* g_nnEval = nullptr;
    std::unique_ptr<Logger> logger;
    std::unique_ptr<Rand> seedRand;
    bool initialized = false;


    void oneTimeInit() {
        static std::once_flag flag;
        std::call_once(flag, []() {
            LOGI("Step: Board::initHash()");
            Board::initHash();
            LOGI("Step: ScoreValue::initTables()");
            ScoreValue::initTables();
        });
    }

    bool fileExists(const std::string& name) {
        struct stat buffer;
        return (stat(name.c_str(), &buffer) == 0);
    }

    long getFileSize(const std::string& name) {
        struct stat buffer;
        if (stat(name.c_str(), &buffer) == 0) return buffer.st_size;
        return -1;
    }

    std::vector<std::string> split(const std::string& s) {
        std::vector<std::string> result;
        std::stringstream ss(s);
        std::string item;
        while (ss >> item) result.push_back(item);
        return result;
    }

    // Parses a GTP color argument ("b", "black", "w", "white"; any case).
    bool parsePlayer(const std::string& s, Player& pla) {
        const std::string lower = Global::toLower(s);
        if (lower == "b" || lower == "black") { pla = P_BLACK; return true; }
        if (lower == "w" || lower == "white") { pla = P_WHITE; return true; }
        return false;
    }

    const char* colorName(Player pla) { return pla == P_BLACK ? "black" : "white"; }

    // Moves must strictly alternate. AsyncBot/Search silently wipe the game record when asked
    // to move or search for the side that is not to play, which breaks undo and ko tracking.
    std::string notYourTurn(Player pla) {
        LOGE("Refused: not %s's turn", colorName(pla));
        return std::string("? not ") + colorName(pla) + "'s turn";
    }

    // Executes one GTP command. The caller holds engineMutex and the engine is initialized.
    // The engine-side behavior matches the iOS app's bridge (weiqi-ios Weiqi/KataGoBridge.cpp).
    std::string runCommand(const std::string& command) {
        std::vector<std::string> parts = split(command);
        if (parts.empty()) return "= ";
        const std::string mainCmd = parts[0];

        if (mainCmd == "name") return "= KataGo";
        if (mainCmd == "version") return "= " + Version::getKataGoVersion();
        if (mainCmd == "protocol_version") return "= 2";

        if (mainCmd == "komi") {
            if (parts.size() < 2) return "? missing value";
            float k = 7.5f;
            try { k = std::stof(parts[1]); } catch (...) { return "? invalid value"; }
            bot->setKomiIfNew(k);
            LOGI("Komi updated to %.1f", k);
            return "= ";
        }

        if (mainCmd == "get_komi") {
            return "= " + Global::strprintf("%.1f", bot->getRootHist().rules.komi);
        }

        // Read-only view of the search limits (lets tests check the difficulty levels).
        if (mainCmd == "kata-get-param") {
            if (parts.size() < 2) return "? missing parameter";
            const SearchParams& params = bot->getParams();
            if (parts[1] == "maxVisits") return "= " + std::to_string(params.maxVisits);
            if (parts[1] == "maxPlayouts") return "= " + std::to_string(params.maxPlayouts);
            if (parts[1] == "numSearchThreads") return "= " + std::to_string(params.numThreads);
            return "? unknown parameter";
        }

        if (mainCmd == "boardsize") {
            if (parts.size() < 2) return "? missing size";
            int s = 19;
            try { s = std::stoi(parts[1]); } catch (...) { return "? invalid size"; }
            if (s < 7 || s > 19) return "? unsupported size";
            Board board(s, s);
            BoardHistory hist(board, P_BLACK, bot->getRootHist().rules, 0);
            bot->setPosition(P_BLACK, board, hist);
            LOGI("Board size changed to %dx%d", s, s);
            return "= ";
        }

        if (mainCmd == "set_max_visits") {
            if (parts.size() < 2) return "? missing visits";
            int64_t v = 400;
            try { v = std::stoll(parts[1]); } catch (...) { return "? invalid visits"; }
            SearchParams params = bot->getParams();
            params.maxVisits = v;
            params.maxPlayouts = v;
            bot->setParams(params);
            LOGI("AI max visits updated to %ld", (long)v);
            return "= ";
        }

        if (mainCmd == "genmove") {
            Player pla;
            if (parts.size() < 2 || !parsePlayer(parts[1], pla)) return "? missing or invalid color";
            if (pla != bot->getRootPla()) return notYourTurn(pla);

            LOGI("Starting AI search (genmove) for %s...", colorName(pla));
            auto startTime = std::chrono::steady_clock::now();
            Loc moveLoc = bot->genMoveSynchronous(pla, TimeControls());
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - startTime).count();
            const long long rootVisits = (long long)bot->getSearch()->getRootVisits();

            std::string moveStr = Location::toString(moveLoc, bot->getRootBoard());
            bot->makeMove(moveLoc, pla);
            // The tree is now re-rooted at the chosen move: these are the visits KataGo spent on the
            // move it played, which never exceed the search budget (Easy 100 ... Pro 2500).
            const long long moveVisits = (long long)bot->getSearch()->getRootVisits();
            LOGI("AI search completed in %lld ms. Move: %s, visits: %lld, root visits: %lld",
                 (long long)duration, moveStr.c_str(), moveVisits, rootVisits);
            return "= " + moveStr;
        }

        if (mainCmd == "think") {
            Player pla;
            if (parts.size() < 2 || !parsePlayer(parts[1], pla)) return "? missing or invalid color";
            if (pla != bot->getRootPla()) return notYourTurn(pla);
            int64_t visits = 400;
            if (parts.size() >= 3) {
                try { visits = std::stoll(parts[2]); } catch (...) { return "? invalid visits"; }
            }
            LOGI("AI think: color=%s, visits=%ld", colorName(pla), (long)visits);
            SearchParams oldParams = bot->getParams();
            SearchParams newParams = oldParams;
            newParams.maxVisits = visits;
            newParams.maxPlayouts = visits;
            // setParamsNoClearing keeps the search tree before and after this search.
            bot->setParamsNoClearing(newParams);
            bot->genMoveSynchronous(pla, TimeControls());
            bot->setParamsNoClearing(oldParams);
            return "= ";
        }

        if (mainCmd == "play") {
            Player pla;
            if (parts.size() < 3 || !parsePlayer(parts[1], pla)) return "? missing color or move";
            const std::string& moveStr = parts[2];
            LOGI("AI play: color=%d, move=%s, rootPla=%d", (int)pla, moveStr.c_str(), (int)bot->getRootPla());
            if (pla != bot->getRootPla()) return notYourTurn(pla);
            Loc moveLoc;
            if (!Location::tryOfString(moveStr, bot->getRootBoard(), moveLoc)) return "? invalid move";
            // makeMove() alone is "tolerant": it skips the ko check and accepts multi-stone suicide
            // whatever the rules say. Validate against the configured rules first.
            if (!bot->isLegalStrict(moveLoc, pla) || !bot->makeMove(moveLoc, pla)) {
                LOGE("AI play refused: illegal move %s", moveStr.c_str());
                return "? illegal move";
            }
            return "= ";
        }

        if (mainCmd == "undo") {
            const BoardHistory& hist = bot->getRootHist();
            if (hist.moveHistory.empty()) return "? cannot undo";
            // Replay all but the last move from the initial position (which holds any handicap
            // stones, so undo never removes them).
            Board board = hist.initialBoard;
            BoardHistory newHist(board, hist.initialPla, hist.rules, hist.initialEncorePhase);
            newHist.setAssumeMultipleStartingBlackMovesAreHandicap(hist.assumeMultipleStartingBlackMovesAreHandicap);
            newHist.setInitialTurnNumber(hist.initialTurnNumber);
            for (size_t i = 0; i + 1 < hist.moveHistory.size(); i++) {
                newHist.makeBoardMoveAssumeLegal(board, hist.moveHistory[i].loc, hist.moveHistory[i].pla, nullptr);
            }
            bot->setPosition(newHist.presumedNextMovePla, board, newHist);
            return "= ";
        }

        if (mainCmd == "clear_board") {
            Board board(bot->getRootBoard().x_size, bot->getRootBoard().y_size);
            BoardHistory hist(board, P_BLACK, bot->getRootHist().rules, 0);
            bot->setPosition(P_BLACK, board, hist);
            LOGI("AI clear_board: board reset to %dx%d, black to move", board.x_size, board.y_size);
            return "= ";
        }

        // Places 1-9 Black handicap stones on the star points; White moves next.
        if (mainCmd == "fixed_handicap") {
            if (parts.size() < 2) return "? missing count";
            int n = 0;
            try { n = std::stoi(parts[1]); } catch (...) { return "? invalid count"; }
            if (n < 1 || n > 9) return "? invalid count";
            const Rules rules = bot->getRootHist().rules;
            Board board(bot->getRootBoard().x_size, bot->getRootBoard().y_size);
            if (n == 1) {
                // KataGo has no 1-stone layout: use the upper-right star point (Q16 on 19x19), the
                // first stone of its 2-stone layout.
                int x = (board.x_size <= 12) ? board.x_size - 3 : board.x_size - 4;
                int y = (board.y_size <= 12) ? 2 : 3;
                board.setStone(Location::getLoc(x, y, board.x_size), P_BLACK);
            } else {
                try {
                    PlayUtils::placeFixedHandicap(board, n);
                } catch (const std::exception& e) {
                    return "? " + std::string(e.what());
                }
            }
            BoardHistory hist(board, P_WHITE, rules, 0);
            hist.setAssumeMultipleStartingBlackMovesAreHandicap(true);
            hist.setInitialTurnNumber(board.numStonesOnBoard());
            bot->setPosition(P_WHITE, board, hist);

            std::string stones;
            for (int y = 0; y < board.y_size; y++) {
                for (int x = 0; x < board.x_size; x++) {
                    Loc loc = Location::getLoc(x, y, board.x_size);
                    if (board.colors[loc] == P_BLACK) stones += Location::toString(loc, board) + " ";
                }
            }
            LOGI("AI fixed_handicap %d: %s", n, stones.c_str());
            return "= " + stones;
        }

        // "= <361 cells> <black's captures> <white's captures> <side to move B|W>"
        if (mainCmd == "get_board") {
            const Board& b = bot->getRootBoard();
            std::string resp = "= ";
            for (int y = 0; y < b.y_size; y++) {
                for (int x = 0; x < b.x_size; x++) {
                    Player color = b.colors[Location::getLoc(x, y, b.x_size)];
                    resp += (color == P_BLACK) ? "1" : (color == P_WHITE) ? "2" : "0";
                }
            }
            resp += " " + std::to_string(b.numWhiteCaptures) + " " + std::to_string(b.numBlackCaptures);
            resp += bot->getRootPla() == P_WHITE ? " W" : " B";
            return resp;
        }

        if (mainCmd == "final_score") {
            bot->stopAndWait();
            // computeLead re-roots the search at several probe komi values, so snapshot the real
            // game state and restore it afterwards (as KataGo's own GTP engine does). Otherwise the
            // game would continue with a wrong komi after an undo at game over.
            const SearchParams oldParams = bot->getParams();
            const Player oldPla = bot->getRootPla();
            const Board oldBoard = bot->getRootBoard();
            const BoardHistory oldHist = bot->getRootHist();

            SearchParams scoringParams = oldParams;
            scoringParams.playoutDoublingAdvantage = 0.0;
            scoringParams.conservativePass = true;
            bot->setParams(scoringParams);

            Board board = oldBoard;
            BoardHistory hist = oldHist;
            // Lead from White's perspective (White points - Black points), judged with 500 visits so
            // dead stones and unfinished borders count the way a person would count them.
            const double rawLead = PlayUtils::computeLead(
                bot->getSearchStopAndWait(), NULL, board, hist, oldPla, 500, OtherGameProperties());
            // Round to a result the rules can actually produce (x.5 with a half-point komi).
            double lead = rawLead;
            if (hist.rules.gameResultWillBeInteger()) lead = std::round(lead);
            else lead = std::round(lead + 0.5) - 0.5;

            bot->setPosition(oldPla, oldBoard, oldHist);
            bot->setParams(oldParams);

            std::string result;
            if (lead == 0) result = "0";
            else if (lead < 0) result = "B+" + Global::strprintf("%.1f", -lead);
            else result = "W+" + Global::strprintf("%.1f", lead);
            LOGI("Final score %s (lead W-B %.2f), komi after scoring %.1f",
                 result.c_str(), rawLead, bot->getRootHist().rules.komi);
            return "= " + result;
        }

        if (mainCmd == "kata-get-analysis") {
            nlohmann::json json;
            // Always from Black's perspective, so the UI consistently shows Black's winrate and lead.
            Player perspective = P_BLACK;
            int64_t analysisVisits = 40;
            if (parts.size() >= 3) {
                try { analysisVisits = std::stoll(parts[2]); } catch (...) {}
            }

            Player rootPla = bot->getRootPla();
            LOGI("AI kata-get-analysis: perspective=%d, root=%d, visits=%ld", (int)perspective, (int)rootPla, (long)analysisVisits);

            Search* search = bot->getSearchStopAndWait();
            if (search->getRootNode() == nullptr || search->getRootVisits() < analysisVisits) {
                SearchParams oldP = bot->getParams();
                SearchParams newP = oldP;
                newP.maxVisits = analysisVisits;
                newP.maxPlayouts = analysisVisits;
                bot->setParamsNoClearing(newP);
                search->runWholeSearch(rootPla);
                bot->setParamsNoClearing(oldP);
            }

            bool success = search->getAnalysisJson(
                perspective,
                10,    // analysisPVLen
                false, // preventEncore
                true,  // includePolicy
                true,  // includeOwnership
                false, // includeOwnershipStdev
                false, // includeMovesOwnership
                false, // includeMovesOwnershipStdev
                true,  // includePVVisits
                false, // includeNoResultValue
                json
            );
            if (!success) return "? failed to get analysis";
            return "= " + json.dump();
        }

        LOGE("Unknown GTP command: %s", mainCmd.c_str());
        return "? unknown command " + mainCmd;
    }
}
#include <pthread.h>
#include <unistd.h>

static int pfd[2];
static pthread_t thr;
static const char *tag = "KataGoEngine";

static void *thread_func(void*) {
    ssize_t rdsz;
    char buf[256];
    while((rdsz = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
        if(buf[rdsz - 1] == '\n') buf[rdsz - 1] = 0;
        else buf[rdsz] = 0;
        __android_log_write(ANDROID_LOG_INFO, tag, buf);
    }
    return 0;
}

int start_logger(const char *app_name) {
    tag = app_name;
    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IOLBF, 0);
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);
    if(pthread_create(&thr, 0, thread_func, 0) == -1) return -1;
    pthread_detach(thr);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_cwave_weiqi_katago_KataGoBridge_init(JNIEnv *env, jobject thiz, jstring config_path, jstring model_path) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (initialized) return 0;

    start_logger("KataGoEngine");
    LOGI("--- Engine Initialization Start ---");
    oneTimeInit();

    const char* cConfigPath = env->GetStringUTFChars(config_path, nullptr);
    const char* cModelPath = env->GetStringUTFChars(model_path, nullptr);

    std::string configPath(cConfigPath);
    std::string modelPath(cModelPath);

    LOGI("Config path: %s (Size: %ld)", configPath.c_str(), getFileSize(configPath));
    LOGI("Model path: %s (Size: %ld)", modelPath.c_str(), getFileSize(modelPath));

    if (!fileExists(configPath)) {
        LOGE("Error: Config file not found!");
        env->ReleaseStringUTFChars(config_path, cConfigPath);
        env->ReleaseStringUTFChars(model_path, cModelPath);
        return -1;
    }
    if (!fileExists(modelPath)) {
        LOGE("Error: Model file not found!");
        env->ReleaseStringUTFChars(config_path, cConfigPath);
        env->ReleaseStringUTFChars(model_path, cModelPath);
        return -2;
    }

    int step = 0;
    try {
        step = 10;
        LOGI("Step 10: Parsing config file...");
        ConfigParser cfg(configPath, true);

        LOGI("Step 11: Overriding settings...");
        cfg.overrideKey("logDir", "/dev/null");
        cfg.overrideKey("logAllGTPCommunication", "true");
        cfg.overrideKey("logSearchInfo", "true");
        cfg.overrideKey("logToStderr", "true");

        std::string internalDir = configPath.substr(0, configPath.find_last_of("/"));
        cfg.overrideKey("homeDataDir", internalDir);
        LOGI("Setting homeDataDir to: %s", internalDir.c_str());

        step = 12;
        LOGI("Step 12: Setup::initializeSession()");
        Setup::initializeSession(cfg);

        step = 13;
        LOGI("Step 13: Creating Logger and Rand...");
        logger = std::make_unique<Logger>(&cfg);
        seedRand = std::make_unique<Rand>();

        step = 14;
        LOGI("Step 14: Setup::loadSingleParams()");
        SearchParams params = Setup::loadSingleParams(cfg, Setup::SETUP_FOR_GTP);

        int expectedConcurrentEvals = params.numThreads;
        int defaultMaxBatchSize = std::max(8, ((expectedConcurrentEvals + 3) / 4) * 4);

        step = 15;
        LOGI("Step 15: Setup::initializeNNEvaluator() - threads=%d, batch=%d", expectedConcurrentEvals, defaultMaxBatchSize);
        std::string expectedSha256 = "";
        g_nnEval = Setup::initializeNNEvaluator(
            modelPath, modelPath, expectedSha256, cfg, *logger, *seedRand, 
            expectedConcurrentEvals, Board::DEFAULT_LEN, Board::DEFAULT_LEN, 
            defaultMaxBatchSize, true, false, Setup::SETUP_FOR_GTP
        );

        step = 16;
        LOGI("Step 16: Setup::loadSingleRules()");
        Rules rules = Setup::loadSingleRules(cfg, false);

        step = 17;
        LOGI("Step 17: Creating AsyncBot...");
        std::string searchRandSeed = cfg.contains("searchRandSeed") ? cfg.getString("searchRandSeed") : Global::uint64ToString(seedRand->nextUInt64());
        bot = std::make_unique<AsyncBot>(params, g_nnEval, logger.get(), searchRandSeed);
        bot->setAlwaysIncludeOwnerMap(true);

        step = 18;
        LOGI("Step 18: Initializing bot position...");
        Board board;
        Player pla = P_BLACK;
        BoardHistory hist(board, pla, rules, 0);
        bot->setPosition(pla, board, hist);

        initialized = true;
        LOGI("--- Engine Initialization Successful! ---");
    } catch (const std::exception& e) {
        LOGE("FATAL Exception at step %d: %s", step, e.what());
        initialized = false;
        env->ReleaseStringUTFChars(config_path, cConfigPath);
        env->ReleaseStringUTFChars(model_path, cModelPath);
        return -step;
    } catch (...) {
        LOGE("FATAL Unknown error at step %d", step);
        initialized = false;
        env->ReleaseStringUTFChars(config_path, cConfigPath);
        env->ReleaseStringUTFChars(model_path, cModelPath);
        return -step;
    }

    env->ReleaseStringUTFChars(config_path, cConfigPath);
    env->ReleaseStringUTFChars(model_path, cModelPath);

    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cwave_weiqi_katago_KataGoBridge_sendGtpCommand(JNIEnv* env, jobject thiz, jstring command) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!initialized) return env->NewStringUTF("? engine not initialized");

    const char* cCommand = env->GetStringUTFChars(command, nullptr);
    std::string cmd(cCommand);
    env->ReleaseStringUTFChars(command, cCommand);

    // Never let an engine exception escape into the JVM (it would abort the app).
    std::string resp;
    try {
        resp = runCommand(cmd);
    } catch (const std::exception& e) {
        LOGE("GTP command '%s' failed: %s", cmd.c_str(), e.what());
        resp = "? " + std::string(e.what());
    } catch (...) {
        LOGE("GTP command '%s' failed with an unknown exception", cmd.c_str());
        resp = "? internal error";
    }
    return env->NewStringUTF(resp.c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_cwave_weiqi_katago_KataGoBridge_getBoardState(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!initialized) return nullptr;

    const Board& board = bot->getRootBoard();
    int xSize = board.x_size;
    int ySize = board.y_size;
    int size = xSize * ySize;

    jintArray result = env->NewIntArray(size);
    jint* fill = new jint[size];

    for (int y = 0; y < ySize; y++) {
        for (int x = 0; x < xSize; x++) {
            Player pla = board.colors[Location::getLoc(x, y, xSize)];
            // 0: EMPTY (P_NONE), 1: BLACK (P_BLACK), 2: WHITE (P_WHITE)
            fill[y * xSize + x] = (jint)pla;
        }
    }

    env->SetIntArrayRegion(result, 0, size, fill);
    delete[] fill;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cwave_weiqi_katago_KataGoBridge_shutdown(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!initialized) return;

    LOGI("Shutting down engine...");
    bot->stopAndWait();
    bot.reset();
    if(g_nnEval != nullptr) {
        delete g_nnEval;
        g_nnEval = nullptr;
    }
    logger.reset();
    seedRand.reset();
    initialized = false;
    LOGI("Engine shutdown complete.");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cwave_weiqi_katago_KataGoBridgeEigen_init(JNIEnv *env, jobject thiz, jstring config_path, jstring model_path) {
    return Java_com_cwave_weiqi_katago_KataGoBridge_init(env, thiz, config_path, model_path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cwave_weiqi_katago_KataGoBridgeEigen_sendGtpCommand(JNIEnv* env, jobject thiz, jstring command) {
    return Java_com_cwave_weiqi_katago_KataGoBridge_sendGtpCommand(env, thiz, command);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_cwave_weiqi_katago_KataGoBridgeEigen_getBoardState(JNIEnv* env, jobject thiz) {
    return Java_com_cwave_weiqi_katago_KataGoBridge_getBoardState(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cwave_weiqi_katago_KataGoBridgeEigen_shutdown(JNIEnv* env, jobject thiz) {
    Java_com_cwave_weiqi_katago_KataGoBridge_shutdown(env, thiz);
}

