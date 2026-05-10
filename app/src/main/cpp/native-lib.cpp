#include <jni.h>
#include <string>
#include <vector>
#include <set>
#include <android/log.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraDevice.h>

#define LOG_TAG "RodytoLensPro_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rodyto_lenspro_CameraControlViewModel_getPhysicalCameraIdsNative(
        JNIEnv *env,
        jobject /* thiz */) {

    std::set<std::string> collectedIds;

    ACameraManager* cameraManager = ACameraManager_create();
    if (cameraManager == nullptr) {
        LOGE("No se pudo crear ACameraManager");
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    ACameraIdList* cameraIdList = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(cameraManager, &cameraIdList);

    if (status == ACAMERA_OK && cameraIdList != nullptr) {
        for (int i = 0; i < cameraIdList->numCameras; ++i) {
            const char* logicalId = cameraIdList->cameraIds[i];
            if (logicalId == nullptr) {
                continue;
            }

            ACameraMetadata* metadata = nullptr;
            if (ACameraManager_getCameraCharacteristics(cameraManager, logicalId, &metadata) != ACAMERA_OK ||
                metadata == nullptr) {
                collectedIds.insert(std::string(logicalId));
                continue;
            }

            ACameraMetadata_const_entry entry;
            if (ACameraMetadata_getConstEntry(
                    metadata,
                    ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS,
                    &entry) == ACAMERA_OK && entry.count > 0) {

                const uint8_t* ids = entry.data.u8;
                std::string currentId;

                for (uint32_t j = 0; j < entry.count; ++j) {
                    if (ids[j] == '\0') {
                        if (!currentId.empty()) {
                            collectedIds.insert(currentId);
                            currentId.clear();
                        }
                    } else {
                        currentId += static_cast<char>(ids[j]);
                    }
                }

                if (!currentId.empty()) {
                    collectedIds.insert(currentId);
                }
            } else {
                collectedIds.insert(std::string(logicalId));
            }

            ACameraMetadata_free(metadata);
        }

        ACameraManager_deleteCameraIdList(cameraIdList);
    }

    ACameraManager_delete(cameraManager);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(collectedIds.size()),
            stringClass,
            nullptr
    );

    int index = 0;
    for (const auto& id : collectedIds) {
        env->SetObjectArrayElement(result, index++, env->NewStringUTF(id.c_str()));
    }

    return result;
}
