#include <jni.h>
#include <string>
#include <vector>
#include <camera/NdkCameraManager.h>
#include <android/log.h>

#define LOG_TAG "RodytoLensPro_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rodyto_lenspro_CameraControlViewModel_getPhysicalCameraIdsNative(JNIEnv *env, jobject thiz) {
    ACameraManager* cameraManager = ACameraManager_create();
    ACameraIdList* cameraIdList = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(cameraManager, &cameraIdList);

    std::vector<std::string> physicalLenses;

    if (status == ACAMERA_OK) {
        for (int i = 0; i < cameraIdList->numCameras; ++i) {
            const char* logicalId = cameraIdList->cameraIds[i];
            ACameraMetadata* metadata = nullptr;
            ACameraManager_getCameraCharacteristics(cameraManager, logicalId, &metadata);

            // Intentar obtener los IDs físicos que respaldan esta cámara lógica
            ACameraMetadata_const_entry entry;
            if (ACameraMetadata_getConstEntry(metadata, ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS, &entry) == ACAMERA_OK) {
                // Parsear la cadena de IDs físicos (vienen separados por bytes nulos)
                const uint8_t* ids = entry.data.u8;
                size_t length = entry.count;
                std::string currentId = "";
                for (size_t j = 0; j < length; ++j) {
                    if (ids[j] == '\0') {
                        if (!currentId.empty()) physicalLenses.push_back(currentId);
                        currentId = "";
                    } else {
                        currentId += (char)ids[j];
                    }
                }
            } else {
                // Si no es multicámara lógica, es un sensor físico puro (Legacy)
                physicalLenses.push_back(std::string(logicalId));
            }
            ACameraMetadata_free(metadata);
        }
        ACameraManager_deleteCameraIdList(cameraIdList);
    }
    ACameraManager_delete(cameraManager);

    // Convertir el vector de C++ a un Array de Strings para que Kotlin lo entienda
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(physicalLenses.size(), stringClass, env->NewStringUTF(""));
    for (size_t i = 0; i < physicalLenses.size(); ++i) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(physicalLenses[i].c_str()));
    }

    return result;
}
