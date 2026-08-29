// BranchDamCoreHeader.h - C definitions for branchdam-mobile Go core engine
#ifndef BranchDamCoreHeader_h
#define BranchDamCoreHeader_h

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

int32_t InitCore(const char* dbPath, const char* baseURL, const char* apiKey, const char* agentID, const char* clientVersion);
int64_t EnqueueMedia(const char* localPath, const char* filename, int64_t capturedAtUnix, const char* localID);
char* EnqueueLineageEvent(const char* parentUUID, const char* childUUID, const char* relationshipType, const char* resolver, double confidence);
char* EnqueueDeleteEvent(const char* nodeUUID);
int32_t SyncBatch(int32_t timeoutSecs, int32_t batchSize);
char* CheckSafeSpaceCandidates(const char* localIDsJSON);
int32_t SetMediaOffloaded(const char* localID, int32_t isOffloaded);
int32_t GetMediaOffloaded(const char* localID);
char* FetchNamingTemplate(void);
void FreeCString(char* str);

#ifdef __cplusplus
}
#endif

#endif /* BranchDamCoreHeader_h */
