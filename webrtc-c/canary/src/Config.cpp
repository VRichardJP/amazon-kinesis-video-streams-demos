#include "Include.h"

namespace Canary {

STATUS Config::init(INT32 argc, PCHAR argv[])
{
    // TODO: Probably also support command line args to fill the config
    STATUS retStatus = STATUS_SUCCESS;

    CHK(argv != NULL, STATUS_NULL_ARG);
    MEMSET(this, 0, SIZEOF(Config));

    if (argc == 2) {
        DLOGI("Reading configuration from %s\n", argv[1]);
        CHK_STATUS(initWithJSON(argv[1]));
    }

    CHK_STATUS(initWithEnvVars());

    // Need to impose a min duration
    if (duration.value != 0 && duration.value < CANARY_MIN_DURATION) {
        DLOGW("Canary duration should be at least %u seconds. Overriding with minimal duration.",
              CANARY_MIN_DURATION / HUNDREDS_OF_NANOS_IN_A_SECOND);
        duration.value = CANARY_MIN_DURATION;
    }

    // Need to impose a min iteration duration
    if (iterationDuration.value < CANARY_MIN_ITERATION_DURATION) {
        DLOGW("Canary iterations duration should be at least %u seconds. Overriding with minimal iterations duration.",
              CANARY_MIN_ITERATION_DURATION / HUNDREDS_OF_NANOS_IN_A_SECOND);
        iterationDuration.value = CANARY_MIN_ITERATION_DURATION;
    }

CleanUp:

    return retStatus;
}

BOOL strtobool(const CHAR* value)
{
    if (STRCMPI(value, "on") == 0 || STRCMPI(value, "true") == 0) {
        return TRUE;
    }

    return FALSE;
}

STATUS mustenv(CHAR const* pKey, Config::Value<Config::String>* pResult)
{
    STATUS retStatus = STATUS_SUCCESS;
    const CHAR* value;

    CHK(pResult != NULL, STATUS_NULL_ARG);
    CHK(!pResult->initialized, retStatus);

    CHK_ERR((value = getenv(pKey)) != NULL, STATUS_INVALID_OPERATION, "%s must be set", pKey);
    SNPRINTF(pResult->value, ARRAY_SIZE(pResult->value), "%s", value);
    pResult->initialized = TRUE;

CleanUp:

    return retStatus;
}

STATUS optenv(CHAR const* pKey, Config::Value<Config::String>* pResult, const CHAR* pDefault)
{
    STATUS retStatus = STATUS_SUCCESS;
    const CHAR* value;

    CHK(pResult != NULL, STATUS_NULL_ARG);
    CHK(!pResult->initialized, retStatus);

    if (NULL == (value = getenv(pKey))) {
        value = pDefault;
    }
    SNPRINTF(pResult->value, ARRAY_SIZE(pResult->value), "%s", value);
    pResult->initialized = TRUE;

CleanUp:

    return retStatus;
}

STATUS mustenvBool(CHAR const* pKey, Config::Value<BOOL>* pResult)
{
    STATUS retStatus = STATUS_SUCCESS;
    Config::Value<Config::String> raw;

    CHK(pResult != NULL, STATUS_NULL_ARG);
    CHK(!pResult->initialized, retStatus);
    CHK_STATUS(mustenv(pKey, &raw));

    pResult->value = strtobool(raw.value);
    pResult->initialized = TRUE;

CleanUp:

    return retStatus;
}

STATUS optenvBool(CHAR const* pKey, Config::Value<BOOL>* pResult, BOOL defVal)
{
    STATUS retStatus = STATUS_SUCCESS;
    Config::Value<Config::String> raw;

    CHK(pResult != NULL, STATUS_NULL_ARG);
    CHK(!pResult->initialized, retStatus);
    CHK_STATUS(optenv(pKey, &raw, ""));
    if (!IS_EMPTY_STRING(raw.value)) {
        pResult->value = strtobool(raw.value);
    } else {
        pResult->value = defVal;
    }
    pResult->initialized = TRUE;

CleanUp:

    return retStatus;
}

STATUS mustenvUint64(CHAR const* pKey, Config::Value<UINT64>* pResult)
{
    STATUS retStatus = STATUS_SUCCESS;
    Config::Value<Config::String> raw;

    CHK(pResult != NULL, STATUS_NULL_ARG);
    CHK(!pResult->initialized, retStatus);
    CHK_STATUS(mustenv(pKey, &raw));

    STRTOUI64((PCHAR) raw.value, NULL, 10, &pResult->value);
    pResult->initialized = TRUE;

CleanUp:

    return retStatus;
}

STATUS optenvUint64(CHAR const* pKey, Config::Value<UINT64>* pResult, UINT64 defVal)
{
    STATUS retStatus = STATUS_SUCCESS;
    Config::Value<Config::String> raw;

    CHK(pResult != NULL, STATUS_NULL_ARG);
    CHK(!pResult->initialized, retStatus);
    CHK_STATUS(optenv(pKey, &raw, ""));
    if (!IS_EMPTY_STRING(raw.value)) {
        STRTOUI64((PCHAR) raw.value, NULL, 10, &pResult->value);
    } else {
        pResult->value = defVal;
    }
    pResult->initialized = TRUE;

CleanUp:

    return retStatus;
}

STATUS Config::initWithEnvVars()
{
    STATUS retStatus = STATUS_SUCCESS;
    Config::Value<UINT64> logLevel64;
    PCHAR pLogStreamName;
    Config::Value<Config::String> logGroupName;

    /* This is ignored for master. Master can extract the info from offer. Viewer has to know if peer can trickle or
     * not ahead of time. */
    CHK_STATUS(optenvBool(CANARY_TRICKLE_ICE_ENV_VAR, &trickleIce, FALSE));
    CHK_STATUS(optenvBool(CANARY_USE_TURN_ENV_VAR, &useTurn, TRUE));
    CHK_STATUS(optenvBool(CANARY_FORCE_TURN_ENV_VAR, &forceTurn, FALSE));

    CHK_STATUS(mustenv(ACCESS_KEY_ENV_VAR, &accessKey));
    CHK_STATUS(mustenv(SECRET_KEY_ENV_VAR, &secretKey));
    CHK_STATUS(optenv(SESSION_TOKEN_ENV_VAR, &sessionToken, ""));
    CHK_STATUS(optenv(DEFAULT_REGION_ENV_VAR, &region, DEFAULT_AWS_REGION));

    // Set the logger log level
    if (!logLevel.initialized) {
        CHK_STATUS(optenvUint64(DEBUG_LOG_LEVEL_ENV_VAR, &logLevel64, LOG_LEVEL_WARN));
        logLevel.value = (UINT32) logLevel64.value;
        logLevel.initialized = TRUE;
    }

    CHK_STATUS(optenv(CANARY_CHANNEL_NAME_ENV_VAR, &channelName, CANARY_DEFAULT_CHANNEL_NAME));
    CHK_STATUS(optenv(CANARY_CLIENT_ID_ENV_VAR, &clientId, CANARY_DEFAULT_CLIENT_ID));
    CHK_STATUS(optenvBool(CANARY_IS_MASTER_ENV_VAR, &isMaster, TRUE));
    CHK_STATUS(optenvBool(CANARY_RUN_BOTH_PEERS_ENV_VAR, &runBothPeers, FALSE));

    if (!this->logGroupName.initialized) {
        CHK_STATUS(optenv(CANARY_LOG_GROUP_NAME_ENV_VAR, &logGroupName, CANARY_DEFAULT_LOG_GROUP_NAME));
        SNPRINTF(this->logGroupName.value, ARRAY_SIZE(this->logGroupName.value), "%s", logGroupName.value);
        this->logGroupName.initialized = TRUE;
    }

    if (!this->logStreamName.initialized) {
        pLogStreamName = getenv(CANARY_LOG_STREAM_NAME_ENV_VAR);
        if (pLogStreamName != NULL) {
            SNPRINTF(this->logStreamName.value, ARRAY_SIZE(this->logStreamName.value), "%s", pLogStreamName);
        } else {
            SNPRINTF(this->logStreamName.value, ARRAY_SIZE(this->logStreamName.value), "%s-%s-%llu", channelName.value,
                     isMaster.value ? "master" : "viewer", GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
        }
    }

    if (!duration.initialized) {
        CHK_STATUS(optenvUint64(CANARY_DURATION_IN_SECONDS_ENV_VAR, &duration, 0));
        duration.value *= HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    // Iteration duration is an optional param
    if (!iterationDuration.initialized) {
        CHK_STATUS(optenvUint64(CANARY_ITERATION_IN_SECONDS_ENV_VAR, &iterationDuration, CANARY_DEFAULT_ITERATION_DURATION_IN_SECONDS));
        iterationDuration.value *= HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    CHK_STATUS(optenvUint64(CANARY_BIT_RATE_ENV_VAR, &bitRate, CANARY_DEFAULT_BITRATE));
    CHK_STATUS(optenvUint64(CANARY_FRAME_RATE_ENV_VAR, &frameRate, CANARY_DEFAULT_FRAMERATE));

CleanUp:

    return retStatus;
}

VOID Config::print()
{
    DLOGD("\n\n"
          "\tChannel Name  : %s\n"
          "\tRegion        : %s\n"
          "\tClient ID     : %s\n"
          "\tRole          : %s\n"
          "\tTrickle ICE   : %s\n"
          "\tUse TURN      : %s\n"
          "\tLog Level     : %u\n"
          "\tLog Group     : %s\n"
          "\tLog Stream    : %s\n"
          "\tDuration      : %lu seconds\n"
          "\tIteration     : %lu seconds\n"
          "\tRun both peers: %s\n"
          "\n",
          this->channelName.value, this->region.value, this->clientId.value, this->isMaster.value ? "Master" : "Viewer",
          this->trickleIce.value ? "True" : "False", this->useTurn.value ? "True" : "False", this->logLevel.value, this->logGroupName.value,
          this->logStreamName.value, this->duration.value / HUNDREDS_OF_NANOS_IN_A_SECOND,
          this->iterationDuration.value / HUNDREDS_OF_NANOS_IN_A_SECOND, this->runBothPeers.value ? "True" : "False");
}

VOID jsonString(PBYTE pRaw, jsmntok_t token, Config::Value<Config::String>* pResult)
{
    UINT32 tokenLength = (UINT32)(token.end - token.start);

    SNPRINTF(pResult->value, MIN(tokenLength + 1, ARRAY_SIZE(pResult->value)), "%s", (const CHAR*) pRaw + token.start);
    pResult->initialized = TRUE;
}

VOID jsonBool(PBYTE pRaw, jsmntok_t token, Config::Value<BOOL>* pResult)
{
    Config::Value<Config::String> raw;

    jsonString(pRaw, token, &raw);
    pResult->value = strtobool((const CHAR*) &raw.value);
    pResult->initialized = TRUE;
}

VOID jsonUint64(PBYTE pRaw, jsmntok_t token, Config::Value<UINT64>* pResult)
{
    Config::Value<Config::String> raw;

    jsonString(pRaw, token, &raw);
    STRTOUI64((PCHAR) raw.value, NULL, 10, &pResult->value);
    pResult->initialized = TRUE;
}

STATUS Config::initWithJSON(PCHAR filePath)
{
    STATUS retStatus = STATUS_SUCCESS;
    UINT64 size;
    jsmn_parser parser;
    int r;
    BYTE raw[MAX_CONFIG_JSON_FILE_SIZE];
    Config::Value<UINT64> logLevel64;

    CHK_STATUS(readFile(filePath, TRUE, NULL, &size));
    CHK_ERR(size < MAX_CONFIG_JSON_FILE_SIZE, STATUS_INVALID_ARG_LEN, "File size too big. Max allowed is 1024 bytes");
    CHK_STATUS(readFile(filePath, TRUE, raw, &size));

    jsmn_init(&parser);
    jsmntok_t tokens[MAX_CONFIG_JSON_TOKENS];

    r = jsmn_parse(&parser, (PCHAR) raw, size, tokens, MAX_CONFIG_JSON_TOKENS);
    for (UINT32 i = 0; i < (UINT32) r; i++) {
        if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_CHANNEL_NAME_ENV_VAR)) {
            jsonString(raw, tokens[++i], &channelName);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_CLIENT_ID_ENV_VAR)) {
            jsonString(raw, tokens[++i], &clientId);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_TRICKLE_ICE_ENV_VAR)) {
            jsonBool(raw, tokens[++i], &trickleIce);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_IS_MASTER_ENV_VAR)) {
            jsonBool(raw, tokens[++i], &isMaster);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_USE_TURN_ENV_VAR)) {
            jsonBool(raw, tokens[++i], &useTurn);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_LOG_GROUP_NAME_ENV_VAR)) {
            jsonString(raw, tokens[++i], &logGroupName);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_LOG_STREAM_NAME_ENV_VAR)) {
            jsonString(raw, tokens[++i], &logStreamName);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_DURATION_IN_SECONDS_ENV_VAR)) {
            jsonUint64(raw, tokens[++i], &duration);
            duration.value *= HUNDREDS_OF_NANOS_IN_A_SECOND;
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_ITERATION_IN_SECONDS_ENV_VAR)) {
            jsonUint64(raw, tokens[++i], &iterationDuration);
            iterationDuration.value *= HUNDREDS_OF_NANOS_IN_A_SECOND;
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_FORCE_TURN_ENV_VAR)) {
            jsonBool(raw, tokens[++i], &forceTurn);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_BIT_RATE_ENV_VAR)) {
            jsonUint64(raw, tokens[++i], &bitRate);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_FRAME_RATE_ENV_VAR)) {
            jsonUint64(raw, tokens[++i], &frameRate);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) CANARY_RUN_BOTH_PEERS_ENV_VAR)) {
            jsonBool(raw, tokens[++i], &runBothPeers);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) DEFAULT_REGION_ENV_VAR)) {
            jsonString(raw, tokens[++i], &region);
        } else if (compareJsonString((PCHAR) raw, &tokens[i], JSMN_STRING, (PCHAR) DEBUG_LOG_LEVEL_ENV_VAR)) {
            jsonUint64(raw, tokens[++i], &logLevel64);
            logLevel.value = (UINT32) logLevel64.value;
            logLevel.initialized = TRUE;
        }
    }

CleanUp:
    return retStatus;
}

} // namespace Canary
