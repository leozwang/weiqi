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

    std::vector<std::string> parts = split(cmd);
    if (parts.empty()) return env->NewStringUTF("= ");

    std::string mainCmd = parts[0];

    if (mainCmd == "name") return env->NewStringUTF("= KataGo");
    if (mainCmd == "version") return env->NewStringUTF(Version::getKataGoVersion().c_str());
    if (mainCmd == "protocol_version") return env->NewStringUTF("= 2");

    if (mainCmd == "genmove") {
        if (parts.size() < 2) return env->NewStringUTF("? missing color");
        std::string colorStr = parts[1];
        Player pla;
        if (colorStr == "black" || colorStr == "b" || colorStr == "B") pla = P_BLACK;
        else if (colorStr == "white" || colorStr == "w" || colorStr == "W") pla = P_WHITE;
        else return env->NewStringUTF("? invalid color");

        LOGI("Starting AI search (genmove) for %s...", colorStr.c_str());
        auto startTime = std::chrono::steady_clock::now();
        
        TimeControls tc;
        Loc moveLoc = bot->genMoveSynchronous(pla, tc);
        
        auto endTime = std::chrono::steady_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
        
        std::string moveStr = Location::toString(moveLoc, bot->getRootBoard());
        LOGI("AI search completed in %lld ms. Move: %s", duration, moveStr.c_str());

        bot->makeMove(moveLoc, pla);
        return env->NewStringUTF(("= " + moveStr).c_str());
    }

    if (mainCmd == "set_max_visits") {
        if (parts.size() < 2) return env->NewStringUTF("? missing visits");
        int64_t v = 400;
        try {
            v = std::stoll(parts[1]);
        } catch (...) {
            return env->NewStringUTF("? invalid visits");
        }
        SearchParams params = bot->getParams();
        params.maxVisits = v;
        params.maxPlayouts = v;
        bot->setParams(params);
        LOGI("AI max visits updated to %ld", (long)v);
        return env->NewStringUTF("= ok");
    }

    if (mainCmd == "think") {
        if (parts.size() < 2) return env->NewStringUTF("? missing color");
        std::string colorStr = parts[1];
        Player pla;
        if (colorStr == "black" || colorStr == "b" || colorStr == "B") pla = P_BLACK;
        else if (colorStr == "white" || colorStr == "w" || colorStr == "W") pla = P_WHITE;
        else return env->NewStringUTF("? invalid color");

        int64_t visits = 400;
        if (parts.size() >= 3) {
            try {
                visits = std::stoll(parts[2]);
            } catch (...) {}
        }

        LOGI("AI starting thinking search (think) for %s with %ld visits...", colorStr.c_str(), (long)visits);
        
        SearchParams oldParams = bot->getParams();
        SearchParams newParams = oldParams;
        newParams.maxVisits = visits;
        newParams.maxPlayouts = visits;
        
        // Use setParamsNoClearing to avoid wiping the tree before/after search
        bot->setParamsNoClearing(newParams);
        bot->genMoveSynchronous(pla, TimeControls());
        bot->setParamsNoClearing(oldParams);
        
        LOGI("AI thinking search completed.");
        return env->NewStringUTF("= ok");
    }

    if (mainCmd == "play") {
        if (parts.size() < 3) return env->NewStringUTF("? missing color or move");
        std::string colorStr = parts[1];
        std::string moveStr = parts[2];
        Player pla;
        if (colorStr == "black" || colorStr == "b" || colorStr == "B") pla = P_BLACK;
        else if (colorStr == "white" || colorStr == "w" || colorStr == "W") pla = P_WHITE;
        else return env->NewStringUTF("? invalid color");

        Loc moveLoc = Location::ofString(moveStr, bot->getRootBoard());
        if (moveLoc == Board::NULL_LOC && moveStr != "pass" && moveStr != "PASS") return env->NewStringUTF("? invalid move");

        if (!bot->makeMove(moveLoc, pla)) {
            return env->NewStringUTF("? illegal move");
        }
        return env->NewStringUTF("= ");
    }

    if (mainCmd == "clear_board") {
        Rules rules = bot->getRootHist().rules;
        int xSize = bot->getRootBoard().x_size;
        int ySize = bot->getRootBoard().y_size;
        Board board(xSize, ySize);
        Player pla = P_BLACK;
        BoardHistory hist(board, pla, rules, 0);
        bot->setPosition(pla, board, hist);
        return env->NewStringUTF("= ");
    }

    if (mainCmd == "fixed_handicap") {
        if (parts.size() < 2) return env->NewStringUTF("? missing count");
        int n = 0;
        try {
            n = std::stoi(parts[1]);
        } catch (...) {
            return env->NewStringUTF("? invalid count");
        }
        if (n < 2 || n > 9) return env->NewStringUTF("? handicap must be 2-9");

        Board board = bot->getRootBoard();
        // Clear board first to ensure standard handicap placement
        board = Board(board.x_size, board.y_size);

        try {
            PlayUtils::placeFixedHandicap(board, n);
        } catch (const std::exception& e) {
            return env->NewStringUTF(("? " + std::string(e.what())).c_str());
        }

        Player pla = P_WHITE; 
        BoardHistory hist(board, pla, bot->getRootHist().rules, 0);
        bot->setPosition(pla, board, hist);

        std::string resp = "= ";
        for (int y = 0; y < board.y_size; y++) {
            for (int x = 0; x < board.x_size; x++) {
                if (board.colors[Location::getLoc(x, y, board.x_size)] == P_BLACK) {
                    resp += Location::toString(Location::getLoc(x, y, board.x_size), board) + " ";
                }
            }
        }
        return env->NewStringUTF(resp.c_str());
    }

    if (mainCmd == "undo") {
        const BoardHistory& hist = bot->getRootHist();
        if (hist.moveHistory.size() == 0) return env->NewStringUTF("? cannot undo");

        Board board = hist.initialBoard;
        Player pla = hist.initialPla;
        Rules rules = hist.rules;
        BoardHistory newHist(board, pla, rules, hist.initialEncorePhase);

        for (size_t i = 0; i < hist.moveHistory.size() - 1; i++) {
            newHist.makeBoardMoveAssumeLegal(board, hist.moveHistory[i].loc, hist.moveHistory[i].pla, nullptr);
        }
        bot->setPosition(newHist.presumedNextMovePla, board, newHist);
        return env->NewStringUTF("= ");
    }

    if (mainCmd == "final_score") {
        bot->stopAndWait();
        const BoardHistory& rootHist = bot->getRootHist();
        Player winner = C_EMPTY;
        double score = 0.0;

        if (rootHist.isGameFinished) {
            winner = rootHist.winner;
            score = rootHist.finalWhiteMinusBlackScore;
        } else {
            // Game not finished, estimate lead
            // Copy history because computeLead takes non-const ref
            BoardHistory histCopy = rootHist;
            score = PlayUtils::computeLead(
                bot->getSearchStopAndWait(),
                NULL,
                bot->getRootBoard(),
                histCopy,
                bot->getRootPla(),
                100, // visits
                OtherGameProperties()
            );
            winner = score > 0 ? P_WHITE : (score < 0 ? P_BLACK : C_EMPTY);
        }

        std::string resp = "= ";
        if (winner == C_EMPTY) resp += "0";
        else if (winner == P_BLACK) resp += "B+" + Global::strprintf("%.1f", -score);
        else if (winner == P_WHITE) resp += "W+" + Global::strprintf("%.1f", score);
        return env->NewStringUTF(resp.c_str());
    }

    if (mainCmd == "kata-get-analysis") {
        nlohmann::json json;
        // perspective: P_BLACK is usually 1, P_WHITE is 2 in KataGo
        Player perspective = bot->getRootPla();
        if (parts.size() >= 2) {
            std::string pStr = parts[1];
            if (pStr == "black" || pStr == "b") perspective = P_BLACK;
            else if (pStr == "white" || pStr == "w") perspective = P_WHITE;
        }

        bool success = bot->getSearch()->getAnalysisJson(
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

        if (!success) return env->NewStringUTF("? failed to get analysis");

        std::string jsonStr = json.dump();
        return env->NewStringUTF(("= " + jsonStr).c_str());
    }

    return env->NewStringUTF("= ok");
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
