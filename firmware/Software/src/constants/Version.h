#ifndef OSSM_VERSION_H
#define OSSM_VERSION_H

#define VERSION "1.1.0"
#define MAJOR_VERSION 1
#define MINOR_VERSION 1
#define PATCH_VERSION 0

#define OSSM_BUILD_IDENTITY                                                   \
    "OSSM_BUILD_IDENTITY=version=" VERSION ";track=" FIRMWARE_TRACK        \
    ";sha=" FIRMWARE_BUILD_SHA

#endif  // OSSM_VERSION_H
